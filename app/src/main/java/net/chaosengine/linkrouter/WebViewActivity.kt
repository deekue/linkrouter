package net.chaosengine.linkrouter

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import androidx.compose.material3.SnackbarResult
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
 * - Non-http(s) custom-scheme redirects (e.g. `snssdk1233://`) are CONSUMED
 *   (so they don't wipe the visible page) and surfaced as a Snackbar with an
 *   "Open App" action that launches the scheme via `ACTION_VIEW`, falling back
 *   to the last loaded HTTP(S) URL when the scheme itself is not registered
 *   (apps like TikTok resolve their https:// deep links but not `app://`).
 * - Back button pops the WebView history before finishing.
 * - Top bar shows the current URL + a private indicator.
 * - "Copy current URL" action copies the live URL to the clipboard.
 */
private const val TAG = "WebViewActivity"

class WebViewActivity : ComponentActivity() {

    private var isPrivate = false
    private var webView: WebView? = null

    // Last HTTP(S) URL the WebView loaded (seeded from the initial URL and
    // updated in `shouldOverrideUrlLoading`). Used as a fallback target for the
    // "Open App" action: many apps (e.g. TikTok) register intent filters for
    // their https:// web URLs but NOT for their custom app:// scheme, so
    // `resolveActivity` can return null for the scheme.
    private var lastHttpsUrl: String? = null

    companion object {
        const val EXTRA_URL = "net.chaosengine.linkrouter.webview.EXTRA_URL"
        const val EXTRA_PRIVATE = "net.chaosengine.linkrouter.webview.EXTRA_PRIVATE"
    }

    /**
     * Launch a custom-scheme URI (e.g. `snssdk1233://...`) in the handling app,
     * if one exists. Builds an `ACTION_VIEW` intent, verifies it resolves via
     * `resolveActivity` before starting.
     *
     * Many apps (TikTok included) register intent filters for their `https://`
     * web URLs but NOT for their own custom app scheme, so the scheme intent can
     * fail to resolve even with the app installed. When that happens we fall
     * back to the last HTTP(S) URL seen by the WebView, which those apps DO
     * handle. Only if neither resolves do we toast "no app found".
     * Best-effort — never crashes the WebView host.
     */
    private fun launchCustomScheme(uri: Uri) {
        if (!ActivityLaunchGuard.canStart(this)) return

        // 1) Preferred: the custom-scheme URI itself.
        val schemeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (resolves(schemeIntent)) {
            startActivitySafely(schemeIntent, uri.toString())
            return
        }

        // 2) Fallback: the last HTTP(S) URL seen by the WebView (apps like
        //    TikTok register intent filters for their https:// deep-link URLs).
        val fallbackUrl = lastHttpsUrl
        if (fallbackUrl != null) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (resolves(webIntent)) {
                    Log.i(TAG, "Custom-scheme URI unresolved; launching https fallback URL: $fallbackUrl")
                    startActivitySafely(webIntent, fallbackUrl)
                    return
                }
                Log.w(TAG, "https fallback URL did not resolve either: $fallbackUrl")
            } catch (e: Exception) {
                Log.w(TAG, "https fallback URL is not a valid URI, skipping: $fallbackUrl", e)
            }
        } else {
            Log.w(TAG, "No remembered https URL available to fall back to")
        }

        // 3) Neither resolved — nothing installed handles this link.
        Log.w(TAG, "No app found to handle custom-scheme URI: $uri (fallback=$fallbackUrl)")
        Toast.makeText(
            this,
            getString(R.string.open_app_unavailable),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** Resolves [intent] via `resolveActivity`, tolerating any resolution failure. */
    private fun resolves(intent: Intent): Boolean {
        return try {
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        } catch (e: Exception) {
            false
        }
    }

    private fun startActivitySafely(intent: Intent, target: String) {
        try {
            Log.i(TAG, "Launching in app via: $target")
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch: $target", e)
            if (ActivityLaunchGuard.canStart(this)) {
                Toast.makeText(
                    this,
                    getString(R.string.open_app_unavailable),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

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
        // Seed the https fallback with the initial URL when it is a web URL.
        lastHttpsUrl = url.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        }

        setContent {
            MaterialTheme {
                WebViewScreen(
                    initialUrl = url,
                    isPrivate = isPrivate,
                    onWebViewCreated = { webView = it },
                    onUrlLoaded = { httpsUrl -> lastHttpsUrl = httpsUrl },
                    onBack = { webView?.goBack() },
                    onCopyUrl = { copyCurrentUrlToClipboard() },
                    onOpenApp = { uri -> launchCustomScheme(uri) },
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
        clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
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
    onUrlLoaded: (String) -> Unit,
    onBack: () -> Unit,
    onCopyUrl: () -> Boolean,
    onOpenApp: (Uri) -> Unit,
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

    /**
     * Show a Material3 Snackbar telling the user the site is trying to open an
     * app (with its scheme). It exposes TWO controls:
     *  - an "Open App" action — launches the pending custom-scheme URI via the
     *    `onOpenApp`/`launchCustomScheme` path only when the user taps it; and
     *  - a visible dismiss "X" — dismisses the snackbar without launching anything.
     *
     * In Material3 1.3.0 (BOM 2024.09.03) the action button is driven by
     * `actionLabel != null` and the dismiss button by `withDismissAction == true`
     * — two independent composables, not a single shared right-side slot. Passing
     * `actionLabel` alone therefore shows the action but NO close button; setting
     * `withDismissAction = true` is what actually produces the visible dismiss "X".
     * The existing `actionLabel != null` call already implies
     * `duration = Indefinite` (no auto-dismiss). The visible WebView page is left
     * untouched (its navigation was already consumed in `shouldOverrideUrlLoading`).
     */
    fun showOpenAppPrompt(uri: Uri) {
        val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: "this link"
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = context.getString(R.string.open_app_prompt, scheme),
                actionLabel = context.getString(R.string.open_app),
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                onOpenApp(uri)
            }
        }
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
                                val scheme = uri.scheme?.lowercase()
                                // In-page web navigation stays in-app.
                                if (scheme == "http" || scheme == "https") {
                                    view.loadUrl(uri.toString())
                                    onUrlLoaded(uri.toString())
                                    return true
                                }
                                // Schemes the WebView manages internally
                                // (javascript: / about: / data: / blob:) must NOT be
                                // offered as "open an app"; let the WebView handle them.
                                if (scheme == "javascript" || scheme == "about" ||
                                    scheme == "data" || scheme == "blob"
                                ) {
                                    return false
                                }
                                // Any other (custom) scheme — e.g. a site trying to
                                // deep-link into an installed app (snssdk1233://).
                                // CONSUME the navigation (return true) so the
                                // WebView doesn't throw ERR_UNKNOWN_URL_SCHEME and
                                // wipe the visible page, then prompt the user.
                                Log.i(
                                    TAG,
                                    "Intercepted non-web scheme redirect: $uri (scheme=${uri.scheme}); blocking navigation and prompting Open App",
                                )
                                showOpenAppPrompt(uri)
                                return true
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
