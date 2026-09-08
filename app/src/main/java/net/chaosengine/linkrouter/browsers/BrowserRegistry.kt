package net.chaosengine.linkrouter.browsers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Browser discovery via PackageManager (DESIGN.md section 8). Uses the
 * manifest `<queries>` block — no QUERY_ALL_PACKAGES.
 *
 * - Discovers installed browsers handling ACTION_VIEW http/https.
 * - Resolves each package's launchable activity.
 * - Caches icons in memory; refresh on PACKAGE_ADDED/REMOVED/CHANGED.
 * - Work-profile note: only the active profile's browsers are visible.
 */
open class BrowserRegistry(context: Context) {

    private val appContext = context.applicationContext

    private val _browsers = MutableStateFlow<List<BrowserInfo>>(emptyList())
    /** Installed browsers, top-level (active profile only). */
    val browsers: StateFlow<List<BrowserInfo>> = _browsers.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A browser was installed / uninstalled / updated → re-discover.
            refresh()
        }
    }
    private var receiverRegistered = false

    init {
        registerPackageReceiver()
        refresh()
    }

    /**
     * Listen for install/uninstall/change of any package (DESIGN.md section 8).
     * These system broadcasts are exempt from the context-registered implicit
     * broadcast restrictions, so runtime registration on the app context is
     * valid on all supported APIs (26+).
     */
    private fun registerPackageReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    packageReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                appContext.registerReceiver(packageReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
            // Registration is best-effort; discovery still works on init + manual refresh.
        }
    }

    /** Unregister the package-change receiver (call when the registry is no longer needed). */
    fun shutdown() {
        if (!receiverRegistered) return
        try {
            appContext.unregisterReceiver(packageReceiver)
        } catch (_: Exception) {
            // already unregistered
        }
        receiverRegistered = false
        scope.cancel()
    }

    open fun refresh() {
        scope.launch {
            val list = withContext(Dispatchers.IO) { discover() }
            _browsers.value = list
        }
    }

    /** Test-only: replace the discovered-browser list directly (no PackageManager). */
    fun setBrowsersForTest(list: List<BrowserInfo>) {
        _browsers.value = list
    }

    /**
     * Whether [packageName] is an installed browser, returning its [BrowserInfo].
     *
     * Cold-start-safe: delegates to [resolveTarget] rather than reading only the
     * in-memory [browsers] cache. On a fresh process (the common case when the
     * dispatcher is launched directly for a link tap) the async [refresh] sweep
     * has not populated the cache yet, so a cache-only read returns null and a
     * perfectly valid fallback browser (or rule target) is silently dropped
     * ("not installed" + chooser). [resolveTarget] fast-paths the cache and, on
     * a miss, does a real PackageManager check + builds a minimal entry on demand.
     */
    open fun installed(packageName: String): BrowserInfo? =
        resolveTarget(packageName)

    fun isInstalled(packageName: String): Boolean =
        try {
            appContext.packageManager.getPackageInfo(packageName, 0) != null
        } catch (e: Exception) {
            false
        }

    /**
     * Synchronous target resolution for the dispatch path. On a fresh process
     * (the common case when [net.chaosengine.linkrouter.DispatcherActivity] is launched
     * directly for a link tap) the async [browsers] cache is still empty, so
     * [installed] returns null and the matched rule is dropped. This method
     * fast-paths the cache, otherwise does a real PackageManager check and
     * builds a minimal entry on demand — keeping the rule's target resolvable
     * without waiting for the background [refresh] sweep to complete.
     */
    open fun resolveTarget(packageName: String): BrowserInfo? {
        _browsers.value.firstOrNull { it.packageName == packageName }?.let { return it }
        if (!isInstalled(packageName)) return null
        val info = discoverOne(packageName) ?: return null
        _browsers.value = _browsers.value + info
        return info
    }

    /** Build a minimal [BrowserInfo] for a single installed package (no full sweep). */
    private fun discoverOne(pkg: String): BrowserInfo? {
        val pm = appContext.packageManager
        val label = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        val activity = launchableActivity(pkg)
        val icon = try {
            pm.getApplicationIcon(pkg)
        } catch (e: Exception) {
            null
        }
        return BrowserInfo(
            packageName = pkg,
            label = label,
            activity = activity,
            icon = icon,
            isPrivateCapable = StrategyTable.isPrivateCapable(pkg),
        )
    }

    private fun discover(): List<BrowserInfo> {
        val pm = appContext.packageManager
        val probe = Uri.parse("https://example.com/")
        val view = Intent(Intent.ACTION_VIEW, probe)
        val self = appContext.packageName

        // Unpinned query first (fast path; also the canonical source of ResolveInfo).
        val resolved = try {
            pm.queryIntentActivities(view, 0).filter {
                val pkg = it.activityInfo?.packageName ?: return@filter false
                pkg != self // never route to ourselves (loop guard)
            }
        } catch (e: Exception) {
            emptyList()
        }

        val byPackage = LinkedHashMap<String, android.content.pm.ResolveInfo>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            byPackage.putIfAbsent(pkg, ri)
        }

        // Android 11+ package-visibility quirk (reproduced on Android 17): an
        // UNPINNED queryIntentActivities for https ACTION_VIEW returns only the
        // default-browser role holder (e.g. Chrome), even when other installed
        // browsers declare https VIEW filters and are visible to our UID.
        // A PINNED per-package resolveActivity does find them. So as a
        // fallback, sweep the installed packages we can see and pin-query each
        // for an https ACTION_VIEW handler, adding any the unpinned query missed.
        val installed = try { pm.getInstalledPackages(0).map { it.packageName } } catch (e: Exception) { emptyList() }
        for (pkg in installed) {
            if (pkg == self) continue
            if (byPackage.containsKey(pkg)) continue
            val pinned = try {
                pm.queryIntentActivities(view.setPackage(pkg), 0)
            } catch (e: Exception) {
                emptyList()
            }
            val ri = pinned.firstOrNull { it.activityInfo?.packageName == pkg } ?: continue
            byPackage[pkg] = ri
        }

        return byPackage.map { (pkg, ri) ->
            val label = ri.activityInfo?.loadLabel(pm)?.toString() ?: pkg
            val activity = launchableActivity(pkg)
            val icon = try {
                ri.activityInfo?.loadIcon(pm) ?: pm.getApplicationIcon(pkg)
            } catch (e: Exception) {
                null
            }
            BrowserInfo(
                packageName = pkg,
                label = label,
                activity = activity,
                icon = icon,
                isPrivateCapable = StrategyTable.isPrivateCapable(pkg),
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** Resolve the package's launcher activity (MAIN/LAUNCHER). */
    private fun launchableActivity(pkg: String): String? {
        val pm = appContext.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(pkg)
        return try {
            val info = pm.resolveActivity(launchIntent, 0)
            val pkg = info?.activityInfo?.packageName
            val cls = info?.activityInfo?.name
            if (pkg != null && cls != null) android.content.ComponentName(pkg, cls).flattenToString() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Build the ACTION_VIEW intent for a rule target. Returns null if the
     * package cannot handle the URL (uninstalled / no handler).
     */
    open fun targetIntent(uri: Uri, packageName: String, activity: String?): Intent? {
        val view = Intent(Intent.ACTION_VIEW, uri).setPackage(packageName)
        val pm = appContext.packageManager
        return if (pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY) != null) view else null
    }
}
