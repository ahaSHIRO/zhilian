package com.baiyin.zhilian.data.question

import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.db.ZhilianDatabase
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
}
