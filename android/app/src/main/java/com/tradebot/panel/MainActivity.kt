package com.tradebot.panel

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Paneli gösterir. Bot verisi GitHub'daki data/status.json dosyasından okunur.
 * Binance anahtarı telefonda tutulmaz; anahtarlar GitHub'ın gizli değişkenlerindedir.
 */
class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("bot", MODE_PRIVATE) }
    private val api = "https://api.github.com/repos/${BuildConfig.REPO_OWNER}/${BuildConfig.REPO_NAME}"
    private val workflow = "run-bot.yml"

    private lateinit var web: WebView
    private lateinit var tokenInput: EditText
    private var pageReady = false
    private var pending: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.dash)
        tokenInput = findViewById(R.id.token)
        web.settings.javaScriptEnabled = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pending?.let { push(it) }
                pending = null
            }
        }
        web.loadUrl("file:///android_asset/dashboard.html")

        tokenInput.setText(prefs.getString("token", ""))

        findViewById<Button>(R.id.saveToken).setOnClickListener {
            prefs.edit().putString("token", tokenInput.text.toString().trim()).apply()
            toast("Token kaydedildi")
            refresh()
        }
        findViewById<Button>(R.id.runBot).setOnClickListener { dispatch("330") }
        findViewById<Button>(R.id.testRun).setOnClickListener { dispatch("1") }
        findViewById<Button>(R.id.refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.demo).setOnClickListener {
            toast("Demo verisi: gerçek değil")
            web.evaluateJavascript("window.renderDemo && window.renderDemo();", null)
        }

        refresh()
    }

    private fun token(): String? = prefs.getString("token", null)?.takeIf { it.isNotBlank() }

    private fun dispatch(minutes: String) {
        val t = token() ?: return toast("Önce token'ı kaydet")
        Thread {
            try {
                if (activeRun(t) != null) {
                    runOnUiThread { toast("Zaten bir çalışma var, onu bekle") }
                    return@Thread
                }
                val body = JSONObject().put("ref", "main")
                    .put("inputs", JSONObject().put("minutes", minutes))
                val code = request("POST", "$api/actions/workflows/$workflow/dispatches", t, body.toString())
                runOnUiThread {
                    if (code == 204) {
                        toast("Çalışma başlatıldı")
                        refresh()
                    } else {
                        toast("Başlatılamadı (HTTP $code). Token yetkisini kontrol et.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Hata: ${e.message}") }
            }
        }.start()
    }

    private fun refresh() {
        val t = token() ?: return
        Thread {
            val payload = JSONObject()
            try {
                val runs = JSONObject(get("$api/actions/workflows/$workflow/runs?per_page=5", t))
                    .getJSONArray("workflow_runs")
                val run = pickRun(runs)
                val runInfo = JSONObject()
                if (run == null) {
                    runInfo.put("state", "Henüz çalışma yok")
                } else {
                    runInfo.put("state", describe(run.optString("status"), run.optString("conclusion")))
                    runInfo.put("detail", "Başlangıç: ${run.optString("run_started_at")}")
                }
                payload.put("run", runInfo)
            } catch (e: Exception) {
                payload.put("run", JSONObject().put("state", "Durum alınamadı").put("detail", e.message ?: ""))
            }
            try {
                payload.put("status", readStatusFile(t))
            } catch (e: Exception) {
                payload.put("status", JSONObject())
            }
            runOnUiThread { push(payload.toString()) }
        }.start()
    }

    private fun push(json: String) {
        if (!pageReady) {
            pending = json
            return
        }
        web.evaluateJavascript("window.render($json);", null)
    }

    /** Aktif (çalışan ya da sırada bekleyen) ilk çalışmayı döndürür, yoksa null. */
    private fun activeRun(token: String): JSONObject? {
        val runs = JSONObject(get("$api/actions/workflows/$workflow/runs?per_page=5", token))
            .getJSONArray("workflow_runs")
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            if (r.optString("status") in ACTIVE) return r
        }
        return null
    }

    private fun pickRun(runs: JSONArray): JSONObject? {
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            if (r.optString("status") in ACTIVE) return r
        }
        return runs.optJSONObject(0)
    }

    private fun describe(status: String, conclusion: String): String = when (status) {
        "in_progress" -> "Bot çalışıyor"
        "queued", "pending", "waiting", "requested" -> "Sırada bekliyor"
        "completed" -> when (conclusion) {
            "success" -> "Tamamlandı"
            "failure" -> "Hata verdi"
            "cancelled" -> "İptal edildi"
            else -> "Bitti ($conclusion)"
        }
        else -> status
    }

    /** Bot her döngüde data/status.json dosyasını repoya yazar (çalışma sonunda). */
    private fun readStatusFile(token: String): JSONObject {
        val file = JSONObject(get("$api/contents/data/status.json", token))
        val raw = file.getString("content").replace("\n", "")
        return JSONObject(String(Base64.decode(raw, Base64.DEFAULT)))
    }

    private fun get(url: String, token: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun request(method: String, url: String, token: String, body: String): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn.responseCode
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private val ACTIVE = setOf("in_progress", "queued", "pending", "waiting", "requested")
    }
}
