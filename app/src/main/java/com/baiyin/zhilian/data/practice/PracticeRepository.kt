package com.baiyin.zhilian.data.practice

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.baiyin.zhilian.data.db.QuestionDao
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.db.QuestionTagRow
import com.baiyin.zhilian.data.db.ZhilianDatabase

/**
 * 练习筛选条件（README：条件组合 = 全部满足；顺序模式按批次顺序号与文件内顺序）。
 * 空集合一律表示"不限该维度"。
 *
 * 维度间一律取交集（AND）；**同一维度内多选取并集（OR）**——
 * 科目/分类/题型/标签都是"选 A 和 B = 刷 A 或 B"。唯一例外是范围维度：
 * 错题与收藏是两个独立开关，同时打开表示"既答错过、又被收藏"，即取交集。
 */
data class PracticeFilter(
    val subjects: Set<String> = emptySet(), // 空 = 不限科目（kotlin / java）
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

/** 练习选题与作答落库 */
class PracticeRepository(private val db: ZhilianDatabase) {

    private val questionDao: QuestionDao = db.questionDao()

    /** SQL 字符串常量转义（值来自本机题库 DISTINCT，常量级注入面） */
    private fun quote(values: Set<String>): String =
        values.joinToString(",") { "'" + it.replace("'", "''") + "'" }

    /** 拼装 WHERE 子句（受控常量）；选题与计数共用，避免两处条件漂移 */
    private fun buildWhere(filter: PracticeFilter): String = buildList {
        add("inactive = 0")
        if (filter.subjects.isNotEmpty()) add("subject IN (${quote(filter.subjects)})")
        if (filter.categories.isNotEmpty()) add("category IN (${quote(filter.categories)})")
        if (filter.types.isNotEmpty()) add("type IN (${quote(filter.types)})")
        if (filter.onlyWrong) add("has_ever_wrong = 1 AND consecutive_perfect < 2")
        if (filter.onlyFavorite) add("favorite = 1")
    }.joinToString(" AND ")

    suspend fun pickQuestions(filter: PracticeFilter): List<QuestionEntity> {
        // 未选标签时走原路径：SQL 直接 LIMIT，不为低频路径付出全表扫描的代价
        if (filter.tags.isEmpty()) {
            val order = if (filter.sequential) "batch_order, order_in_batch" else "RANDOM()"
            val sql = "SELECT * FROM questions WHERE ${buildWhere(filter)} ORDER BY $order LIMIT ${filter.limit}"
            return questionDao.rawForPractice(SimpleSQLiteQuery(sql))
        }
        val picked = tagRows(filter).filter { QuestionTags.matches(it.tagsJson, filter.tags) }
        val ids = (if (filter.sequential) picked else picked.shuffled())
            .take(filter.limit).map { it.questionId }
        // getByIds 按批次序返回，随机模式下须按打乱后的顺序重排，否则顺序练习以外一律退化为顺序
        val byId = questionDao.getByIds(ids).associateBy { it.questionId }
        return ids.mapNotNull { byId[it] }
    }

    /** 符合条件的题目总数（不受 limit 截断；练习配置页预览用） */
    suspend fun countMatching(filter: PracticeFilter): Int {
        if (filter.tags.isEmpty()) {
            val sql = "SELECT COUNT(*) FROM questions WHERE ${buildWhere(filter)}"
            return questionDao.countRaw(SimpleSQLiteQuery(sql))
        }
        return tagRows(filter).count { QuestionTags.matches(it.tagsJson, filter.tags) }
    }

    /**
     * 范围 chip 上的预判计数：假设只打开该范围、其余条件不变时能刷出几道。
     * 让"错题"从盲开关变成可判断的入口——否则只能勾上之后看总数才知道值不值得刷。
     */
    suspend fun wrongCount(filter: PracticeFilter): Int =
        countMatching(filter.copy(onlyWrong = true))

    suspend fun favoriteCount(filter: PracticeFilter): Int =
        countMatching(filter.copy(onlyFavorite = true))

    /** 题库中已有的科目（练习配置页科目 chips） */
    suspend fun distinctSubjects(): List<String> =
        questionDao.rawForStrings(SimpleSQLiteQuery(
            "SELECT DISTINCT subject FROM questions WHERE inactive = 0 ORDER BY subject"
        ))

    /**
     * 给定科目下已有的分类（科目为 null 时返回全部）。
     * 分类挂在科目下，故按科目取，避免 Java 与 Kotlin 的同名分类混在一起。
     */
    suspend fun distinctCategories(subject: String?): List<String> {
        val where = if (subject == null) "inactive = 0"
        else "inactive = 0 AND subject = '${subject.replace("'", "''")}'"
        return questionDao.rawForStrings(SimpleSQLiteQuery(
            "SELECT DISTINCT category FROM questions WHERE $where ORDER BY category"
        ))
    }

    /**
     * 给定科目与分类下已有的标签，二者任一为空表示不限该层。
     *
     * 标签比分类更细（同一科目的分类下常有 launch / async / mutex 等多个主题），
     * 但存在 JSON 数组列里，取 DISTINCT 必须逐行解析；题库为个人规模，全表扫描可接受。
     *
     * 分类这一层不是多余的过滤：全库标签基数远高于分类（18 道题已产出 20+ 个标签），
     * 不按分类收窄就会把配置页撑成两屏。先选分类、再用标签细筛，才是标签该出现的地方。
     */
    suspend fun distinctTags(subject: String?, categories: Set<String>): List<String> {
        val conditions = mutableListOf("inactive = 0")
        if (subject != null) conditions += "subject = '${subject.replace("'", "''")}'"
        if (categories.isNotEmpty()) conditions += "category IN (${quote(categories)})"
        return questionDao.rawForStrings(SimpleSQLiteQuery(
            "SELECT tags_json FROM questions WHERE ${conditions.joinToString(" AND ")}"
        )).let { QuestionTags.distinct(it) }
    }

    /**
     * 标签筛选的两列投影（SQL 条件不含标签，标签在内存里判；判定见 [QuestionTags]）
     */
    private suspend fun tagRows(filter: PracticeFilter): List<QuestionTagRow> =
        questionDao.rawForTagRows(SimpleSQLiteQuery(
            "SELECT question_id, tags_json FROM questions WHERE ${buildWhere(filter)}"
        ))

    /** 题库中已有的题型（按固定顺序返回，便于 UI 稳定排布） */
    suspend fun distinctTypes(): List<String> {
        val existing = questionDao.rawForStrings(SimpleSQLiteQuery(
            "SELECT DISTINCT type FROM questions WHERE inactive = 0"
        )).toSet()
        // 固定顺序，UI 不因题库变化而重排
        return listOf("single_choice", "multiple_choice", "true_false", "fill_in_blank")
            .filter { it in existing }
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

    // 统计（README 四项指标）
    suspend fun firstAttemptAccuracy() = questionDao.firstAttemptAccuracy()
    suspend fun overallAccuracy() = questionDao.overallAccuracy()
    suspend fun multipleChoicePerfectRate() = questionDao.multipleChoicePerfectRate()
    suspend fun averageScoreRate() = questionDao.averageScoreRate()
}
