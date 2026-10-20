package com.tradebot.panel

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Paneli gösterir.
 * - "Testnet çek": veriyi telefondan doğrudan Binance testnet'ten alır (sadece testnet anahtarı).
 * - "GitHub": botun GitHub'a yazdığı data/status.json dosyasını okur.
 * Bu uygulama emir göndermez.
 */
class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("bot", MODE_PRIVATE) }
    private val api = "https://api.github.com/repos/${BuildConfig.REPO_OWNER}/${BuildConfig.REPO_NAME}"
    private val workflow = "run-bot.yml"
    private val testnet = "https://testnet.binance.vision"
    private val handler = Handler(Looper.getMainLooper())
    private val autoRefresh = object : Runnable {
        override fun run() {
            if (testnetKeysOrNull() != null) fetchTestnet()
            handler.postDelayed(this, 30_000)
        }
    }

    private lateinit var web: WebView
    private lateinit var tokenInput: EditText
    private lateinit var tkeyInput: EditText
    private lateinit var tsecInput: EditText
    private var pageReady = false
    private var pending: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.dash)
        tokenInput = findViewById(R.id.token)
        tkeyInput = findViewById(R.id.tkey)
        tsecInput = findViewById(R.id.tsec)
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
        tkeyInput.setText(prefs.getString("tkey", ""))
        tsecInput.setText(prefs.getString("tsec", ""))

        findViewById<Button>(R.id.saveToken).setOnClickListener {
            prefs.edit().putString("token", tokenInput.text.toString().trim()).apply()
            toast("GitHub token kaydedildi")
        }
        findViewById<Button>(R.id.saveTestnet).setOnClickListener {
            prefs.edit()
                .putString("tkey", tkeyInput.text.toString().trim())
                .putString("tsec", tsecInput.text.toString().trim())
                .apply()
            toast("Testnet anahtarları telefonda kaydedildi")
            fetchTestnet()
        }
        findViewById<Button>(R.id.testnet).setOnClickListener { fetchTestnet() }
        findViewById<Button>(R.id.runBot).setOnClickListener { dispatch("330") }
        findViewById<Button>(R.id.testRun).setOnClickListener { dispatch("1") }
        findViewById<Button>(R.id.refresh).setOnClickListener { refreshGithub() }
        findViewById<Button>(R.id.demo).setOnClickListener {
            toast("Demo verisi: gerçek değil")
            web.evaluateJavascript("window.renderDemo && window.renderDemo();", null)
        }

        fetchTestnet()
    }

    override fun onResume() {
        super.onResume()
        handler.post(autoRefresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoRefresh)
    }

    private fun token(): String? = prefs.getString("token", null)?.takeIf { it.isNotBlank() }

    private fun testnetKeysOrNull(): Pair<String, String>? {
        val k = prefs.getString("tkey", null)?.takeIf { it.isNotBlank() } ?: return null
        val s = prefs.getString("tsec", null)?.takeIf { it.isNotBlank() } ?: return null
        return k to s
    }

    // ---------- Testnet (telefondan doğrudan) ----------

    private fun fetchTestnet() {
        val keys = testnetKeysOrNull()
        Thread {
            val status = JSONObject()
            val run = JSONObject()
            try {
                val klines = JSONArray(getUrl("$testnet/api/v3/klines?symbol=BTCUSDT&interval=1m&limit=100"))
                val closes = (0 until klines.length()).map { klines.getJSONArray(it).getString(4).toDouble() }
                val sig = signal(closes)
                status.put("price", closes.last())
                status.put("signal", sig.first)
                status.put("signal_reason", sig.second)

                if (keys != null) {
                    val query = "timestamp=${System.currentTimeMillis()}&recvWindow=5000"
                    val sign = hmac(keys.second, query)
                    val acc = JSONObject(getUrl("$testnet/api/v3/account?$query&signature=$sign", keys.first))
                    val balances = acc.getJSONArray("balances")
                    var usdt = 0.0
                    for (i in 0 until balances.length()) {
                        val b = balances.getJSONObject(i)
                        if (b.getString("asset") == "USDT") usdt = b.getString("free").toDouble()
                    }
                    status.put("balance_usdt", usdt)
                    run.put("state", "Testnet verisi (canlı)")
                } else {
                    run.put("state", "Testnet anahtarı yok, sadece fiyat")
                }
                status.put("updated", java.time.Instant.now().toString())
                status.put("config", configJson())
                run.put("detail", "Kaynak: testnet.binance.vision · emir gönderilmez")
            } catch (e: Exception) {
                status.put("last_error", "Testnet hatası: ${e.message}")
                status.put("config", configJson())
                run.put("state", "Testnet verisi alınamadı")
                run.put("detail", e.message ?: "")
            }
            val payload = JSONObject()
                .put("status", status)
                .put("run", run)
                .put("trades", JSONArray())
                .put("pnl_series", JSONArray())
            runOnUiThread { push(payload.toString()) }
        }.start()
    }

    private fun configJson(): JSONObject = JSONObject()
        .put("symbol", "BTCUSDT").put("interval", "1m")
        .put("fast_ma", 10).put("slow_ma", 30)
        .put("max_order_usdt", 20).put("max_daily_loss_usdt", 10)
        .put("max_position_usdt", 50).put("dry_run", true)

    /** Basit SMA kesişimi: sadece gösterim amaçlı, emir üretmez. */
    private fun signal(closes: List<Double>): Pair<String, String> {
        val fast = 10
        val slow = 30
        if (closes.size < slow + 1) return "HOLD" to "yetersiz veri"
        fun sma(list: List<Double>, n: Int) = list.takeLast(n).average()
        val prev = closes.dropLast(1)
        val fNow = sma(closes, fast); val sNow = sma(closes, slow)
        val fPrev = sma(prev, fast); val sPrev = sma(prev, slow)
        return when {
            fPrev <= sPrev && fNow > sNow -> "BUY" to "SMA$fast, SMA$slow'ü yukarı kesti"
            fPrev >= sPrev && fNow < sNow -> "SELL" to "SMA$fast, SMA$slow'ü aşağı kesti"
            else -> "HOLD" to "sinyal yok"
        }
    }

    private fun hmac(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun getUrl(url: String, apiKey: String? = null): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            if (apiKey != null) setRequestProperty("X-MBX-APIKEY", apiKey)
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${body.take(160)}")
        return body
    }

    // ---------- GitHub (bot çalıştırma ve kayıt) ----------

    private fun dispatch(minutes: String) {
        val t = token() ?: return toast("Önce GitHub token'ı kaydet")
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
                        refreshGithub()
                    } else {
                        toast("Başlatılamadı (HTTP $code). Token yetkisini kontrol et.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Hata: ${e.message}") }
            }
        }.start()
    }

    private fun refreshGithub() {
        val t = token() ?: return toast("Önce GitHub token'ı kaydet")
        Thread {
            val payload = JSONObject()
            try {
                val runs = JSONObject(getUrlGithub("$api/actions/workflows/$workflow/runs?per_page=5", t))
                    .getJSONArray("workflow_runs")
                val run = pickRun(runs)
                val info = JSONObject()
                if (run == null) {
                    info.put("state", "GitHub: henüz çalışma yok")
                } else {
                    info.put("state", "GitHub: " + describe(run.optString("status"), run.optString("conclusion")))
                    info.put("detail", "Başlangıç: ${run.optString("run_started_at")}")
                }
                payload.put("run", info)
            } catch (e: Exception) {
                payload.put("run", JSONObject().put("state", "GitHub durumu alınamadı").put("detail", e.message ?: ""))
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

    private fun activeRun(token: String): JSONObject? {
        val runs = JSONObject(getUrlGithub("$api/actions/workflows/$workflow/runs?per_page=5", token))
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

    private fun readStatusFile(token: String): JSONObject {
        val file = JSONObject(getUrlGithub("$api/contents/data/status.json", token))
        val raw = file.getString("content").replace("\n", "")
        return JSONObject(String(Base64.decode(raw, Base64.DEFAULT)))
    }

    private fun getUrlGithub(url: String, token: String): String {
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
