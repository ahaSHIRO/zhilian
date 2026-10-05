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

    /** 删除指定题目的全部作答记录（撤销批次导入时级联清除该批练习历史） */
    @Query("DELETE FROM answer_records WHERE question_id IN (:questionIds)")
    suspend fun deleteByQuestionIds(questionIds: List<String>)

    @Query("SELECT COUNT(*) FROM answer_records")
    fun observeCount(): Flow<Int>
}
