package com.cashewbridge.app.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BatchedTransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tx: BatchedTransaction): Long

    @Query("SELECT * FROM batch_queue ORDER BY timestamp ASC")
    suspend fun getAll(): List<BatchedTransaction>

    @Query("SELECT COUNT(*) FROM batch_queue")
    suspend fun count(): Int

    @Query("DELETE FROM batch_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM batch_queue")
    suspend fun deleteAll()
}
