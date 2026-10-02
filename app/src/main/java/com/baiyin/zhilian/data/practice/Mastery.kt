package com.baiyin.zhilian.data.practice

import com.baiyin.zhilian.data.db.QuestionEntity

/**
 * 掌握度：某题的连续全对次数与历史错误标记（错题推导依据，见 CONTEXT.md）。
 * 不可变值对象；转移由 [masteryTransition] 纯函数产出新值，调用方不就地修改。
 */
data class Mastery(
    val consecutivePerfect: Int,
    val hasEverWrong: Boolean,
)

/**
 * 作答结果分类（错题消解规则的三态；跳过不进转移）。
 *
 * - [Perfect] 满分：连续全对 +1
 * - [Wrong] 零分错误：连续全对归零
 * - [Partial] 多选部分得分（0 < rate < 1）：仍算未对（计入错题），但保留当前连续全对计数不清零
 *
 * 跳过不产生作答记录，不进转移，故无对应枚举值。
 */
enum class AnswerOutcome { Perfect, Wrong, Partial }

/**
 * 掌握度转移纯函数（CONTEXT.md 错题/连续全对次数 + ADR-0004）。
 *
 * 规则表：
 * - Perfect → consecutivePerfect + 1；hasEverWrong 保持
 * - Wrong   → consecutivePerfect 归零；hasEverWrong = true
 * - Partial → consecutivePerfect 不变（保留已积累的连续全对）；hasEverWrong = true
 *
 * hasEverWrong 维度：满分保持原值，非满分（Wrong/Partial）置 true
 * （"部分得分仍算未对"，错题定义见 CONTEXT.md）。
 */
fun masteryTransition(current: Mastery, outcome: AnswerOutcome): Mastery = when (outcome) {
    AnswerOutcome.Perfect -> Mastery(
        consecutivePerfect = current.consecutivePerfect + 1,
        hasEverWrong = current.hasEverWrong,
    )
    AnswerOutcome.Wrong -> Mastery(
        consecutivePerfect = 0,
        hasEverWrong = true,
    )
    AnswerOutcome.Partial -> Mastery(
        consecutivePerfect = current.consecutivePerfect, // 不变：保留已积累的连续全对
        hasEverWrong = true, // 部分得分仍算未对，计入错题
    )
}

/**
 * 取当前掌握度（ADR-0013）：库内当前值优先，库内无此题时兜底用传入快照。
 *
 * ADR-0004 修过的「读陈旧实体快照」bug 的**取值决策**就是这里——用传入 `question`
 * 的快照而非库内当前值，会让掌握度转移基于过期数据。收成纯函数后可直接 JVM 测。
 */
internal fun currentMasteryOf(loaded: Mastery?, question: QuestionEntity): Mastery =
    loaded ?: Mastery(question.consecutivePerfect, question.hasEverWrong)
