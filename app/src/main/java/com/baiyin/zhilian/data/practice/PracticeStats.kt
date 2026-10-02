package com.baiyin.zhilian.data.practice

import androidx.sqlite.db.SimpleSQLiteQuery
import com.baiyin.zhilian.data.db.CategoryWrongRow
import com.baiyin.zhilian.data.db.QuestionDao
import com.baiyin.zhilian.data.db.ZhilianDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 练习统计（ADR-0013：从 [PracticeRepository] 拆出，让后者只管「作答之后」的落库编排）。
 *
 * 只读查询，无编排逻辑——四项指标 + 错题 / 弱项统计。错题条件与 [PracticeSql.where] 共用
 * [SQL_WRONG]（同一处片段，勿两处漂移）。
 */
class PracticeStats(private val db: ZhilianDatabase) {
    private val questionDao: QuestionDao = db.questionDao()

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
