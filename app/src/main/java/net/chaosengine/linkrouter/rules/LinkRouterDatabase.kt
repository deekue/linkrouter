package net.chaosengine.linkrouter.rules

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RuleEntity::class, RedirectFormatEntity::class, ShortenerHostEntity::class, QueryParamFilterEntity::class],
    version = 7,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class LinkRouterDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun redirectFormatDao(): RedirectFormatDao
    abstract fun shortenerHostDao(): ShortenerHostDao
    abstract fun queryParamFilterDao(): QueryParamFilterDao

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
    }
}
