package com.baiyin.zhilian.data.practice

import kotlin.math.roundToInt

/** 会话小结（CONTEXT.md「会话得分」） */
data class SessionSummary(
    val answered: Int,
    val perfect: Int,
    val skipped: Int,
    val score: Int,
)

/** 提交后的即时反馈分级 */
enum class SessionFeedback { Perfect, Partial, Wrong }

/**
 * 练习会话（CONTEXT.md）：逐题作答状态 → 会话小结与即时反馈。
 *
 * 纯模块，无 Compose/Android 依赖。会话得分口径（满分 100 按会话题数均分、
 * 各题按得分率折算、跳过计 0 分）与反馈分级在此各写一次——原先前者内联在
 * HorizontalPager 的调用点上，后者在题卡横幅与解析面板各抄一遍。
 */
object PracticeSession {

    /** 会话得分与小结，口径见 CONTEXT.md「会话得分」 */
    fun summary(
        results: Collection<SubmitSummary>,
        questionCount: Int,
        skippedCount: Int,
    ): SessionSummary = SessionSummary(
        answered = results.size,
        perfect = results.count { it.perfect },
        skipped = skippedCount,
        score = if (questionCount <= 0) {
            0
        } else {
            (results.sumOf { it.rate } / questionCount * 100).roundToInt()
        },
    )

    /**
     * 提交按钮可用性：有作答即可提交；多选至少选一项。
     * 提交进行中的防重入属 UI 瞬时态，不在此判定（ADR-0004）。
     */
    fun canSubmit(type: String, answer: UserAnswer?): Boolean = when {
        answer == null -> false
        type == "multiple_choice" ->
            (answer as? UserAnswer.Multiple)?.optionIds?.isNotEmpty() == true
        else -> true
    }

    /** 反馈分级：满分 / 部分得分 / 零分 */
    fun feedbackOf(result: SubmitSummary): SessionFeedback = when {
        result.perfect -> SessionFeedback.Perfect
        result.rate > 0.0 -> SessionFeedback.Partial
        else -> SessionFeedback.Wrong
    }

    /** 第一道未提交的题索引（结尾卡「跳回未答」）；全答完为 null */
    fun firstUnansweredIndex(submittedIndices: Set<Int>, questionCount: Int): Int? =
        (0 until questionCount).firstOrNull { it !in submittedIndices }
}
