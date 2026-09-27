package com.baiyin.zhilian.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingDuplicateDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<PendingDuplicateEntity>)

    @Query("SELECT * FROM pending_duplicates ORDER BY batch_id, order_in_batch")
    fun observeAll(): Flow<List<PendingDuplicateEntity>>

    @Query("SELECT * FROM pending_duplicates WHERE batch_id = :batchId AND question_id = :questionId")
    suspend fun get(batchId: String, questionId: String): PendingDuplicateEntity?

    @Query("DELETE FROM pending_duplicates WHERE batch_id = :batchId AND question_id = :questionId")
    suspend fun delete(batchId: String, questionId: String)

    @Query("SELECT COUNT(*) FROM pending_duplicates WHERE batch_id = :batchId")
    suspend fun countByBatch(batchId: String): Int
}
