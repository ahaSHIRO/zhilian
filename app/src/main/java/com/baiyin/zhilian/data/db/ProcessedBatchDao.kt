package com.baiyin.zhilian.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProcessedBatchDao {

    @Upsert
    suspend fun upsert(record: ProcessedBatchEntity)

    @Query("SELECT * FROM processed_batches ORDER BY batch_order DESC")
    fun observeAll(): Flow<List<ProcessedBatchEntity>>

    @Query("SELECT * FROM processed_batches WHERE batch_id = :batchId")
    suspend fun getById(batchId: String): ProcessedBatchEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM processed_batches WHERE batch_order = :order AND status = 'IMPORTED')")
    suspend fun isOrderTaken(order: Int): Boolean
}
