package com.telx.app.ui.common

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.telx.app.R

/**
 * v6: lightweight in-app browser (Telegram-style internal link viewer).
 * Opens http(s) links inside TelX with a toolbar: host title, refresh,
 * open-in-system, and a horizontal progress bar.
 */
class BrowserActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var progress: android.widget.ProgressBar

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        val url = intent.getStringExtra("url") ?: "https://mybackup-test.alwaysdata.net"
        val toolbar = findViewById<Toolbar>(R.id.browser_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.setNavigationOnClickListener { finish() }
        findViewById<androidx.appcompat.widget.AppCompatTextView>(R.id.browser_host).text =
            try { java.net.URI(url).host ?: url } catch (_: Exception) { url }

        web = findViewById(R.id.browser_web)
        progress = findViewById(R.id.browser_progress)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false                       // keep everything inside the internal browser
            }
            override fun onPageFinished(view: WebView, u: String) {
                findViewById<androidx.appcompat.widget.AppCompatTextView>(R.id.browser_host).text =
                    try { java.net.URI(u).host ?: u } catch (_: Exception) { u }
                progress.visibility = View.GONE
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
        findViewById<View>(R.id.browser_refresh).setOnClickListener { web.reload() }
        findViewById<View>(R.id.browser_open_externally).setOnClickListener {
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            catch (_: Exception) {}
        }
        web.loadUrl(url)
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.apply { try { loadUrl("about:blank"); onDestroy() } catch (_: Exception) {} }
        super.onDestroy()
    }
}
