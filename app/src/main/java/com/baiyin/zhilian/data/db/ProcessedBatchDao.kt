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

    /** 全部已处理记录（题库对账读取指纹与占用顺序号的快照） */
    @Query("SELECT * FROM processed_batches")
    suspend fun all(): List<ProcessedBatchEntity>

    @Query("SELECT * FROM processed_batches WHERE batch_id = :batchId")
    suspend fun getById(batchId: String): ProcessedBatchEntity?

    /** 删除已处理记录（撤销批次导入） */
    @Query("DELETE FROM processed_batches WHERE batch_id = :batchId")
    suspend fun deleteById(batchId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM processed_batches WHERE batch_order = :order AND status = 'IMPORTED')")
    suspend fun isOrderTaken(order: Int): Boolean
}
