package com.baiyin.zhilian.data.question

import androidx.sqlite.db.SimpleSQLiteQuery
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.db.ZhilianDatabase
import com.baiyin.zhilian.data.db.sqlEscape
import com.baiyin.zhilian.data.db.sqlEscapeAll
import kotlinx.coroutines.flow.Flow

/**
 * 本机题库（CONTEXT.md）：已导入题目及其状态的读写面。
 *
 * 题库页、统计页、练习会话与批次管理页原先直连 `AppContainer.database.<dao>()`，
 * 于是 Room 的 DAO 类型散进四个 UI 文件，屏既无法被替身、也无法单独预览。
 * 此模块把这一面收成单一 seam：调用方只认识题目与计数，DAO 与实体映射留在实现里。
 */
class QuestionBank(private val db: ZhilianDatabase) {

    private val dao = db.questionDao()

    /** 未停用题目（按批次顺序）；题库页列表与练习会话载入共用 */
    fun observeQuestions(): Flow<List<QuestionEntity>> = dao.observeActive()

    /** 题库中已有的分类（题库页筛选 chips） */
    fun observeCategories(): Flow<List<String>> = dao.observeCategories()

    /** 未停用题目数（统计页） */
    fun observeCount(): Flow<Int> = dao.observeActiveCount()

    /** 按 ID 取题（按批次顺序返回）；练习会话载入与疑似重复预览共用 */
    suspend fun get(ids: List<String>): List<QuestionEntity> =
        if (ids.isEmpty()) emptyList() else dao.getByIds(ids)

    /**
     * 单题流：题库详情面板按 id 订阅，而不是抱一份点击瞬间的快照。
     * 抱快照会让面板里的收藏按钮永远读旧值——点了不换文案、再点还是写同一个目标值，
     * 用户根本无法从面板里取消收藏。
     */
    fun observeQuestion(id: String): Flow<QuestionEntity?> = dao.observeById(id)

    /** 收藏开关（题库页） */
    suspend fun setFavorite(id: String, favorite: Boolean) = dao.setFavorite(id, favorite)

    /** 题库中已有的科目（练习配置页科目 chips） */
    suspend fun subjects(): List<String> =
        dao.rawForStrings(SimpleSQLiteQuery(
            "SELECT DISTINCT subject FROM questions WHERE inactive = 0 ORDER BY subject"
        ))

    /**
     * 给定科目下已有的分类（科目为 null 时返回全部）。
     * 分类挂在科目下，故按科目取，避免 Java 与 Kotlin 的同名分类混在一起。
     */
    suspend fun categories(subject: String?): List<String> {
        val where = if (subject == null) {
            "inactive = 0"
        } else {
            "inactive = 0 AND subject = ${sqlEscape(subject)}"
        }
        return dao.rawForStrings(SimpleSQLiteQuery(
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
    suspend fun tags(subject: String?, categories: Set<String>): List<String> {
        val conditions = mutableListOf("inactive = 0")
        if (subject != null) conditions += "subject = ${sqlEscape(subject)}"
        if (categories.isNotEmpty()) conditions += "category IN (${sqlEscapeAll(categories)})"
        return dao.rawForStrings(SimpleSQLiteQuery(
            "SELECT tags_json FROM questions WHERE ${conditions.joinToString(" AND ")}"
        )).let { QuestionTags.distinct(it) }
    }

    /** 题库中已有的题型（按固定顺序返回，便于 UI 稳定排布） */
    suspend fun types(): List<String> {
        val existing = dao.rawForStrings(SimpleSQLiteQuery(
            "SELECT DISTINCT type FROM questions WHERE inactive = 0"
        )).toSet()
        // 固定顺序，UI 不因题库变化而重排
        return listOf("single_choice", "multiple_choice", "true_false", "fill_in_blank")
            .filter { it in existing }
    }
}
