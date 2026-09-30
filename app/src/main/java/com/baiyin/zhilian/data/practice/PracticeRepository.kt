package com.baiyin.zhilian.data.practice

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.baiyin.zhilian.data.db.CategoryWrongRow
import com.baiyin.zhilian.data.db.QuestionDao
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.db.ZhilianDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 练习筛选条件（README：条件组合 = 全部满足；顺序模式按批次顺序号与文件内顺序）。
 * 空集合一律表示"不限该维度"。
 *
 * 维度间一律取交集（AND）；**同一维度内多选取并集（OR）**——
 * 科目/分类/题型/标签都是"选 A 和 B = 刷 A 或 B"。唯一例外是范围维度：
 * 错题与收藏是两个独立开关，同时打开表示"既答错过、又被收藏"，即取交集。
 *
 * 条件如何变成 SQL 见 [PracticeSql]（纯函数），入口见 [QuestionPicker]。
 */
data class PracticeFilter(
    val subjects: Set<String> = emptySet(), // 空 = 不限科目（kotlin / java / interview）
    val categories: Set<String> = emptySet(), // 空 = 不限分类
    val types: Set<String> = emptySet(), // 空 = 不限题型（single_choice 等）
    val tags: Set<String> = emptySet(), // 空 = 不限标签；比分类更细粒度，取并集
    val onlyWrong: Boolean = false,
    val onlyFavorite: Boolean = false,
    val sequential: Boolean = true,
    val limit: Int = 20,
)

/** 作答提交结果（UI 反馈所需最小集；isFirstAttempt 是落库内部事不外露） */
data class SubmitSummary(val rate: Double, val perfect: Boolean)

/**
 * 作答落库与统计。
 *
 * **选题不在这里**（见 [QuestionPicker]）：这个类只管「作答之后」的事——提交、清除、统计。
 * 原先两类职责混在一个仓里，条件的四份 SQL 与统计的错题片段共用私有常量，
 * 谁都不好单独测。
 *
 * [clock] 是本模块唯一的外部时间源：作答时间由它给出，测试可替换为固定值钉死时间，
 * 事务体内不再内联 `System.currentTimeMillis()`。
 */
class PracticeRepository(
    private val db: ZhilianDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val questionDao: QuestionDao = db.questionDao()

    /**
     * 提交作答：单个 Room 事务内 判首答 → 算落库计划 → 写作答记录 → 写回掌握度（ADR-0004）。
     *
     * 评分与掌握度转移收口于 [planSubmission]：调用方只传 (question, answer)，不再自算
     * scoreRate/isPerfect；返回 [SubmitSummary] 供 UI 反馈。事务内 [getById] 读库内当前掌握度，
     * 修原先读陈旧实体快照的隐患。
     */
    suspend fun submitAnswer(question: QuestionEntity, answer: UserAnswer): SubmitSummary =
        db.withTransaction {
            val isFirst = db.answerRecordDao().countByQuestion(question.questionId) == 0
            // 读库内当前掌握度（修陈旧快照）；题目必在库，此兜底为防御
            val current = questionDao.getById(question.questionId)
            val currentMastery = if (current != null) {
                Mastery(current.consecutivePerfect, current.hasEverWrong)
            } else {
                Mastery(question.consecutivePerfect, question.hasEverWrong)
            }
            val plan = planSubmission(
                question = question,
                answer = answer,
                isFirstAttempt = isFirst,
                currentMastery = currentMastery,
                answeredAt = clock(),
            )
            db.answerRecordDao().insert(plan.record)
            questionDao.updateMastery(
                question.questionId,
                plan.newMastery.consecutivePerfect,
                plan.newMastery.hasEverWrong,
            )
            plan.summary
        }

    /**
     * 清除全部本机练习记录（README）。
     *
     * 单事务内清空作答记录 **并复位题目掌握度**：只删 answer_records 会让
     * isWrong（has_ever_wrong && consecutive_perfect < 2）继续为真——错题列表非空
     * 而统计页显示 0 条记录，状态与历史分裂。收藏不属于练习记录，不复位。
     */
    suspend fun clearAllHistory() {
        db.withTransaction {
            db.answerRecordDao().deleteAll()
            questionDao.resetAllMastery()
        }
    }

    /** 作答记录条数（统计页） */
    fun observeRecordCount(): Flow<Int> = db.answerRecordDao().observeCount()

    // 统计（README 四项指标）
    suspend fun firstAttemptAccuracy() = questionDao.firstAttemptAccuracy()
    suspend fun overallAccuracy() = questionDao.overallAccuracy()
    suspend fun multipleChoicePerfectRate() = questionDao.multipleChoicePerfectRate()
    suspend fun averageScoreRate() = questionDao.averageScoreRate()

    // 统计（弱项卡：错题数 + 弱项分类 TOP3；错题条件与 [PracticeSql.where] 共用 SQL_WRONG）

    /** 当前错题总数（CONTEXT.md「错题」） */
    suspend fun wrongQuestionCount(): Int =
        questionDao.countRaw(SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM questions WHERE inactive = 0 AND $SQL_WRONG"
        ))

    /** 弱项分类 TOP [limit]：当前错题按分类计数，降序取前若干（并列按分类名稳定排序） */
    suspend fun topWrongCategories(limit: Int = 3): List<CategoryWrongRow> =
        questionDao.wrongCategoryRows(SimpleSQLiteQuery(
            "SELECT category, COUNT(*) AS wrong_count FROM questions " +
                "WHERE inactive = 0 AND $SQL_WRONG " +
                "GROUP BY category ORDER BY wrong_count DESC, category LIMIT $limit"
        ))
}
