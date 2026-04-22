package com.cashewbridge.app.model

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {

    @Query("SELECT * FROM rules ORDER BY priority DESC, id ASC")
    fun getAllRules(): Flow<List<NotificationRule>>

    @Query("SELECT * FROM rules ORDER BY priority DESC, id ASC")
    suspend fun getAllRulesSync(): List<NotificationRule>

    @Query("SELECT * FROM rules WHERE isEnabled = 1 ORDER BY priority DESC, id ASC")
    suspend fun getEnabledRules(): List<NotificationRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: NotificationRule): Long

    @Update
    suspend fun updateRule(rule: NotificationRule)

    @Delete
    suspend fun deleteRule(rule: NotificationRule)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteById(id: Long)
}
