package net.chaosengine.linkrouter

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import net.chaosengine.linkrouter.browsers.BrowserRegistry
import net.chaosengine.linkrouter.rules.ActivityWebResolver
import net.chaosengine.linkrouter.rules.builtInQueryParamFilters
import net.chaosengine.linkrouter.rules.builtInRedirectFormats
import net.chaosengine.linkrouter.rules.builtInShortenerHosts
import net.chaosengine.linkrouter.rules.HostRewriteRepository
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
    lateinit var hostRewriteRepository: HostRewriteRepository
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
                    LinkRouterDatabase.MIGRATION_7_8,
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
                        // Host-rewrite seeds (P0): fresh installs do NOT run
                        // migrations, so seed here too (idempotent, defensive).
                        LinkRouterDatabase.seedBuiltInHostRewrites(db)
                    }
                })
                .build()
            ruleRepository = RuleRepository(database)
            redirectFormatRepository = RedirectFormatRepository(database)
            shortenerHostRepository = ShortenerHostRepository(database)
            queryParamFilterRepository = QueryParamFilterRepository(database)
            hostRewriteRepository = HostRewriteRepository(database)
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
            builtInRedirectFormats.forEach { rf ->
                val enabledInt = if (rf.enabled) 1 else 0
                val orInt = if (rf.openRealDestination) 1 else 0
                db.execSQL(
                    "INSERT OR IGNORE INTO redirect_formats " +
                        "(id, name, pattern, matchType, extractType, extractTarget, enabled, priority, isBuiltIn, openRealDestination) " +
                        "SELECT ${rf.id}, '${rf.name}', '${rf.pattern}', '${rf.matchType.name}', '${rf.extractType.name}', '${rf.extractTarget}', $enabledInt, ${rf.priority}, 1, $orInt " +
                        "WHERE NOT EXISTS (SELECT 1 FROM redirect_formats WHERE id = ${rf.id})"
                )
            }
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
        try {
            builtInShortenerHosts.forEach { sh ->
                val enabledInt = if (sh.enabled) 1 else 0
                val prefixLiteral = sh.pathPrefix?.let { "'$it'" } ?: "NULL"
                db.execSQL(
                    "INSERT OR IGNORE INTO shortener_hosts " +
                        "(id, name, host, pathPrefix, enabled, priority, isBuiltIn) " +
                        "SELECT ${sh.id}, '${sh.name}', '${sh.host}', $prefixLiteral, $enabledInt, ${sh.priority}, 1 " +
                        "WHERE NOT EXISTS (SELECT 1 FROM shortener_hosts WHERE host = '${sh.host}')"
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
        try {
            builtInQueryParamFilters.forEach { qpf ->
                val enabledInt = if (qpf.enabled) 1 else 0
                val hostLiteral = qpf.host?.let { "'$it'" } ?: "NULL"
                // Natural-key clause shared by the NOT EXISTS guard and the
                // self-heal UPDATE below.
                val keyClause = if (qpf.host == null) {
                    "param = '${qpf.param}' AND host IS NULL"
                } else {
                    "param = '${qpf.param}' AND host = '${qpf.host}'"
                }
                val existsGuard = "WHERE NOT EXISTS (SELECT 1 FROM query_param_filters WHERE $keyClause)"
                db.execSQL(
                    "INSERT OR IGNORE INTO query_param_filters " +
                        "(id, name, host, param, enabled, priority, isBuiltIn) " +
                        "SELECT ${qpf.id}, '${qpf.name}', $hostLiteral, '${qpf.param}', $enabledInt, ${qpf.priority}, 1 " +
                        "$existsGuard"
                )
                // Self-heal: a pre-existing user row under the canonical key
                // blocked the INSERT above (a user row under a builtin's natural
                // key suppresses the seed, and such rows would otherwise export
                // as isBuiltIn=false). Flip the highest-priority such row to
                // built-in; enabled/priority/name/host/param are left untouched.
                db.execSQL(
                    "UPDATE query_param_filters SET isBuiltIn = 1 " +
                        "WHERE id = (SELECT id FROM query_param_filters WHERE $keyClause AND isBuiltIn = 0 " +
                        "ORDER BY priority DESC LIMIT 1)"
                )
            }
        } catch (e: Exception) {
            // Defensive: never let seeding take the app down.
        }
    }
}
