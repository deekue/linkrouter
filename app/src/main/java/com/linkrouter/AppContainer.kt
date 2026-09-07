package com.linkrouter

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.linkrouter.browsers.BrowserRegistry
import com.linkrouter.rules.ActivityWebResolver
import com.linkrouter.rules.LinkRouterDatabase
import com.linkrouter.rules.RedirectFormatRepository
import com.linkrouter.rules.RuleRepository
import com.linkrouter.rules.ShortenerHostRepository
import com.linkrouter.rules.ShortenerWebResolver
import com.linkrouter.settings.SettingsStore

/** Minimal manual DI container. */
object AppContainer {

    private lateinit var database: LinkRouterDatabase

    // Public + lateinit so unit tests can pre-inject fakes before [get] runs.
    lateinit var ruleRepository: RuleRepository
    lateinit var redirectFormatRepository: RedirectFormatRepository
    lateinit var shortenerHostRepository: ShortenerHostRepository
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
                    }
                })
                .build()
            ruleRepository = RuleRepository(database)
            redirectFormatRepository = RedirectFormatRepository(database)
            shortenerHostRepository = ShortenerHostRepository(database)
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
        val builtIns = listOf(
            -21L to "t.co",
            -22L to "bit.ly",
            -23L to "is.gd",
            -24L to "tinyurl.com",
            -25L to "ow.ly",
            -26L to "buff.ly",
        )
        try {
            builtIns.forEach { (id, host) ->
                db.execSQL(
                    "INSERT OR IGNORE INTO shortener_hosts " +
                        "(id, name, host, enabled, priority, isBuiltIn) " +
                        "SELECT $id, '$host', '$host', 0, 1000, 1 " +
                        "WHERE NOT EXISTS (SELECT 1 FROM shortener_hosts WHERE host = '$host')"
                )
            }
        } catch (e: Exception) {
            // Defensive: never let seeding take the app down.
        }
    }
}
