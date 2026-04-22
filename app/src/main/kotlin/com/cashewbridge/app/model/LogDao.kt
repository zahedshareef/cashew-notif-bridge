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
}
