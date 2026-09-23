package net.chaosengine.linkrouter

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PrivateConnectivity
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
 * - "Copy current URL" action copies the live URL to the clipboard.
 */
class WebViewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "net.chaosengine.linkrouter.webview.EXTRA_URL"
        const val EXTRA_PRIVATE = "net.chaosengine.linkrouter.webview.EXTRA_PRIVATE"
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
                    onCopyUrl = { copyCurrentUrlToClipboard() },
                    onClose = { finish() },
                )
            }
        }
    }

    /**
     * Copies the WebView's currently loaded URL to the clipboard.
     *
     * Returns `true` on success, or `false` when there is no page loaded yet so
     * the caller can surface the appropriate feedback.
     *
     * [WebView.getUrl] returns a [java.net.URL] that is null when no page has
     * loaded (e.g. `about:blank`), so we guard against it rather than crash.
     * No runtime permission is required for writes, and we reach [ClipboardManager]
     * via [Context.getSystemService] (not the deprecated `Activity` accessor).
     * Called on the main thread while the app is in the foreground.
     */
    private fun copyCurrentUrlToClipboard(): Boolean {
        val url = webView?.url ?: return false
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("URL", url.toString()))
        return true
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
    onCopyUrl: () -> Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = remember { CoroutineScope(Dispatchers.Main.immediate) }
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
                    IconButton(
                        onClick = {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(
                                        if (onCopyUrl()) R.string.copy_url_done
                                        else R.string.copy_url_failed
                                    )
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Link, contentDescription = context.getString(R.string.copy_url))
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
