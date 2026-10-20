package com.tradebot.panel

import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Botu GitHub Actions üzerinden başlatır, çalışma durumunu ve bot verisini gösterir.
 * Binance anahtarı telefonda tutulmaz; anahtarlar GitHub'ın gizli değişkenlerindedir.
 */
class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("bot", MODE_PRIVATE) }
    private val api = "https://api.github.com/repos/${BuildConfig.REPO_OWNER}/${BuildConfig.REPO_NAME}"
    private val workflow = "run-bot.yml"

    private lateinit var tokenInput: EditText
    private lateinit var runState: TextView
    private lateinit var runDetail: TextView
    private lateinit var botData: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tokenInput = findViewById(R.id.token)
        runState = findViewById(R.id.runState)
        runDetail = findViewById(R.id.runDetail)
        botData = findViewById(R.id.botData)

        tokenInput.setText(prefs.getString("token", ""))

        findViewById<Button>(R.id.saveToken).setOnClickListener {
            prefs.edit().putString("token", tokenInput.text.toString().trim()).apply()
            toast("Token kaydedildi")
            refresh()
        }
        findViewById<Button>(R.id.runBot).setOnClickListener { dispatch("330") }
        findViewById<Button>(R.id.testRun).setOnClickListener { dispatch("1") }
        findViewById<Button>(R.id.refresh).setOnClickListener { refresh() }

        refresh()
    }

    private fun token(): String? = prefs.getString("token", null)?.takeIf { it.isNotBlank() }

    private fun dispatch(minutes: String) {
        val t = token() ?: return toast("Önce token'ı kaydet")
        Thread {
            try {
                // Zaten aktif bir çalışma varsa yenisini başlatma, çift çalışma olmasın.
                val active = activeRun(t)
                if (active != null) {
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
        val t = token()
        if (t == null) {
            runState.text = "Token kaydedilmedi"
            runDetail.text = "Önce GitHub token'ını gir ve kaydet."
            return
        }
        Thread {
            var state = ""
            var detail = ""
            var data = ""
            try {
                val runs = JSONObject(get("$api/actions/workflows/$workflow/runs?per_page=5", t))
                    .getJSONArray("workflow_runs")
                val run = pickRun(runs)
                if (run == null) {
                    state = "Henüz çalışma yok"
                } else {
                    state = describe(run.optString("status"), run.optString("conclusion"))
                    detail = "Başlangıç: ${run.optString("run_started_at")}\n${run.optString("html_url")}"
                }
            } catch (e: Exception) {
                state = "Durum alınamadı"
                detail = e.message ?: ""
            }
            try {
                data = formatBotData(readStatusFile(t))
            } catch (e: Exception) {
                data = "Henüz veri yok. Bot bir çalışmayı bitirince burada görünür."
            }
            val s = state
            val d = detail
            val b = data
            runOnUiThread {
                runState.text = s
                runDetail.text = d
                botData.text = b
            }
        }.start()
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

    /** Bot her çalışma sonunda data/status.json dosyasını repoya yazar. */
    private fun readStatusFile(token: String): JSONObject {
        val file = JSONObject(get("$api/contents/data/status.json", token))
        val raw = file.getString("content").replace("\n", "")
        return JSONObject(String(Base64.decode(raw, Base64.DEFAULT)))
    }

    private fun formatBotData(s: JSONObject): String {
        val risk = s.optJSONObject("risk")?.optString("state") ?: "-"
        val inPos = s.optBoolean("in_position", false)
        val pos = if (inPos) "Var (${s.optDouble("position_qty")})" else "Yok"
        val err = s.optString("last_error", "")
        return "Son fiyat: ${s.optDouble("price")}\n" +
                "Açık pozisyon: $pos\n" +
                "Son sinyal: ${s.optString("signal")} (${s.optString("signal_reason")})\n" +
                "Risk durumu: $risk\n" +
                "Son hata: ${if (err.isBlank() || err == "null") "yok" else err}\n" +
                "Güncelleme: ${s.optString("updated")}"
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
