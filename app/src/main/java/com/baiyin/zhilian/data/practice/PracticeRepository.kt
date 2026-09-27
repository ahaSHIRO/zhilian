package com.baiyin.zhilian.data.practice

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.baiyin.zhilian.data.db.QuestionDao
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.db.ZhilianDatabase

/** 练习筛选条件（README：条件组合 = 全部满足；顺序模式按批次顺序号与文件内顺序） */
data class PracticeFilter(
    val categories: Set<String> = emptySet(), // 空 = 不限分类
    val onlyWrong: Boolean = false,
    val onlyFavorite: Boolean = false,
    val sequential: Boolean = true,
    val limit: Int = 20,
)

/** 作答提交结果（UI 反馈所需最小集；isFirstAttempt 是落库内部事不外露） */
data class SubmitSummary(val rate: Double, val perfect: Boolean)

/** 练习选题与作答落库 */
class PracticeRepository(private val db: ZhilianDatabase) {

    private val questionDao: QuestionDao = db.questionDao()

    /** 拼装 WHERE 子句（受控常量；分类名经转义）；选题与计数共用，避免两处条件漂移 */
    private fun buildWhere(filter: PracticeFilter): String = buildList {
        add("inactive = 0")
        if (filter.categories.isNotEmpty()) {
            val escaped = filter.categories.joinToString(",") { "'" + it.replace("'", "''") + "'" }
            add("category IN ($escaped)") // 分类名来自本机题库 DISTINCT，常量级注入面
        }
        if (filter.onlyWrong) add("has_ever_wrong = 1 AND consecutive_perfect < 2")
        if (filter.onlyFavorite) add("favorite = 1")
    }.joinToString(" AND ")

    suspend fun pickQuestions(filter: PracticeFilter): List<QuestionEntity> {
        val order = if (filter.sequential) "batch_order, order_in_batch" else "RANDOM()"
        val sql = "SELECT * FROM questions WHERE ${buildWhere(filter)} ORDER BY $order LIMIT ${filter.limit}"
        return questionDao.rawForPractice(SimpleSQLiteQuery(sql))
    }

    /** 符合条件的题目总数（不受 limit 截断；练习配置页预览用） */
    suspend fun countMatching(filter: PracticeFilter): Int {
        val sql = "SELECT COUNT(*) FROM questions WHERE ${buildWhere(filter)}"
        return questionDao.countRaw(SimpleSQLiteQuery(sql))
    }

    /**
     * 提交作答：单个 Room 事务内 判首答 → 写作答记录 → 读当前掌握度 → 转移 → 写回（ADR-0004）。
     *
     * 评分与掌握度转移收口于此接口：调用方只传 (question, answer)，不再自算 scoreRate/isPerfect；
     * 返回 [SubmitSummary] 供 UI 反馈。事务内 [getById] 读库内当前掌握度，修原先读陈旧实体快照的隐患。
     */
    suspend fun submitAnswer(question: QuestionEntity, answer: UserAnswer): SubmitSummary =
        db.withTransaction {
            val (rate, perfect) = Scoring.score(question, answer)
            val isFirst = db.answerRecordDao().countByQuestion(question.questionId) == 0
            val outcome = Scoring.outcomeOf(rate, perfect)
            // 读库内当前掌握度（修陈旧快照）；题目必在库，此兜底为防御
            val current = questionDao.getById(question.questionId)
            val currentMastery = if (current != null) {
                Mastery(current.consecutivePerfect, current.hasEverWrong)
            } else {
                Mastery(question.consecutivePerfect, question.hasEverWrong)
            }
            val newMastery = masteryTransition(currentMastery, outcome)
            db.answerRecordDao().insert(
                Scoring.toRecord(
                    question = question,
                    answer = answer,
                    scoreRate = rate,
                    isPerfect = perfect,
                    isFirstAttempt = isFirst,
                    answeredAt = System.currentTimeMillis(),
                )
            )
            questionDao.updateMastery(question.questionId, newMastery.consecutivePerfect, newMastery.hasEverWrong)
            SubmitSummary(rate, perfect)
        }

    // 统计（README 四项指标）
    suspend fun firstAttemptAccuracy() = questionDao.firstAttemptAccuracy()
    suspend fun overallAccuracy() = questionDao.overallAccuracy()
    suspend fun multipleChoicePerfectRate() = questionDao.multipleChoicePerfectRate()
    suspend fun averageScoreRate() = questionDao.averageScoreRate()
}
