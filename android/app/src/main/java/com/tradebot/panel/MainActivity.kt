package com.tradebot.panel

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Botu GitHub Actions üzerinden başlatır ve son çalıştırmanın durumunu gösterir.
 * Binance anahtarı telefonda tutulmaz; anahtarlar GitHub'ın gizli değişkenlerindedir.
 * Token sadece workflow çalıştırma yetkisine sahip olmalıdır.
 */
class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("bot", MODE_PRIVATE) }
    private val api = "https://api.github.com/repos/${BuildConfig.REPO_OWNER}/${BuildConfig.REPO_NAME}"
    private val workflow = "run-bot.yml"

    private lateinit var tokenInput: EditText
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tokenInput = findViewById(R.id.token)
        statusView = findViewById(R.id.status)
        findViewById<TextView>(R.id.mode).text = "Testnet · ${BuildConfig.REPO_OWNER}/${BuildConfig.REPO_NAME}"

        findViewById<Button>(R.id.saveToken).setOnClickListener {
            prefs.edit().putString("token", tokenInput.text.toString().trim()).apply()
            toast("Token kaydedildi")
        }
        findViewById<Button>(R.id.runBot).setOnClickListener { dispatch() }
        findViewById<Button>(R.id.refresh).setOnClickListener { refreshStatus() }

        refreshStatus()
    }

    private fun token(): String? = prefs.getString("token", null)?.takeIf { it.isNotBlank() }

    private fun dispatch() {
        val t = token() ?: return toast("Önce token'ı kaydet")
        Thread {
            try {
                val body = JSONObject().put("ref", "main").put("inputs", JSONObject().put("minutes", "20"))
                val code = request("POST", "$api/actions/workflows/$workflow/dispatches", t, body.toString())
                runOnUiThread {
                    if (code == 204) {
                        toast("Bot başlatıldı, birkaç saniye içinde görünür")
                        statusView.postDelayed({ refreshStatus() }, 5000)
                    } else {
                        toast("Başlatılamadı (HTTP $code). Token yetkisini kontrol et.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Hata: ${e.message}") }
            }
        }.start()
    }

    private fun refreshStatus() {
        val t = token()
        Thread {
            try {
                val text = if (t == null) {
                    "Token kaydedilmedi."
                } else {
                    val raw = get("$api/actions/workflows/$workflow/runs?per_page=1", t)
                    val run = JSONObject(raw).getJSONArray("workflow_runs").optJSONObject(0)
                    if (run == null) {
                        "Henüz çalıştırma yok."
                    } else {
                        val state = run.optString("status")
                        val conclusion = run.optString("conclusion", "-")
                        "Son çalıştırma: $state / $conclusion\nBaşlangıç: ${run.optString("run_started_at")}\n" +
                                "Bağlantı: ${run.optString("html_url")}"
                    }
                }
                runOnUiThread { statusView.text = text }
            } catch (e: Exception) {
                runOnUiThread { statusView.text = "Durum alınamadı: ${e.message}" }
            }
        }.start()
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
}
