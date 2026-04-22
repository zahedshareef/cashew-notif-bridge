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
}
