package com.baiyin.zhilian.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(questions: List<QuestionEntity>)

    /** 已存在的题目 ID（导入前判重） */
    @Query("SELECT question_id FROM questions WHERE question_id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    /** 与候选题干（NFC+trim 归一化）完全相同的既有题（疑似重复判定材料） */
    @Query("SELECT * FROM questions WHERE stem = :normalizedStem AND inactive = 0 LIMIT 5")
    suspend fun findByStem(normalizedStem: String): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE question_id = :id")
    suspend fun getById(id: String): QuestionEntity?

    @Query("SELECT * FROM questions WHERE question_id IN (:ids) ORDER BY batch_order, order_in_batch")
    suspend fun getByIds(ids: List<String>): List<QuestionEntity>

    /** 题库列表（按批次顺序） */
    @Query("SELECT * FROM questions WHERE inactive = 0 ORDER BY batch_order, order_in_batch")
    fun observeActive(): Flow<List<QuestionEntity>>

    /** 分类列表（题库筛选 chips） */
    @Query("SELECT DISTINCT category FROM questions WHERE inactive = 0 ORDER BY category")
    fun observeCategories(): Flow<List<String>>

    /** 练习选题：whereSql 由调用方用受控常量拼装（分类名经转义），参数走 ? 绑定 */
    @RawQuery(observedEntities = [QuestionEntity::class])
    suspend fun rawForPractice(query: SupportSQLiteQuery): List<QuestionEntity>

    @Query("UPDATE questions SET favorite = :favorite WHERE question_id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE questions SET consecutive_perfect = :consecutive, has_ever_wrong = :hasEverWrong WHERE question_id = :id")
    suspend fun updateMastery(id: String, consecutive: Int, hasEverWrong: Boolean)

    @Query("UPDATE questions SET inactive = 1 WHERE question_id IN (:ids)")
    suspend fun markInactive(ids: List<String>)

    @Query("SELECT COUNT(*) FROM questions WHERE inactive = 0")
    fun observeActiveCount(): Flow<Int>

    // ---- 统计聚合（README：首次作答正确率 / 全部作答正确率 / 多选全对率 / 平均得分率） ----

    @Query(
        """
        SELECT AVG(r.is_perfect) FROM answer_records r
        JOIN (
            SELECT question_id, MIN(answered_at) AS first_at
            FROM answer_records GROUP BY question_id
        ) f ON r.question_id = f.question_id AND r.answered_at = f.first_at
        """
    )
    suspend fun firstAttemptAccuracy(): Double?

    @Query("SELECT AVG(is_perfect) FROM answer_records")
    suspend fun overallAccuracy(): Double?

    @Query("SELECT AVG(is_perfect) FROM answer_records WHERE type = 'multiple_choice'")
    suspend fun multipleChoicePerfectRate(): Double?

    @Query("SELECT AVG(score_rate) FROM answer_records")
    suspend fun averageScoreRate(): Double?
}
