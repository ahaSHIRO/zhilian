package com.baiyin.zhilian.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnswerRecordDao {

    @Insert
    suspend fun insert(record: AnswerRecordEntity)

    @Query("SELECT COUNT(*) FROM answer_records WHERE question_id = :questionId")
    suspend fun countByQuestion(questionId: String): Int

    @Query("DELETE FROM answer_records")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM answer_records")
    fun observeCount(): Flow<Int>
}
