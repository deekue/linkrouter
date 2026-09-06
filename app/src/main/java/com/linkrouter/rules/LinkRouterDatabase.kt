package com.linkrouter.rules

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RuleEntity::class, RedirectFormatEntity::class],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class LinkRouterDatabase : RoomDatabase() {
    abstract fun ruleDao(): RuleDao
    abstract fun redirectFormatDao(): RedirectFormatDao

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
    }
}
