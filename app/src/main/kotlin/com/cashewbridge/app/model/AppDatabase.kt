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
        BatchedTransaction::class,
        AppBlocklistEntry::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun ruleDao(): RuleDao
    abstract fun logDao(): LogDao
    abstract fun cachedNotificationDao(): CachedNotificationDao
    abstract fun batchedTransactionDao(): BatchedTransactionDao
    abstract fun appBlocklistDao(): AppBlocklistDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rules ADD COLUMN conditionLogic INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rules ADD COLUMN cooldownMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rules ADD COLUMN activeStartHour INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE rules ADD COLUMN activeEndHour INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE rules ADD COLUMN activeDaysOfWeek INTEGER NOT NULL DEFAULT 0")
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

        /**
         * Migration v3 → v4:
         *  - Adds noteRegex, senderContains, minAmountFilter, maxAmountFilter, currencyOverride
         *    to the rules table (#1, #2, #3, #8)
         *  - Adds currency column to logs table (#1)
         *  - Creates app_blocklist table (#9)
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rules additions
                db.execSQL("ALTER TABLE rules ADD COLUMN noteRegex TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE rules ADD COLUMN senderContains TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE rules ADD COLUMN minAmountFilter REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE rules ADD COLUMN maxAmountFilter REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE rules ADD COLUMN currencyOverride TEXT NOT NULL DEFAULT ''")
                // Logs — currency column
                db.execSQL("ALTER TABLE logs ADD COLUMN currency TEXT NOT NULL DEFAULT 'USD'")
                // App blocklist table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_blocklist (
                        packageName TEXT NOT NULL PRIMARY KEY,
                        appLabel TEXT NOT NULL,
                        addedAt INTEGER NOT NULL
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
