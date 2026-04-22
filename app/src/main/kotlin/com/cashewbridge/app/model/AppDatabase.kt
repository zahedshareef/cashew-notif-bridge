package com.cashewbridge.app.model

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NotificationRule::class,
        ProcessedLog::class,
        CachedNotification::class,
        BatchedTransaction::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun ruleDao(): RuleDao
    abstract fun logDao(): LogDao
    abstract fun cachedNotificationDao(): CachedNotificationDao
    abstract fun batchedTransactionDao(): BatchedTransactionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rules ADD COLUMN autoDetectType INTEGER NOT NULL DEFAULT 0")
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

        /**
         * Migration v2 → v3:
         * - Add OR/AND condition logic, per-rule cooldown, and time-range fields to rules
         * - Add batch_queue table for batch review mode
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // New rule fields
                db.execSQL("ALTER TABLE rules ADD COLUMN conditionLogic INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rules ADD COLUMN cooldownMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rules ADD COLUMN activeStartHour INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE rules ADD COLUMN activeEndHour INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE rules ADD COLUMN activeDaysOfWeek INTEGER NOT NULL DEFAULT 0")

                // Batch queue table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS batch_queue (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        sourcePackage TEXT NOT NULL,
                        appLabel TEXT NOT NULL,
                        sourceTitle TEXT NOT NULL,
                        cashewUri TEXT NOT NULL,
                        amount REAL NOT NULL,
                        merchant TEXT,
                        category TEXT,
                        isIncome INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
