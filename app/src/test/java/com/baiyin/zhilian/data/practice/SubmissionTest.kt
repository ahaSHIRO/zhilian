package com.baiyin.zhilian.data.practice

import com.baiyin.zhilian.data.db.QuestionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作答落库计划单测（ADR-0004）。
 *
 * submitAnswer 的原子性靠真机端到端验证，但「判分 → 转移 → 记录」这三步的规则
 * 现在是可以直接问的纯函数——这里把三种结果的转移与记录字段钉死，
 * 事务里就只剩读事实与写。
 */
class SubmissionTest {

    private fun question(
        questionId: String = "q1",
        type: String = "single_choice",
        answerJson: String = "\"A\"",
        optionsJson: String? = """[{"optionId":"A","text":"甲"},{"optionId":"B","text":"乙"}]""",
        consecutivePerfect: Int = 0,
        hasEverWrong: Boolean = false,
    ) = QuestionEntity(
        questionId = questionId,
        type = type,
        subject = "kotlin",
        category = "协程",
        tagsJson = "[]",
        stem = "题干",
        optionsJson = optionsJson,
        answerJson = answerJson,
        explanation = "解析",
        sourceJson = """{"title":"文档","accessedDate":"2026-01-01"}""",
        batchOrder = 1,
        orderInBatch = 0,
        importedAt = 0L,
        consecutivePerfect = consecutivePerfect,
        hasEverWrong = hasEverWrong,
    )

    @Test
    fun perfect_answer_advances_streak_and_records_the_attempt() {
        val plan = planSubmission(
            question = question(consecutivePerfect = 1, hasEverWrong = true),
            answer = UserAnswer.Single("A"),
            isFirstAttempt = true,
            currentMastery = Mastery(1, true),
            answeredAt = 42L,
        )

        assertEquals(1.0, plan.summary.rate, 1e-9)
        assertTrue(plan.summary.perfect)
        assertEquals(2, plan.newMastery.consecutivePerfect)
        assertTrue(plan.newMastery.hasEverWrong) // 一旦为真不再回落

        assertEquals("q1", plan.record.questionId)
        assertEquals("\"A\"", plan.record.userAnswerJson)
        assertTrue(plan.record.isFirstAttempt)
        assertEquals(42L, plan.record.answeredAt)
    }

    @Test
    fun wrong_answer_resets_streak_and_marks_ever_wrong() {
        val plan = planSubmission(
            question = question(),
            answer = UserAnswer.Single("B"),
            isFirstAttempt = false,
            currentMastery = Mastery(2, false),
            answeredAt = 1L,
        )

        assertEquals(0.0, plan.summary.rate, 1e-9)
        assertFalse(plan.summary.perfect)
        assertEquals(0, plan.newMastery.consecutivePerfect)
        assertTrue(plan.newMastery.hasEverWrong)
        assertFalse(plan.record.isFirstAttempt)
    }

    @Test
    fun partial_multiple_choice_keeps_streak_but_counts_as_not_perfect() {
        // ADR-0004 裁决：部分得分保留 consecutive，但仍算未对（计入错题）
        val plan = planSubmission(
            question = question(
                type = "multiple_choice",
                answerJson = """["A","C"]""",
                optionsJson = """[{"optionId":"A","text":"甲"},{"optionId":"B","text":"乙"},{"optionId":"C","text":"丙"}]""",
            ),
            answer = UserAnswer.Multiple(setOf("A")),
            isFirstAttempt = true,
            currentMastery = Mastery(3, false),
            answeredAt = 0L,
        )

        assertEquals(0.5, plan.summary.rate, 1e-9) // (1 对 - 0 错) / 2 正确项
        assertFalse(plan.summary.perfect)
        assertEquals(3, plan.newMastery.consecutivePerfect) // 不清零
        assertTrue(plan.newMastery.hasEverWrong)
    }

    @Test
    fun clock_is_taken_from_the_caller_not_from_the_system() {
        // submitAnswer 不再内联 System.currentTimeMillis()：时间由事务边界传入，测试可钉死
        val plan = planSubmission(
            question = question(),
            answer = UserAnswer.Single("A"),
            isFirstAttempt = true,
            currentMastery = Mastery(0, false),
            answeredAt = 987654321L,
        )
        assertEquals(987654321L, plan.record.answeredAt)
    }

    // ---- currentMasteryOf（ADR-0013）----

    @Test
    fun current_mastery_prefers_loaded_over_snapshot() {
        val loaded = Mastery(5, true)
        val q = question(consecutivePerfect = 0, hasEverWrong = false)
        val result = currentMasteryOf(loaded, q)
        assertEquals(5, result.consecutivePerfect)
        assertTrue(result.hasEverWrong)
    }

    @Test
    fun current_mastery_falls_back_to_question_snapshot() {
        val q = question(consecutivePerfect = 3, hasEverWrong = true)
        val result = currentMasteryOf(null, q)
        assertEquals(3, result.consecutivePerfect)
        assertTrue(result.hasEverWrong)
    }

    @Test
    fun current_mastery_prefers_db_even_when_snapshot_differs() {
        // ADR-0004 回归钉子：库内值 ≠ 快照时，必须用库内值（否则基于过期数据转移）
        val loaded = Mastery(5, true)
        val q = question(consecutivePerfect = 0, hasEverWrong = false)
        val result = currentMasteryOf(loaded, q)
        assertEquals(5, result.consecutivePerfect) // 不是 0
    }
}
