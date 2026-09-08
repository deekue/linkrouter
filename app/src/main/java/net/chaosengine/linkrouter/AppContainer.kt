package net.chaosengine.linkrouter

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import net.chaosengine.linkrouter.browsers.BrowserRegistry
import net.chaosengine.linkrouter.rules.ActivityWebResolver
import net.chaosengine.linkrouter.rules.LinkRouterDatabase
import net.chaosengine.linkrouter.rules.RedirectFormatRepository
import net.chaosengine.linkrouter.rules.QueryParamFilterRepository
import net.chaosengine.linkrouter.rules.RuleRepository
import net.chaosengine.linkrouter.rules.ShortenerHostRepository
import net.chaosengine.linkrouter.rules.ShortenerWebResolver
import net.chaosengine.linkrouter.settings.SettingsStore

/** Minimal manual DI container. */
object AppContainer {

    private lateinit var database: LinkRouterDatabase

    // Public + lateinit so unit tests can pre-inject fakes before [get] runs.
    lateinit var ruleRepository: RuleRepository
    lateinit var redirectFormatRepository: RedirectFormatRepository
    lateinit var shortenerHostRepository: ShortenerHostRepository
    lateinit var queryParamFilterRepository: QueryParamFilterRepository
    lateinit var browserRegistry: BrowserRegistry
    lateinit var settings: SettingsStore

    // Swappable fetcher (Task C test seam): production default is the real
    // HttpURLConnection-based fetcher; tests replace this with a no-redirect fake
    // so the dispatcher never touches the network. Only consulted when a
    // shortener host is enabled (D8).
    var shortenerFetcher: ShortenerResolver.Fetcher = ShortenerResolver.RealFetcher()

    // M7 WebView fallback (test seam): production default is the real
    // ActivityWebResolver (launches the ephemeral ResolutionWebViewActivity);
    // tests replace this with a fake whose resolve() returns synchronously.
    // Only consulted when the fast path returned Interstitial (D9/D6).
    var shortenerWebResolver: ShortenerWebResolver = ActivityWebResolver()

