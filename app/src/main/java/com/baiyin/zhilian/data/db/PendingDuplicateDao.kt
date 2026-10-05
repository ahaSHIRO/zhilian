package com.baiyin.zhilian.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingDuplicateDao {

    /** 幂等写入：题库对账每次前台都会重跑，同一待决项重复到来时不应抛异常 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<PendingDuplicateEntity>)

    @Query("SELECT * FROM pending_duplicates ORDER BY batch_id, order_in_batch")
    fun observeAll(): Flow<List<PendingDuplicateEntity>>

    @Query("SELECT * FROM pending_duplicates WHERE batch_id = :batchId AND question_id = :questionId")
    suspend fun get(batchId: String, questionId: String): PendingDuplicateEntity?

    @Query("DELETE FROM pending_duplicates WHERE batch_id = :batchId AND question_id = :questionId")
    suspend fun delete(batchId: String, questionId: String)

    @Query("SELECT COUNT(*) FROM pending_duplicates WHERE batch_id = :batchId")
    suspend fun countByBatch(batchId: String): Int

    /** 撤销批次导入时清除该批待决项 */
    @Query("DELETE FROM pending_duplicates WHERE batch_id = :batchId")
    suspend fun deleteByBatch(batchId: String)
}
