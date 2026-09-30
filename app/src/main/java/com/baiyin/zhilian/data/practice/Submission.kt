package com.baiyin.zhilian.data.practice

import com.baiyin.zhilian.data.db.AnswerRecordEntity
import com.baiyin.zhilian.data.db.QuestionEntity

/**
 * 一次作答提交的落库计划（ADR-0004）。
 *
 * `submitAnswer` 原先把「判分 → 判首答 → 掌握度转移 → 构造记录」四步散在事务体里，
 * 与读写混作一处，规则无法单测。收口成纯函数：事务只负责读事实、按计划写，
 * 正确性规则在此一处，接口即测试面。
 *
 * 时钟不在此处产生——[answeredAt] 由调用方（事务边界）传入，与
 * [Scoring.toRecord] 的取舍一致，测试可钉死时间。
 */
data class SubmissionPlan(
    val record: AnswerRecordEntity,
    val newMastery: Mastery,
    val summary: SubmitSummary,
)

/**
 * 由「题目 + 作答 + 库内既有事实」算出落库计划。
 *
 * @param isFirstAttempt 调用方查询后的判首答结果（首答以落库时写定为准，见 ADR-0004）
 * @param currentMastery 事务内读到的库内当前掌握度，避免陈旧快照
 */
fun planSubmission(
    question: QuestionEntity,
    answer: UserAnswer,
    isFirstAttempt: Boolean,
    currentMastery: Mastery,
    answeredAt: Long,
): SubmissionPlan {
    val (rate, perfect) = Scoring.score(question, answer)
    return SubmissionPlan(
        record = Scoring.toRecord(
            question = question,
            answer = answer,
            scoreRate = rate,
            isPerfect = perfect,
            isFirstAttempt = isFirstAttempt,
            answeredAt = answeredAt,
        ),
        newMastery = masteryTransition(currentMastery, Scoring.outcomeOf(rate, perfect)),
        summary = SubmitSummary(rate, perfect),
    )
}
