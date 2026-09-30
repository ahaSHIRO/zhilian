package com.baiyin.zhilian.data.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 练习会话单测：会话得分口径（CONTEXT.md「会话得分」）、可提交性、反馈分级。
 *
 * 得分口径原先内联在 pager 的调用点上、无从单测；反馈分级在题卡横幅与解析面板各抄一遍。
 */
class PracticeSessionTest {

    @Test
    fun summary_folds_score_rates_over_the_whole_session() {
        // 4 题，答了 3 题（1 满分 / 1 半对 / 1 零分），跳过 1 题按 0 分计
        val summary = PracticeSession.summary(
            results = listOf(
                SubmitSummary(1.0, true),
                SubmitSummary(0.5, false),
                SubmitSummary(0.0, false),
            ),
            questionCount = 4,
            skippedCount = 1,
        )
        assertEquals(3, summary.answered)
        assertEquals(1, summary.perfect)
        assertEquals(1, summary.skipped)
        assertEquals(38, summary.score) // (1.0 + 0.5 + 0.0) / 4 × 100 = 37.5 → 38
    }

    @Test
    fun summary_of_an_empty_session_is_zero() {
        assertEquals(0, PracticeSession.summary(emptyList(), questionCount = 0, skippedCount = 0).score)
    }

    @Test
    fun an_all_correct_session_scores_one_hundred() {
        val summary = PracticeSession.summary(listOf(SubmitSummary(1.0, true), SubmitSummary(1.0, true)), 2, 0)
        assertEquals(100, summary.score)
        assertEquals(2, summary.perfect)
    }

    @Test
    fun skipped_questions_drag_the_score_down_by_zero_filling() {
        // 跳过计 0 分：两题全对但只答一题，得分应减半
        val summary = PracticeSession.summary(listOf(SubmitSummary(1.0, true)), questionCount = 2, skippedCount = 1)
        assertEquals(50, summary.score)
    }

    @Test
    fun can_submit_requires_an_answer() {
        assertFalse(PracticeSession.canSubmit("single_choice", null))
        assertTrue(PracticeSession.canSubmit("single_choice", UserAnswer.Single("A")))
    }

    @Test
    fun can_submit_requires_at_least_one_option_for_multiple_choice() {
        assertFalse(PracticeSession.canSubmit("multiple_choice", UserAnswer.Multiple(emptySet())))
        assertTrue(PracticeSession.canSubmit("multiple_choice", UserAnswer.Multiple(setOf("A"))))
    }

    @Test
    fun feedback_grades_into_three_levels() {
        assertEquals(SessionFeedback.Perfect, PracticeSession.feedbackOf(SubmitSummary(1.0, true)))
        assertEquals(SessionFeedback.Partial, PracticeSession.feedbackOf(SubmitSummary(0.5, false)))
        assertEquals(SessionFeedback.Wrong, PracticeSession.feedbackOf(SubmitSummary(0.0, false)))
    }

    @Test
    fun first_unanswered_index_points_at_the_first_gap() {
        assertEquals(1, PracticeSession.firstUnansweredIndex(setOf(0, 2), questionCount = 4))
        assertEquals(0, PracticeSession.firstUnansweredIndex(emptySet(), questionCount = 3))
        assertNull(PracticeSession.firstUnansweredIndex(setOf(0, 1, 2), questionCount = 3))
    }
}
