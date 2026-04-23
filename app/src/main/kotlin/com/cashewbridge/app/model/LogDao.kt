package com.cashewbridge.app.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    @Query("SELECT * FROM logs ORDER BY timestamp DESC LIMIT 200")
    fun getRecentLogs(): Flow<List<ProcessedLog>>

    @Insert
    suspend fun insertLog(log: ProcessedLog)

    @Query("DELETE FROM logs WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM logs")
    suspend fun clearAll()

    /** Count forwarded transactions since a given timestamp (for today's stats). */
    @Query("SELECT COUNT(*) FROM logs WHERE timestamp >= :since AND actionTaken = 'LAUNCHED'")
    suspend fun countForwardedSince(since: Long): Int

    /** Count skipped (min amount / no amount) transactions since a given timestamp. */
    @Query("SELECT COUNT(*) FROM logs WHERE timestamp >= :since AND (actionTaken = 'SKIPPED_MIN_AMOUNT' OR actionTaken = 'NO_AMOUNT')")
    suspend fun countSkippedSince(since: Long): Int

    /** Count pending-confirm transactions since a given timestamp. */
    @Query("SELECT COUNT(*) FROM logs WHERE timestamp >= :since AND actionTaken = 'PENDING_CONFIRM'")
    suspend fun countPendingSince(since: Long): Int

    // ── Insights queries (#6) ──────────────────────────────────────────────────

    /** Sum of forwarded expenses per calendar-day label (last N days), for the bar chart. */
    @Query("""
        SELECT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch') AS day,
               SUM(parsedAmount) AS total
        FROM logs
        WHERE timestamp >= :since
          AND actionTaken IN ('LAUNCHED', 'BATCHED')
          AND parsedAmount IS NOT NULL
          AND isIncome = 0
        GROUP BY day
        ORDER BY day ASC
    """)
    suspend fun getDailyExpenses(since: Long): List<DayTotal>

    /** Total forwarded expenses for daily/weekly summary notification (#5). */
    @Query("""
        SELECT SUM(parsedAmount)
        FROM logs
        WHERE timestamp >= :since
          AND actionTaken IN ('LAUNCHED', 'BATCHED')
          AND parsedAmount IS NOT NULL
          AND isIncome = 0
    """)
    suspend fun sumExpensesSince(since: Long): Double?

    /** Count forwarded transactions (expense + income) since [since]. */
    @Query("""
        SELECT COUNT(*)
        FROM logs
        WHERE timestamp >= :since
          AND actionTaken IN ('LAUNCHED', 'BATCHED')
    """)
    suspend fun countForwardedAllSince(since: Long): Int

    /** Top merchant by count since [since] (for summary). */
    @Query("""
        SELECT parsedMerchant AS day, COUNT(*) AS total
        FROM logs
        WHERE timestamp >= :since
          AND actionTaken IN ('LAUNCHED', 'BATCHED')
          AND parsedMerchant IS NOT NULL
        GROUP BY parsedMerchant
        ORDER BY total DESC
        LIMIT 1
    """)
    suspend fun getTopMerchantSince(since: Long): List<DayTotal>

    // ── Per-currency insights (multi-currency correctness) ─────────────────────

    /** Forwarded expense totals grouped by currency. */
    @Query("""
        SELECT currency, SUM(parsedAmount) AS total
        FROM logs
        WHERE timestamp >= :since
          AND actionTaken IN ('LAUNCHED', 'BATCHED')
          AND parsedAmount IS NOT NULL
          AND isIncome = 0
        GROUP BY currency
        ORDER BY total DESC
    """)
    suspend fun getExpenseTotalsByCurrency(since: Long): List<CurrencyTotal>

    /** Forwarded income totals grouped by currency. */
    @Query("""
        SELECT currency, SUM(parsedAmount) AS total
        FROM logs
        WHERE timestamp >= :since
          AND actionTaken IN ('LAUNCHED', 'BATCHED')
          AND parsedAmount IS NOT NULL
          AND isIncome = 1
        GROUP BY currency
        ORDER BY total DESC
    """)
    suspend fun getIncomeTotalsByCurrency(since: Long): List<CurrencyTotal>

    /** Category breakdown that preserves the currency of each row. */
    @Query("""
        SELECT COALESCE(parsedCategory, '(uncategorised)') AS category,
               currency,
               SUM(parsedAmount) AS total
        FROM logs
        WHERE timestamp >= :since
          AND actionTaken IN ('LAUNCHED', 'BATCHED')
          AND parsedAmount IS NOT NULL
          AND isIncome = 0
        GROUP BY parsedCategory, currency
        ORDER BY total DESC
        LIMIT 10
    """)
    suspend fun getCategoryBreakdownByCurrency(since: Long): List<CategoryCurrencyTotal>
}

/** Reusable projection for day/label + amount pairs. */
data class DayTotal(val day: String, val total: Double)

/** Sum of forwarded amounts for a single ISO-4217 currency code. */
data class CurrencyTotal(val currency: String, val total: Double)

/** Sum of forwarded amounts for a (category, currency) pair. */
data class CategoryCurrencyTotal(val category: String, val currency: String, val total: Double)
