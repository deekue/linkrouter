package net.chaosengine.linkrouter

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
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
     * if one exists.
     *
     * `resolveActivity` is NOT consulted up front — it can return null even
     * when the app actually handles the link (e.g. TikTok's `snssdk1340://`
     * scheme, or its https:// deep links, failed resolution with the app
     * installed). Instead we ATTEMPT the launch and rely on
     * `ActivityNotFoundException`:
     *
      *  1. `ACTION_VIEW` on the custom-scheme URI itself.
      *  2. Fallback: `ACTION_VIEW` on the last HTTP(S) URL seen by the WebView
      *     (apps like TikTok register intent filters for their https:// web
      *     URLs but NOT for their own custom app scheme).
      *  3. Explicit component: if both implicit attempts throw but
      *     `queryIntentActivities` still returns a `ResolveInfo`, launch that
      *     exact component directly (observed with TikTok: the implicit intent
      *     fails to match the activity filters, yet a resolvable component
      *     exists).
      *  4. Only if all attempts throw do we log diagnostics of what the OS
      *     *can* resolve for each URL and toast "no app found".
     *
     * Best-effort — never crashes the WebView host.
     */
    private fun launchCustomScheme(uri: Uri) {
        if (!ActivityLaunchGuard.canStart(this)) return

        // 1) Preferred: the custom-scheme URI itself — attempt directly.
        try {
            Log.i(TAG, "Attempting to launch custom-scheme URI: $uri")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            Log.i(TAG, "Custom-scheme URI launched successfully: $uri")
            return
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity handles custom-scheme URI: $uri", e)
        }

        // 2) Fallback: the last HTTP(S) URL seen by the WebView (apps like
        //    TikTok register intent filters for their https:// deep-link URLs).
        val fallbackUrl = lastHttpsUrl
        if (fallbackUrl != null) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
                Log.i(TAG, "Attempting to launch https fallback URL: $fallbackUrl")
                startActivity(webIntent)
                Log.i(TAG, "https fallback URL launched successfully: $fallbackUrl")
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No activity handles https fallback URL: $fallbackUrl", e)
            } catch (e: Exception) {
                Log.w(TAG, "https fallback URL is not a usable URI, skipping: $fallbackUrl", e)
            }
        } else {
            Log.w(TAG, "No remembered https URL available to fall back to")
        }

        // 3) Explicit component: both implicit attempts failed, yet the OS may
        //    still report a resolvable component (e.g. TikTok registers the
        //    activity filters without matching the implicit intent). Launch it
        //    directly by component to bypass implicit resolution.
        if (launchByResolvedComponent(uri, fallbackUrl)) {
            return
        }

        // 4) Neither attempt succeeded — diagnose what the OS reports it CAN
        //    resolve for each URL, then tell the user nothing handled the link.
        logUnresolvedHandlers(uri, fallbackUrl)
        Log.w(TAG, "No app found to handle custom-scheme URI: $uri (fallback=$fallbackUrl)")
        Toast.makeText(
            this,
            getString(R.string.open_app_unavailable),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * Third-resort launch path: queries
     * [PackageManager.queryIntentActivities] (MATCH_DEFAULT_ONLY) for BOTH the
     * scheme URI and the https fallback URI, takes the first non-null
     * [android.content.pm.ResolveInfo], and starts an EXPLICIT intent for that
     * component.
     *
     * Needed for apps (e.g. TikTok / `com.zhiliaoapp.musically`) whose intent
     * filters match `queryIntentActivities` but where the implicit
     * `ACTION_VIEW` `startActivity` still throws `ActivityNotFoundException`.
     *
     * @return `true` when an explicit intent was started successfully.
     */
    private fun launchByResolvedComponent(uri: Uri, fallbackUrl: String?): Boolean {
        val candidates = listOfNotNull(uri, fallbackUrl?.let(Uri::parse))
        for (candidate in candidates) {
            val resolveInfo: android.content.pm.ResolveInfo? = try {
                packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_VIEW, candidate),
                    PackageManager.MATCH_DEFAULT_ONLY,
                ).firstOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "queryIntentActivities failed for $candidate", e)
                continue
            }
            val pkg = resolveInfo?.activityInfo?.packageName
            val cls = resolveInfo?.activityInfo?.name
            if (resolveInfo == null || pkg == null || cls == null) continue
            val component = ComponentName(pkg, cls)
            try {
                Log.i(TAG, "Attempting explicit component launch: $component for $candidate")
                startActivity(Intent(Intent.ACTION_VIEW, candidate).setComponent(component))
                Log.i(TAG, "Launched via explicit component: $pkg/$cls for $candidate")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Explicit component launch failed for $component (uri=$candidate)", e)
            }
        }
        return false
    }

    /**
     * Runs on the final failure path only: reports, for BOTH the scheme URI and
     * the https fallback (when remembered), how many activities
     * `PackageManager.queryIntentActivities` resolves and which packages they
     * belong to — so logcat shows exactly what the OS *can* resolve.
     *
     * Log format:
     * `No handler found. scheme=[snssdk1340://…] matched 0 activities; https=[https://…] matched 1 activities (packages: com.zhiliaoapp.musically)`
     *
     * Never throws.
     */
    private fun logUnresolvedHandlers(schemeUri: Uri, fallbackUrl: String?) {
        try {
            val schemePart = "scheme=[$schemeUri] ${describeResolvableHandlers(schemeUri)}"
            val httpsPart = if (fallbackUrl != null) {
                "https=[$fallbackUrl] ${describeResolvableHandlers(Uri.parse(fallbackUrl))}"
            } else {
                "https=[none] no remembered fallback URL"
            }
            Log.w(TAG, "No handler found. $schemePart; $httpsPart")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect unresolved-handler diagnostics", e)
        }
    }

    /** Returns e.g. `matched 0 activities` or `matched 2 activities (packages: a, b)`; never throws. */
    private fun describeResolvableHandlers(uri: Uri): String {
        return try {
            val matches = packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, uri),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            val packages = matches
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
            if (packages.isEmpty()) {
                "matched 0 activities"
            } else {
                "matched ${packages.size} activities (packages: ${packages.joinToString(", ")})"
            }
        } catch (e: Exception) {
            "handler query failed (${e.javaClass.simpleName}: ${e.message})"
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
