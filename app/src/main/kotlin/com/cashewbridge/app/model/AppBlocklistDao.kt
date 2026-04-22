package com.cashewbridge.app.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppBlocklistDao {

    @Query("SELECT * FROM app_blocklist ORDER BY appLabel ASC")
    fun getAll(): Flow<List<AppBlocklistEntry>>

    @Query("SELECT packageName FROM app_blocklist")
    suspend fun getAllPackageNames(): List<String>

    @Query("SELECT COUNT(*) FROM app_blocklist WHERE packageName = :pkg")
    suspend fun isBlocked(pkg: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AppBlocklistEntry)

    @Query("DELETE FROM app_blocklist WHERE packageName = :pkg")
    suspend fun remove(pkg: String)

    @Query("DELETE FROM app_blocklist")
    suspend fun clearAll()
}
