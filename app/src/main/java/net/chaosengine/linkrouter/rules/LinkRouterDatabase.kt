package net.chaosengine.linkrouter.rules

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RuleEntity::class, RedirectFormatEntity::class, ShortenerHostEntity::class, QueryParamFilterEntity::class, HostRewriteEntity::class],
    version = 8,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class LinkRouterDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun redirectFormatDao(): RedirectFormatDao
    abstract fun shortenerHostDao(): ShortenerHostDao
    abstract fun queryParamFilterDao(): QueryParamFilterDao
    abstract fun hostRewriteDao(): HostRewriteDao

    companion object {
        /**
         * v2 -> v3: add the `redirect_formats` table. Column names/types must
         * match Room's annotation-generated schema EXACTLY (Kotlin property
         * names as-is; boolean -> INTEGER; enums -> TEXT via the `.name` form
         * in [Converters]).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `redirect_formats` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`pattern` TEXT NOT NULL, " +
                        "`matchType` TEXT NOT NULL, " +
                        "`extractType` TEXT NOT NULL, " +
                        "`extractTarget` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`priority` INTEGER NOT NULL, " +
                        "`isBuiltIn` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_redirect_formats_enabled_priority " +
                        "ON redirect_formats (enabled, priority)"
                )
            }
        }

        /**
         * v3 -> v4: add the `openRealDestination` flag to `redirect_formats`.
         * Column name must match the Kotlin property name Room expects.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE redirect_formats " +
                        "ADD COLUMN openRealDestination INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v4 -> v5: add the `shortener_hosts` table. Column names/types must
         * match Room's annotation-generated schema EXACTLY (Kotlin property
         * names as-is; boolean -> INTEGER).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `shortener_hosts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`host` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`priority` INTEGER NOT NULL, " +
                        "`isBuiltIn` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_shortener_hosts_enabled_priority " +
                        "ON shortener_hosts (enabled, priority)"
                )
            }
        }

        /**
         * v5 -> v6: add the optional `pathPrefix` column to `shortener_hosts`.
         * Nullable with no default: existing rows read as NULL = host-only.
         * Column name must match the Kotlin property name Room expects.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE shortener_hosts ADD COLUMN pathPrefix TEXT"
                )
            }
        }

        /**
         * v6 -> v7: add the `query_param_filters` table. Column names/types must
         * match Room's annotation-generated schema EXACTLY (Kotlin property
         * names as-is; boolean -> INTEGER).
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `query_param_filters` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`host` TEXT, " +
                        "`param` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`priority` INTEGER NOT NULL, " +
                        "`isBuiltIn` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_query_param_filters_enabled_priority " +
                        "ON query_param_filters (enabled, priority)"
                )
            }
        }

        /**
         * v7 -> v8: add the `host_rewrites` table. Column names/types must
         * match the annotation-generated schema EXACTLY (Kotlin property names
         * as-is; boolean -> INTEGER; enums -> TEXT via the `.name` form in
         * [Converters]; `priority` UNIQUE like the routing rules). Then seed the
         * three canonical example rules, all BUILT-IN and DISABLED by default
         * (Q5) so a fresh or migrated install always has them visible & editable.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `host_rewrites` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`matchHost` TEXT NOT NULL, " +
                        "`matchType` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`targetHost` TEXT NOT NULL, " +
                        "`preserveHostInPath` INTEGER NOT NULL DEFAULT 0, " +
                        "`enabled` INTEGER NOT NULL DEFAULT 1, " +
                        "`priority` INTEGER NOT NULL, " +
                        "`isBuiltIn` INTEGER NOT NULL DEFAULT 0, " +
                        "UNIQUE (priority)" +
                        ")"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_host_rewrites_enabled_priority " +
                        "ON host_rewrites (enabled, priority)"
                )
                seedBuiltInHostRewrites(db)
            }
        }

        /**
         * Idempotently seed the three canonical example rules (x.com, tiktok,
         * nytimes), all `isBuiltIn = 1`, `enabled = 0`. Runs from
         * [MIGRATION_7_8] and is also exposed to [androidx.room.RoomDatabase.Callback]
         * `onOpen` (fresh installs do NOT run migrations). Each row is guarded
         * with `INSERT OR IGNORE` (belt-and-braces on the fixed negative id)
         * AND a `NOT EXISTS ... matchHost` guard so a user-edited/toggled copy
         * of the same seed is never re-inserted or overwritten. Failures are
         * swallowed: seeding must never crash app startup or a migration.
         */
        fun seedBuiltInHostRewrites(db: SupportSQLiteDatabase) {
            // (id, matchHost, matchType, kind, targetHost, preserveHostInPath)
            val builtIns = listOf(
                Triple(-51L, Pair("x.com", Pair(RewriteMatchType.EXACT_HOST, RewriteKind.HOST_SWAP)), Pair("twitter.com", false)),
                Triple(-52L, Pair("www.tiktok.com", Pair(RewriteMatchType.EXACT_WWW_HOST, RewriteKind.HOST_SWAP)), Pair("www.seetiktok.com", false)),
                Triple(-53L, Pair("nytimes.com", Pair(RewriteMatchType.EXACT_HOST, RewriteKind.PATH_PREFIX_REWRITE)), Pair("archive.md", true)),
            )
            // Fixed priorities for the seeds (per spec: 1, 2, 3). User inserts
            // are assigned `maxPriority + 1`, so they always land above the seeds
            // and never collide with them.
            val priorities = listOf(1, 2, 3)
            try {
                builtIns.forEachIndexed { index, seed ->
                    val (id, hostAndKind, targetAndFlag) = seed
                    val (matchHost, matchAndKind) = hostAndKind
                    val (targetHost, preserve) = targetAndFlag
                    val (matchType, kind) = matchAndKind
                    val priority = priorities[index]
                    val preserveInt = if (preserve) 1 else 0
                    // `INSERT OR IGNORE` (id) + `NOT EXISTS (matchHost)` make this
                    // seed idempotent: it neither re-inserts nor overwrites when
                    // ANY row with this matchHost already exists — user-toggled
                    // (enabled = 1) seeds are never touched.
                    db.execSQL(
                        "INSERT OR IGNORE INTO host_rewrites " +
                            "(id, matchHost, matchType, kind, targetHost, preserveHostInPath, enabled, priority, isBuiltIn) " +
                            "SELECT $id, '$matchHost', '${matchType.name}', '${kind.name}', '$targetHost', $preserveInt, 0, $priority, 1 " +
                            "WHERE NOT EXISTS (SELECT 1 FROM host_rewrites WHERE matchHost = '$matchHost')"
                    )
                }
            } catch (e: Exception) {
                // Defensive: never let seeding take a migration or app open down.
            }
        }
    }
}
