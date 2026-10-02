package com.baiyin.zhilian.data.practice

import com.baiyin.zhilian.data.db.QuestionEntity

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
 * 作答落库编排（ADR-0013：统计已拆到 [PracticeStats]，本类只管「作答之后」的落库）。
 *
 * **选题不在这里**（见 [QuestionPicker]）；**统计不在这里**（见 [PracticeStats]）：
 * 这个类只管提交与清除两件编排。DAO 依赖收在 [PracticeStore]（生产接 Room、测试接内存替身），
 * 「库内当前值 vs 传入快照」的取值决策收在 [currentMasteryOf]（纯函数，可 JVM 测）。
 *
 * [clock] 是本模块唯一的外部时间源：作答时间由它给出，测试可替换为固定值钉死时间，
 * 事务体内不再内联 `System.currentTimeMillis()`。
 */
class PracticeRepository internal constructor(
    private val store: PracticeStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * 提交作答：单事务内 判首答 → 读库内当前掌握度 → 算落库计划 → 写记录 → 写回掌握度（ADR-0004）。
     *
     * 评分与掌握度转移收口于 [planSubmission]；取值决策收口于 [currentMasteryOf]（ADR-0013）。
     * 调用方只传 (question, answer)，不再自算 scoreRate/isPerfect；返回 [SubmitSummary] 供 UI 反馈。
     */
    suspend fun submitAnswer(question: QuestionEntity, answer: UserAnswer): SubmitSummary =
        store.inTransaction {
            val isFirst = store.countByQuestion(question.questionId) == 0
            val loaded = store.getMastery(question.questionId)
            val currentMastery = currentMasteryOf(loaded, question)
            val plan = planSubmission(
                question = question,
                answer = answer,
                isFirstAttempt = isFirst,
                currentMastery = currentMastery,
                answeredAt = clock(),
            )
            store.insertRecord(plan.record)
            store.updateMastery(question.questionId, plan.newMastery)
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
        store.inTransaction {
            store.deleteAllRecords()
            store.resetAllMastery()
        }
    }
}
