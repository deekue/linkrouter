package com.linkrouter

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.linkrouter.browsers.BrowserRegistry
import com.linkrouter.rules.LinkRouterDatabase
import com.linkrouter.rules.RedirectFormatRepository
import com.linkrouter.rules.RuleRepository
import com.linkrouter.settings.SettingsStore

/** Minimal manual DI container. */
object AppContainer {

    private lateinit var database: LinkRouterDatabase

    // Public + lateinit so unit tests can pre-inject fakes before [get] runs.
    lateinit var ruleRepository: RuleRepository
    lateinit var redirectFormatRepository: RedirectFormatRepository
    lateinit var browserRegistry: BrowserRegistry
    lateinit var settings: SettingsStore

    @Synchronized
    fun get(context: Context): AppContainer {
        val app = context.applicationContext
        if (!this::ruleRepository.isInitialized) {
            database = Room.databaseBuilder(app, LinkRouterDatabase::class.java, "linkrouter.db")
                .addMigrations(LinkRouterDatabase.MIGRATION_2_3)
                // Real migration (2 -> 3) is primary; destructive is a last-resort
                // safety net only.
                .fallbackToDestructiveMigration()
                // Seed the built-in Google format on EVERY open (fresh installs do
                // NOT run migrations, so we must not seed only in MIGRATION_2_3).
                // Idempotent: guarded by NOT EXISTS on isBuiltIn.
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        ensureBuiltInFormat(db)
                    }
                })
                .build()
            ruleRepository = RuleRepository(database)
            redirectFormatRepository = RedirectFormatRepository(database)
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
                    "(id, name, pattern, matchType, extractType, extractTarget, enabled, priority, isBuiltIn) " +
                    "SELECT -1, 'Google', 'google.com/url', 'PATH_PREFIX', 'QUERY_PARAM', 'q', 1, 1000, 1 " +
                    "WHERE NOT EXISTS (SELECT 1 FROM redirect_formats WHERE isBuiltIn = 1)"
            )
        } catch (e: Exception) {
            // Defensive: never let seeding take the app down.
        }
    }
}
