package com.baiyin.zhilian.data.practice

import com.baiyin.zhilian.data.db.AnswerRecordEntity
import com.baiyin.zhilian.data.db.QuestionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作答落库编排测试（ADR-0013）。
 *
 * 用内存替身（[FakePracticeStore]，有限可变状态：写入能读，**不模拟事务原子性与并发**）验证
 * `submitAnswer` 的编排决策：判首答、读库内当前掌握度（而非传入快照）、按计划写。
 * 「读陈旧快照」（ADR-0004 修过的 bug）的回归钉子在 [submit_answer_prefers_db_mastery_over_snapshot]。
 */
class PracticeRepositoryTest {

    /** 内存替身：写入能读，不模拟事务语义 */
    private class FakePracticeStore : PracticeStore {
        val records = mutableListOf<AnswerRecordEntity>()
        val mastery = mutableMapOf<String, Mastery>()
        var transactionCount = 0

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            transactionCount++
            return block()
        }

        override suspend fun countByQuestion(questionId: String): Int =
            records.count { it.questionId == questionId }

        override suspend fun getMastery(questionId: String): Mastery? = mastery[questionId]

        override suspend fun insertRecord(record: AnswerRecordEntity) {
            records += record
        }

        override suspend fun updateMastery(questionId: String, m: Mastery) {
            mastery[questionId] = m
        }

        override suspend fun deleteAllRecords() {
            records.clear()
        }

        override suspend fun resetAllMastery() {
            mastery.clear()
        }
    }

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
    fun first_attempt_is_marked_and_recorded() = runBlocking {
        val store = FakePracticeStore()
        val repo = PracticeRepository(store, clock = { 42L })
        val summary = repo.submitAnswer(question(), UserAnswer.Single("A"))

        assertTrue(summary.perfect)
        assertTrue(store.records.single().isFirstAttempt)
        assertEquals(42L, store.records.single().answeredAt)
        assertEquals(1, store.transactionCount)
    }

    @Test
    fun second_attempt_is_not_marked_first() = runBlocking {
        val store = FakePracticeStore()
        store.records += AnswerRecordEntity(
            questionId = "q1", type = "single_choice", userAnswerJson = "\"A\"",
            scoreRate = 1.0, isPerfect = true, isFirstAttempt = true, answeredAt = 0L,
        )
        val repo = PracticeRepository(store, clock = { 0L })
        repo.submitAnswer(question(), UserAnswer.Single("B"))

        assertFalse(store.records.last().isFirstAttempt)
    }

    @Test
    fun submit_answer_prefers_db_mastery_over_snapshot() = runBlocking {
        // ADR-0004 回归钉子：库内值 ≠ 传入快照时，必须用库内值（否则基于过期数据转移）
        val store = FakePracticeStore()
        store.mastery["q1"] = Mastery(consecutivePerfect = 5, hasEverWrong = true) // 库内：5,true
        val repo = PracticeRepository(store, clock = { 0L })
        // 传入快照：0,false（与库内不同）
        repo.submitAnswer(question(consecutivePerfect = 0, hasEverWrong = false), UserAnswer.Single("A"))

        // 满分 → 库内 5+1=6；若误用快照会得到 0+1=1
        assertEquals(6, store.mastery["q1"]?.consecutivePerfect)
    }

    @Test
    fun falls_back_to_question_snapshot_when_not_in_db() = runBlocking {
        val store = FakePracticeStore() // 库内无此题
        val repo = PracticeRepository(store, clock = { 0L })
        repo.submitAnswer(question(consecutivePerfect = 3, hasEverWrong = true), UserAnswer.Single("A"))

        // 用传入快照 3,true → 满分 3+1=4
        assertEquals(4, store.mastery["q1"]?.consecutivePerfect)
    }

    @Test
    fun clear_all_history_resets_records_and_mastery() = runBlocking {
        val store = FakePracticeStore()
        store.records += AnswerRecordEntity(
            questionId = "q1", type = "single_choice", userAnswerJson = "\"A\"",
            scoreRate = 1.0, isPerfect = true, isFirstAttempt = true, answeredAt = 0L,
        )
        store.mastery["q1"] = Mastery(2, true)
        val repo = PracticeRepository(store, clock = { 0L })
        repo.clearAllHistory()

        assertTrue(store.records.isEmpty())
        assertTrue(store.mastery.isEmpty())
    }
}
