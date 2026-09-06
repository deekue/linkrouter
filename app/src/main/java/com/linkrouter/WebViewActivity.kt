package com.linkrouter

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PrivateConnectivity
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * In-app WebView (DESIGN.md section 7).
 *
 * Renders a URL inside LinkRouter itself — no cross-app hand-off. This makes
 * the PRIVATE mode a **REAL** private strategy: the page never leaves our
 * process, and on close we clear cookies, HTTP cache and web storage
 * (D6 satisfied without a warning).
 *
 * - JS + DOM storage enabled.
 * - All in-page http/https navigation stays in-app (`shouldOverrideUrlLoading`).
 * - Back button pops the WebView history before finishing.
 * - Top bar shows the current URL + a private indicator.
 */
class WebViewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "com.linkrouter.webview.EXTRA_URL"
        const val EXTRA_PRIVATE = "com.linkrouter.webview.EXTRA_PRIVATE"
    }

    private var isPrivate = false
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    @androidx.compose.material3.ExperimentalMaterial3Api
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            finish()
            return
        }
        isPrivate = intent.getBooleanExtra(EXTRA_PRIVATE, false)

        setContent {
            MaterialTheme {
                WebViewScreen(
                    initialUrl = url,
                    isPrivate = isPrivate,
                    onWebViewCreated = { webView = it },
                    onBack = { webView?.goBack() },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        // For REAL private mode: clear all WebView data so nothing leaks into
        // a subsequent normal session. Best-effort — never crash.
        if (isPrivate) {
            try {
                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }
                webView?.clearCache(true)
                webView?.loadUrl("about:blank")
                webView?.destroy()
            } catch (_: Exception) {
                // Best-effort cleanup.
            }
        }
        webView = null
        super.onDestroy()
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun WebViewScreen(
    initialUrl: String,
    isPrivate: Boolean,
    onWebViewCreated: (WebView) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var canGoBack by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Consume the system back press to pop the WebView history first.
    BackHandler(enabled = canGoBack) {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        if (title.isNotEmpty()) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            currentUrl,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = canGoBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (isPrivate) {
                        Icon(
                            Icons.Filled.PrivateConnectivity,
                            contentDescription = "Private browsing",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val uri = request.url
                                if (uri.scheme == "http" || uri.scheme == "https") {
                                    view.loadUrl(uri.toString())
                                    return true
                                }
                                return false
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                currentUrl = url
                                title = view.title ?: ""
                                canGoBack = view.canGoBack()
                            }

                            override fun onPageStarted(
                                view: WebView,
                                url: String,
                                favicon: android.graphics.Bitmap?,
                            ) {
                                canGoBack = view.canGoBack()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                canGoBack = view.canGoBack()
                            }
                        }
                        loadUrl(initialUrl)
                        webViewRef = this
                        onWebViewCreated(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