    @Synchronized
    fun get(context: Context): AppContainer {
        val app = context.applicationContext
        if (!this::ruleRepository.isInitialized) {
            database = Room.databaseBuilder(app, LinkRouterDatabase::class.java, "linkrouter.db")
                .addMigrations(
                    LinkRouterDatabase.MIGRATION_2_3,
                    LinkRouterDatabase.MIGRATION_3_4,
                    LinkRouterDatabase.MIGRATION_4_5,
                    LinkRouterDatabase.MIGRATION_5_6,
                    LinkRouterDatabase.MIGRATION_6_7,
                )
                // Real migration (2 -> 3) is primary; destructive is a last-resort
                // safety net only.
                .fallbackToDestructiveMigration()
                // Seed the built-in Google format on EVERY open (fresh installs do
                // NOT run migrations, so we must not seed only in MIGRATION_2_3).
                // Idempotent: guarded by NOT EXISTS on isBuiltIn.
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        ensureBuiltInFormat(db)
                        ensureBuiltInShortenerHosts(db)
                        ensureBuiltInQueryParamFilters(db)
                    }
                })
                .build()
            ruleRepository = RuleRepository(database)
            redirectFormatRepository = RedirectFormatRepository(database)
            shortenerHostRepository = ShortenerHostRepository(database)
            queryParamFilterRepository = QueryParamFilterRepository(database)
            browserRegistry = BrowserRegistry(app)
            settings = SettingsStore(app)
        }
        return this
    }

    /**
     * Idempotently guarantee a built-in Google redirect format exists. Runs on
     * every open so fresh installs and migrated installs both end up with it.
     * A schema hiccup must not crash app startup, so failures are swallowed.
     */
    private fun ensureBuiltInFormat(db: SupportSQLiteDatabase) {
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO redirect_formats " +
                    "(id, name, pattern, matchType, extractType, extractTarget, enabled, priority, isBuiltIn, openRealDestination) " +
                    "SELECT -1, 'Google', 'google.com/url', 'PATH_PREFIX', 'QUERY_PARAM', 'q', 1, 1000, 1, 0 " +
                    "WHERE NOT EXISTS (SELECT 1 FROM redirect_formats WHERE isBuiltIn = 1)"
            )
        } catch (e: Exception) {
            // Defensive: never let seeding take the app down.
        }
    }

    /**
     * Idempotently guarantee the built-in shortener hosts exist. Runs on every
     * open so fresh installs and migrated installs both end up with them. All
     * built-ins start DISABLED (D8: zero network unless the user opts in).
     * A schema hiccup must not crash app startup, so failures are swallowed.
     */
    private fun ensureBuiltInShortenerHosts(db: SupportSQLiteDatabase) {
        // Triple of (host, name, pathPrefix) — pathPrefix is null for host-only
        // rows (existing built-ins are unchanged: name == host).
        val builtIns = listOf(
            -21L to Triple("t.co", "t.co", null),
            -22L to Triple("bit.ly", "bit.ly", null),
            -23L to Triple("is.gd", "is.gd", null),
            -24L to Triple("tinyurl.com", "tinyurl.com", null),
            -25L to Triple("ow.ly", "ow.ly", null),
            -26L to Triple("buff.ly", "buff.ly", null),
            -27L to Triple("www.tiktok.com", "TikTok short links", "/t/"),
            -28L to Triple("www.facebook.com", "Facebook share links", "/share/r/"),
        )
        try {
            builtIns.forEach { (id, entry) ->
                val (host, name, pathPrefix) = entry
                val prefixLiteral = pathPrefix?.let { "'$it'" } ?: "NULL"
                db.execSQL(
                    "INSERT OR IGNORE INTO shortener_hosts " +
                        "(id, name, host, pathPrefix, enabled, priority, isBuiltIn) " +
                        "SELECT $id, '$name', '$host', $prefixLiteral, 0, 1000, 1 " +
                        "WHERE NOT EXISTS (SELECT 1 FROM shortener_hosts WHERE host = '$host')"
                )
            }
        } catch (e: Exception) {
            // Defensive: never let seeding take the app down.
        }
    }

    /**
     * Idempotently guarantee the built-in query-param filters exist. Runs on
     * every open so fresh installs and migrated installs both end up with them.
     * All built-ins start ENABLED (M9: param stripping is local and zero
     * network risk, unlike the shortener hosts). A schema hiccup must not crash
     * app startup, so failures are swallowed.
     */
    private fun ensureBuiltInQueryParamFilters(db: SupportSQLiteDatabase) {
        // id to (host, param, name) — host is null for global rows, else a
        // lowercase scope host.
        val builtIns = listOf(
            -31L to Triple(null, "utm_source", "utm_source (global)"),
            -32L to Triple(null, "utm_medium", "utm_medium (global)"),
            -33L to Triple(null, "utm_campaign", "utm_campaign (global)"),
            -34L to Triple(null, "utm_term", "utm_term (global)"),
            -35L to Triple(null, "utm_content", "utm_content (global)"),
            -36L to Triple(null, "gclid", "gclid (global)"),
            -37L to Triple(null, "gclsrc", "gclsrc (global)"),
            -38L to Triple(null, "msclkid", "msclkid (global)"),
            -39L to Triple(null, "fbclid", "fbclid (global)"),
            -40L to Triple(null, "fbid", "fbid (global)"),
            -41L to Triple(null, "sharer_id", "sharer_id (global)"),
            -42L to Triple(null, "mc_eid", "mc_eid (global)"),
            -43L to Triple(null, "mc_cid", "mc_cid (global)"),
            -44L to Triple("tiktok.com", "_t", "_t (TikTok)"),
            -45L to Triple("instagram.com", "igsi", "igsi (Instagram)"),
            -46L to Triple("instagram.com", "igshid", "igshid (Instagram)"),
            -47L to Triple("youtube.com", "si", "si (YouTube)"),
            -48L to Triple("youtube.com", "feature", "feature (YouTube)"),
            -49L to Triple("facebook.com", "original_uri", "original_uri (Facebook)"),
        )
        try {
            builtIns.forEach { (id, entry) ->
                val (host, param, name) = entry
                val hostLiteral = host?.let { "'$it'" } ?: "NULL"
                val existsGuard = if (host == null) {
                    "WHERE NOT EXISTS (SELECT 1 FROM query_param_filters WHERE param = '$param' AND host IS NULL)"
                } else {
                    "WHERE NOT EXISTS (SELECT 1 FROM query_param_filters WHERE param = '$param' AND host = '$host')"
                }
                db.execSQL(
                    "INSERT OR IGNORE INTO query_param_filters " +
                        "(id, name, host, param, enabled, priority, isBuiltIn) " +
                        "SELECT $id, '$name', $hostLiteral, '$param', 1, 1000, 1 " +
                        "$existsGuard"
                )
            }
        } catch (e: Exception) {
            // Defensive: never let seeding take the app down.
        }
    }
}
