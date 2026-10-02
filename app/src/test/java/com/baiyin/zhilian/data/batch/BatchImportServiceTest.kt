package com.baiyin.zhilian.data.batch

import com.baiyin.zhilian.data.db.PendingDuplicateEntity
import com.baiyin.zhilian.data.db.ProcessedBatchEntity
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.db.StemOwnerRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次导入编排测试（ADR-0013）。
 *
 * 用内存替身（[FakeImportStore]）验证 `importFromText` 的编排决策：组装快照、按计划写、映射结果。
 * SAF 文件读取不在测试面（已留调用方），真实 Room 事务语义靠端到端。
 */
class BatchImportServiceTest {

    /** 内存替身：写入能读，不模拟事务语义 */
    private class FakeImportStore : ImportStore {
        val questions = mutableListOf<QuestionEntity>()
        val inactiveIds = mutableSetOf<String>()
        val pending = mutableListOf<PendingDuplicateEntity>()
        val batches = mutableMapOf<String, ProcessedBatchEntity>()
        var existingIds: List<String> = emptyList()
        var stemOwners: List<StemOwnerRow> = emptyList()
        var orderTaken: Boolean = false
        var transactionCount = 0

        override suspend fun <T> inTransaction(block: suspend () -> T): T {
            transactionCount++
            return block()
        }

        override suspend fun findExistingIds(ids: List<String>): List<String> = existingIds

        override suspend fun findStemOwners(stems: List<String>): List<StemOwnerRow> = stemOwners

        override suspend fun isOrderTaken(batchOrder: Int): Boolean = orderTaken

        override suspend fun insertQuestions(q: List<QuestionEntity>) {
            questions += q
        }

        override suspend fun markInactive(ids: List<String>) {
            inactiveIds += ids
        }

        override suspend fun insertPendingDuplicates(items: List<PendingDuplicateEntity>) {
            pending += items
        }

        override suspend fun upsertBatch(record: ProcessedBatchEntity) {
            batches[record.batchId] = record
        }
    }

    // 构造一个最小合法批次 JSON（Schema 通过、应用级通过）
    private fun batchJson(): String = """{
      "formatVersion": 1,
      "batchId": "6f1a2b3c-4d5e-4f60-8a1b-2c3d4e5f6001",
      "batchOrder": 9001,
      "createdAt": "2026-10-02",
      "subject": "kotlin",
      "questions": [
        {
          "questionId": "6f1a2b3c-4d5e-4f60-8a1b-2c3d4e5f600a",
          "type": "single_choice",
          "category": "测试",
          "tags": [],
          "stem": "题干",
          "options": [
            { "optionId": "A", "text": "甲" },
            { "optionId": "B", "text": "乙" }
          ],
          "answer": "A",
          "explanation": "解析",
          "source": { "title": "文档", "url": "https://example.com", "accessedDate": "2026-10-02" }
        }
      ],
      "retiredQuestionIds": []
    }"""

    private fun service(store: FakeImportStore) = BatchImportService(
        importStore = store,
        schemaValidator = BatchSchemaValidator("""{"type":"object"}"""), // 最小 schema（测试用）
        db = null,
        clock = { 0L },
    )

    @Test
    fun successful_import_inserts_and_maps_outcome() = runBlocking {
        val store = FakeImportStore()
        val outcome = service(store).importFromText(batchJson(), "test.json")

        assertTrue(outcome is ImportOutcome.Completed)
        outcome as ImportOutcome.Completed
        assertEquals(1, outcome.importedCount)
        assertEquals(ImportPlan.STATUS_IMPORTED, outcome.status)
        assertEquals(1, store.questions.size)
        assertEquals(1, store.batches.size)
        assertEquals(1, store.transactionCount)
    }

    @Test
    fun schema_failure_returns_failed_without_write() = runBlocking {
        val store = FakeImportStore()
        val outcome = service(store).importFromText("not json", "test.json")

        assertTrue(outcome is ImportOutcome.Failed)
        assertTrue(store.questions.isEmpty())
        assertTrue(store.batches.isEmpty())
    }

    @Test
    fun duplicate_stem_creates_pending_and_partial() = runBlocking {
        val store = FakeImportStore()
        store.stemOwners = listOf(StemOwnerRow("existing-id", "题干")) // 库内已有同题干
        val outcome = service(store).importFromText(batchJson(), "test.json")

        assertTrue(outcome is ImportOutcome.Completed)
        outcome as ImportOutcome.Completed
        assertEquals(ImportPlan.STATUS_PARTIAL, outcome.status)
        assertEquals(1, store.pending.size)
        assertTrue(store.questions.isEmpty()) // 未插入（待决）
    }

    @Test
    fun retired_ids_are_marked_inactive() = runBlocking {
        val store = FakeImportStore()
        store.existingIds = listOf("old-id") // 库内有 old-id
        val json = batchJson().replace("\"retiredQuestionIds\": []", "\"retiredQuestionIds\": [\"old-id\"]")
        val outcome = service(store).importFromText(json, "test.json")

        assertTrue(outcome is ImportOutcome.Completed)
        outcome as ImportOutcome.Completed
        assertEquals(1, outcome.retiredCount)
        assertTrue("old-id" in store.inactiveIds)
    }

    @Test
    fun validate_batch_rejection_returns_failed_without_write() = runBlocking {
        val store = FakeImportStore()
        store.orderTaken = true // batchOrder 已被占用 → validateBatch 拒绝
        val outcome = service(store).importFromText(batchJson(), "test.json")

        assertTrue(outcome is ImportOutcome.Failed)
        assertTrue(store.questions.isEmpty())
        assertTrue(store.batches.isEmpty())
    }
}
