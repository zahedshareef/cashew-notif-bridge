package com.cashewbridge.app.model

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [NotificationRule::class, ProcessedLog::class, CachedNotification::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun ruleDao(): RuleDao
    abstract fun logDao(): LogDao
    abstract fun cachedNotificationDao(): CachedNotificationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Migration from v1 (no autoDetectType, no notification_cache) to v2. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add autoDetectType column to rules (default false = 0)
                db.execSQL("ALTER TABLE rules ADD COLUMN autoDetectType INTEGER NOT NULL DEFAULT 0")

                // Create the persistent notification cache table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS notification_cache (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        packageName TEXT NOT NULL,
                        appLabel TEXT NOT NULL,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        parsedAmount REAL,
                        parsedMerchant TEXT,
                        parsedCategory TEXT,
                        parsedWallet TEXT,
                        isIncome INTEGER NOT NULL,
                        matchedRuleName TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cashew_bridge.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
