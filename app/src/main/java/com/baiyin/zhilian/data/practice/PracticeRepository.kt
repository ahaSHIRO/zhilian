package com.baiyin.zhilian.data.practice

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

/** 练习选题与作答落库 */
class PracticeRepository(private val db: ZhilianDatabase) {

    private val questionDao: QuestionDao = db.questionDao()

    suspend fun pickQuestions(filter: PracticeFilter): List<QuestionEntity> {
        val where = buildList {
            add("inactive = 0")
            if (filter.categories.isNotEmpty()) {
                val escaped = filter.categories.joinToString(",") { "'" + it.replace("'", "''") + "'" }
                add("category IN ($escaped)") // 分类名来自本机题库 DISTINCT，常量级注入面
            }
            if (filter.onlyWrong) add("has_ever_wrong = 1 AND consecutive_perfect < 2")
            if (filter.onlyFavorite) add("favorite = 1")
        }.joinToString(" AND ")
        val order = if (filter.sequential) "batch_order, order_in_batch" else "RANDOM()"
        val sql = "SELECT * FROM questions WHERE $where ORDER BY $order LIMIT ${filter.limit}"
        return questionDao.rawForPractice(SimpleSQLiteQuery(sql))
    }

    /** 提交作答：写记录 + 更新掌握度（连续全对/错题标记），同一事务 */
    suspend fun submitAnswer(
        question: QuestionEntity,
        answer: UserAnswer,
        scoreRate: Double,
        isPerfect: Boolean,
    ) {
        val isFirst = db.answerRecordDao().countByQuestion(question.questionId) == 0
        db.answerRecordDao().insert(
            Scoring.toRecord(
                question = question,
                answer = answer,
                scoreRate = scoreRate,
                isPerfect = isPerfect,
                isFirstAttempt = isFirst,
                answeredAt = System.currentTimeMillis(),
            )
        )
        // 错题消解规则：错误归零；满分 +1；跳过不产生记录自然不变
        val newConsecutive = if (isPerfect) question.consecutivePerfect + 1 else 0
        val newEverWrong = question.hasEverWrong || !isPerfect
        questionDao.updateMastery(question.questionId, newConsecutive, newEverWrong)
    }

    // 统计（README 四项指标）
    suspend fun firstAttemptAccuracy() = questionDao.firstAttemptAccuracy()
    suspend fun overallAccuracy() = questionDao.overallAccuracy()
    suspend fun multipleChoicePerfectRate() = questionDao.multipleChoicePerfectRate()
    suspend fun averageScoreRate() = questionDao.averageScoreRate()
}
