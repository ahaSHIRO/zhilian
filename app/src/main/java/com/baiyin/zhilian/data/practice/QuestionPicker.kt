package com.baiyin.zhilian.data.practice

import androidx.sqlite.db.SimpleSQLiteQuery
import com.baiyin.zhilian.data.db.QuestionDao
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.db.QuestionTagRow
import com.baiyin.zhilian.data.db.sqlEscapeAll
import com.baiyin.zhilian.data.question.QuestionTags

/**
 * 错题判定（CONTEXT.md「错题」：有错史且未被连续两次全对消解）。
 * 选题条件与统计弱项共用这**一处**片段，勿两处漂移。
 */
internal const val SQL_WRONG = "has_ever_wrong = 1 AND consecutive_perfect < ${QuestionEntity.WRONG_THRESHOLD}"

/**
 * 选题条件的 SQL 拼装：纯函数，故「条件对不对」不必插设备就能问。
 *
 * 以前这些字符串散在仓里与调用点之间，同名条件出现多份，标签路径漏排序就是这么来的。
 */
internal object PracticeSql {

    /** SQL 字符串常量转义（值来自本机题库 DISTINCT，常量级注入面）；转义规则见 [sqlEscapeAll] */
    fun quote(values: Set<String>): String = sqlEscapeAll(values)

    /** WHERE 子句：维度间取交集（AND），维度内多选取并集（IN） */
    fun where(filter: PracticeFilter): String = buildList {
        add("inactive = 0")
        if (filter.subjects.isNotEmpty()) add("subject IN (${quote(filter.subjects)})")
        if (filter.categories.isNotEmpty()) add("category IN (${quote(filter.categories)})")
        if (filter.types.isNotEmpty()) add("type IN (${quote(filter.types)})")
        if (filter.onlyWrong) add(SQL_WRONG)
        if (filter.onlyFavorite) add("favorite = 1")
    }.joinToString(" AND ")

    fun orderBy(sequential: Boolean): String =
        if (sequential) "batch_order, order_in_batch" else "RANDOM()"

    /** 错题条件的完整 WHERE（inactive = 0 AND 错题规则）：统计与选题共用此入口 */
    fun wrongClause(): String = "inactive = 0 AND $SQL_WRONG"
}

/**
 * 选题（C7）：把「出哪些题」的四个入口收成一处——选题 / 总数 / 错题数 / 收藏数，
 * 外加配置页需要的可选值列表（科目 / 分类 / 标签 / 题型）。
 *
 * 四条投影共用同一份 WHERE 与同一份排序，标签路径也不例外：
 * **标签的 SQL 必须自带 ORDER BY**——标签存在 JSON 数组列里、判定只能在内存做，
 * 取投影时若漏掉排序，拿到的是行存储顺序，「顺序练习」会退化成「按导入先后刷」。
 */
class QuestionPicker(private val questionDao: QuestionDao) {

    suspend fun pick(filter: PracticeFilter): List<QuestionEntity> {
        // 未选标签时走 SQL 直接 LIMIT，不为低频路径付出全表扫描的代价
        if (filter.tags.isEmpty()) {
            val sql = "SELECT * FROM questions WHERE ${PracticeSql.where(filter)} " +
                "ORDER BY ${PracticeSql.orderBy(filter.sequential)} LIMIT ${filter.limit}"
            return questionDao.rawForPractice(SimpleSQLiteQuery(sql))
        }
        val picked = tagRows(filter).filter { QuestionTags.matches(it.tagsJson, filter.tags) }
        val ids = (if (filter.sequential) picked else picked.shuffled())
            .take(filter.limit).map { it.questionId }
        // getByIds 按批次序返回，随机模式下须按打乱后的顺序重排，否则一律退化为顺序
        val byId = questionDao.getByIds(ids).associateBy { it.questionId }
        return ids.mapNotNull { byId[it] }
    }

    /** 符合条件的题目总数（不受 limit 截断；练习配置页预览用） */
    suspend fun countMatching(filter: PracticeFilter): Int =
        if (filter.tags.isEmpty()) {
            questionDao.countRaw(
                SimpleSQLiteQuery("SELECT COUNT(*) FROM questions WHERE ${PracticeSql.where(filter)}")
            )
        } else {
            tagRows(filter).count { QuestionTags.matches(it.tagsJson, filter.tags) }
        }

    /**
     * 范围 chip 上的预判计数：假设只打开该范围、其余条件不变时能刷出几道。
     * 让「错题」从盲开关变成可判断的入口——否则只能勾上之后看总数才知道值不值得刷。
     */
    suspend fun wrongCount(filter: PracticeFilter): Int =
        countMatching(filter.copy(onlyWrong = true))

    suspend fun favoriteCount(filter: PracticeFilter): Int =
        countMatching(filter.copy(onlyFavorite = true))

    /**
     * 标签筛选的两列投影（SQL 条件不含标签，标签在内存里判；判定见 [QuestionTags]）。
     * ORDER BY 必须在这里给出：内存筛选取的是这个投影的**顺序**。
     */
    private suspend fun tagRows(filter: PracticeFilter): List<QuestionTagRow> =
        questionDao.rawForTagRows(SimpleSQLiteQuery(
            "SELECT question_id, tags_json FROM questions WHERE ${PracticeSql.where(filter)} " +
                "ORDER BY batch_order, order_in_batch"
        ))
}
