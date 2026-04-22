package com.cashewbridge.app.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedNotificationDao {

    @Query("SELECT * FROM notification_cache ORDER BY timestamp DESC")
    fun getAllFlow(): Flow<List<CachedNotification>>

    @Query("SELECT * FROM notification_cache ORDER BY timestamp DESC")
    suspend fun getAll(): List<CachedNotification>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: CachedNotification)

    @Query("DELETE FROM notification_cache WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM notification_cache")
    suspend fun deleteAll()

    /** Distinct package names seen in the cache — used by AppBlocklistActivity (#9). */
    @Query("SELECT DISTINCT packageName FROM notification_cache ORDER BY packageName ASC")
    suspend fun getDistinctPackages(): List<String>

    /** Keep only the newest N entries to avoid unbounded growth. */
    @Query("""
        DELETE FROM notification_cache 
        WHERE `key` NOT IN (
            SELECT `key` FROM notification_cache ORDER BY timestamp DESC LIMIT :maxCount
        )
    """)
    suspend fun trimToSize(maxCount: Int)
}
