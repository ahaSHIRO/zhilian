package com.baiyin.zhilian.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * 标签筛选用的轻量投影：只取 question_id 与 tags_json 两列。
 *
 * 标签以 JSON 数组字符串存在一列里，用 SQL 条件表达"含某标签"并不可靠
 * （详见 PracticeRepository.matchesTags），故先取此投影在内存过滤，
 * 避免为了几个标签把题干、解析等整行实体全部拉进内存。
 */
data class QuestionTagRow(
    @ColumnInfo(name = "question_id") val questionId: String,
    @ColumnInfo(name = "tags_json") val tagsJson: String,
)

/**
 * 题干反查用的投影：归一化题干 → 题目 ID。
 * 导入时一次性取回整批，供疑似重复判定（spec §应用级校验 8）在纯规划里查表。
 */
data class StemOwnerRow(
    @ColumnInfo(name = "question_id") val questionId: String,
    @ColumnInfo(name = "stem") val stem: String,
)

/**
 * 弱项分类聚合投影（统计页弱项卡）：当前错题（CONTEXT.md「错题」）按分类计数。
 */
data class CategoryWrongRow(
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "wrong_count") val wrongCount: Int,
)

@Dao
interface QuestionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(questions: List<QuestionEntity>)

    /** 已存在的题目 ID（导入前判重） */
    @Query("SELECT question_id FROM questions WHERE question_id IN (:ids)")
    suspend fun existingIds(ids: List<String>): List<String>

    /** 与候选题干（NFC+trim 归一化）完全相同的既有题（疑似重复判定材料，清单 8） */
    @Query("SELECT question_id, stem FROM questions WHERE stem IN (:stems) AND inactive = 0")
    suspend fun findStemOwners(stems: List<String>): List<StemOwnerRow>

    @Query("SELECT * FROM questions WHERE question_id = :id")
    suspend fun getById(id: String): QuestionEntity?

    /** 单题流（题库详情面板按 id 订阅，写操作后立即反映最新值） */
    @Query("SELECT * FROM questions WHERE question_id = :id")
    fun observeById(id: String): Flow<QuestionEntity?>

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

    /** 符合条件的题目计数（不受 LIMIT 影响；练习配置页预览用） */
    @RawQuery
    suspend fun countRaw(query: SupportSQLiteQuery): Int

    /**
     * 单列字符串结果（DISTINCT subject / category / type 等）。
     * 取单列用 rawForPractice 会浪费整行映射，故单列走这个。
     */
    @RawQuery
    suspend fun rawForStrings(query: SupportSQLiteQuery): List<String>

    /** 标签筛选的两列投影（见 [QuestionTagRow]）；whereSql 由调用方用受控常量拼装 */
    @RawQuery
    suspend fun rawForTagRows(query: SupportSQLiteQuery): List<QuestionTagRow>

    @Query("UPDATE questions SET favorite = :favorite WHERE question_id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE questions SET consecutive_perfect = :consecutive, has_ever_wrong = :hasEverWrong WHERE question_id = :id")
    suspend fun updateMastery(id: String, consecutive: Int, hasEverWrong: Boolean)

    @Query("UPDATE questions SET inactive = 1 WHERE question_id IN (:ids)")
    suspend fun markInactive(ids: List<String>)

    /** 清除练习记录时复位全部题目掌握度（错题标记与连续全对计数）；收藏不受影响 */
    @Query("UPDATE questions SET consecutive_perfect = 0, has_ever_wrong = 0")
    suspend fun resetAllMastery()

    @Query("SELECT COUNT(*) FROM questions WHERE inactive = 0")
    fun observeActiveCount(): Flow<Int>

    // ---- 统计聚合（README：首次作答正确率 / 全部作答正确率 / 多选全对率 / 平均得分率） ----

    /**
     * 首次作答正确率：以落库时写定的 is_first_attempt 列为准（单一真值源）。
     * 不再用 MIN(answered_at) 子查询 JOIN 重算——那会与列分歧，且同毫秒双记录会重复计数。
     */
    @Query("SELECT AVG(is_perfect) FROM answer_records WHERE is_first_attempt = 1")
    suspend fun firstAttemptAccuracy(): Double?

    @Query("SELECT AVG(is_perfect) FROM answer_records")
    suspend fun overallAccuracy(): Double?

    @Query("SELECT AVG(is_perfect) FROM answer_records WHERE type = 'multiple_choice'")
    suspend fun multipleChoicePerfectRate(): Double?

    @Query("SELECT AVG(score_rate) FROM answer_records")
    suspend fun averageScoreRate(): Double?

    /** 弱项卡：当前错题按分类聚合（TOP N 与排序由调用方 SQL 给定，错题条件与 PracticeRepository 同源） */
    @RawQuery
    suspend fun wrongCategoryRows(query: SupportSQLiteQuery): List<CategoryWrongRow>
}
