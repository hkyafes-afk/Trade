package com.tradebot.panel

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Panel sayfasını gösteren basit kabuk. Tüm veri ve kontroller bot tarafındadır.
 * Bu uygulama Binance anahtarı tutmaz ve emir göndermez.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var errorView: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        errorView = findViewById(R.id.error)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) showError()
            }
        }

        if (savedInstanceState == null) {
            webView.loadUrl(BuildConfig.PANEL_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun showError() {
        errorView.visibility = View.VISIBLE
        errorView.text = "Panele bağlanılamadı (${BuildConfig.PANEL_URL}).\n" +
                "Termux'ta botu ve paneli çalıştırdığından emin ol, sonra sayfayı yenile."
        webView.visibility = View.GONE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
