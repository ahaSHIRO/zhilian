package com.baiyin.zhilian.data.batch

import com.baiyin.zhilian.data.db.PendingDuplicateEntity
import com.baiyin.zhilian.data.db.ProcessedBatchEntity
import com.baiyin.zhilian.data.db.QuestionEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对账编排测试（ADR-0013 的内存替身手法）。
 *
 * 钉死三件事：① 两阶段——停用永远在写题之后（跨批次停用才成立）；② 幂等——重跑无变更；
 * ③ 撤销批次导入的级联范围。
 */
class BatchReconcileServiceTest {

    /** 内存替身：写入能读，不模拟事务原子性 */
    private class FakeReconcileStore : ReconcileStore {
        val questions = mutableListOf<QuestionEntity>()
        val records = mutableMapOf<String, ProcessedBatchEntity>()
        val pending = mutableListOf<PendingDuplicateEntity>()
        val inactiveIds = mutableSetOf<String>()
        val answerIds = mutableSetOf<String>()

        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()

        override suspend fun allQuestions(): List<QuestionEntity> = questions.toList()

        override suspend fun allBatchRecords(): List<ProcessedBatchEntity> = records.values.toList()

        override suspend fun insertQuestions(questions: List<QuestionEntity>) {
            this.questions += questions
        }

        override suspend fun updateQuestions(questions: List<QuestionEntity>) {
            questions.forEach { updated ->
                val i = this.questions.indexOfFirst { it.questionId == updated.questionId }
                if (i >= 0) {
                    // 与真实 DAO `updateContent` 同口径：只覆盖内容列，本地状态列随 questionId 保留。
                    // 替身曾直接整行替换（等同误用 `@Update`）——那会把 ADR-0017
                    // 「作答历史与掌握度不被覆盖」的核心声明在测试里建模成相反语义。
                    val prior = this.questions[i]
                    this.questions[i] = updated.copy(
                        inactive = prior.inactive,
                        consecutivePerfect = prior.consecutivePerfect,
                        hasEverWrong = prior.hasEverWrong,
                        favorite = prior.favorite,
                        importedAt = prior.importedAt,
                    )
                }
            }
        }

        override suspend fun markInactive(ids: List<String>) {
            // 与真实 DAO 同口径：停用要落到题上，下轮对账才能识别「已是停用态」
            ids.forEach { id ->
                val i = questions.indexOfFirst { it.questionId == id }
                if (i >= 0) questions[i] = questions[i].copy(inactive = true)
            }
            inactiveIds += ids
        }

        override suspend fun insertPendingDuplicates(items: List<PendingDuplicateEntity>) {
            items.forEach { item ->
                if (pending.none { it.batchId == item.batchId && it.questionId == item.questionId }) {
                    pending += item
                }
            }
        }

        override suspend fun upsertBatchRecords(records: List<ProcessedBatchEntity>) {
            records.forEach { this.records[it.batchId] = it }
        }

        override suspend fun questionIdsByBatch(batchId: String): List<String> =
            questions.filter { it.batchId == batchId }.map { it.questionId }

        override suspend fun deleteQuestionsByBatch(batchId: String) {
            questions.removeAll { it.batchId == batchId }
        }

        override suspend fun deleteAnswerRecords(questionIds: List<String>) {
            answerIds -= questionIds.toSet()
        }

        override suspend fun deletePendingDuplicatesByBatch(batchId: String) {
            pending.removeAll { it.batchId == batchId }
        }

        override suspend fun deleteBatchRecord(batchId: String) {
            records.remove(batchId)
        }
    }

    private fun service(store: FakeReconcileStore) = BatchReconcileService(
        store = store,
        schemaValidator = BatchSchemaValidator("""{"type":"object"}"""), // 最小 schema（测试用）
        clock = { 0L },
    )

    private fun batchJson(
        batchId: String = "b1",
        batchOrder: Int = 1,
        stem: String = "题干",
        questionId: String = "q1",
        retired: List<String> = emptyList(),
    ): String = """
        {
          "formatVersion": 1,
          "batchId": "$batchId",
          "batchOrder": $batchOrder,
          "createdAt": "2026-01-01",
          "subject": "kotlin",
          "questions": [
            {
              "questionId": "$questionId",
              "type": "single_choice",
              "category": "协程",
              "tags": [],
              "stem": "$stem",
              "options": [{"optionId":"A","text":"甲"},{"optionId":"B","text":"乙"}],
              "answer": "A",
              "explanation": "解析",
              "source": {"title":"文档","accessedDate":"2026-01-01"}
            }
          ],
          "retiredQuestionIds": [${retired.joinToString(",") { "\"$it\"" }}]
        }
    """.trimIndent()

    private fun source(name: String = "b.json", json: String = batchJson()) = BatchSource(name, json)

    @Test
    fun `reconcile 插入新题并记录指纹`() = runBlocking {
        val store = FakeReconcileStore()
        val digest = service(store).reconcile(listOf(source()))

        assertEquals(1, digest.inserted)
        assertEquals(1, store.questions.size)
        assertEquals("b1", store.questions.first().batchId)
        assertEquals(1, store.records.size)
        assertTrue(store.records.getValue("b1").contentHash.isNotEmpty())
    }

    @Test
    fun `两阶段——先写题后统一停用，跨批次停用成立`() = runBlocking {
        val store = FakeReconcileStore()
        // 低序批次新增 newbie；高序批次停用它（batch-0018 场景）
        service(store).reconcile(
            listOf(
                source("low.json", batchJson(batchId = "bLow", batchOrder = 11, questionId = "newbie")),
                source("high.json", batchJson(batchId = "bHigh", batchOrder = 18, questionId = "other", retired = listOf("newbie"))),
            )
        )
        assertTrue(store.questions.any { it.questionId == "newbie" })
        assertTrue("跨批次停用必须在写题之后生效", "newbie" in store.inactiveIds)
    }

    @Test
    fun `同 ID 更新不清本地状态——历史与掌握度随 questionId 保留`() = runBlocking {
        val store = FakeReconcileStore()
        val svc = service(store)
        svc.reconcile(listOf(source("v1.json", batchJson(stem = "旧题干"))))
        // 本机产生学习痕迹
        store.questions[0] = store.questions[0].copy(
            consecutivePerfect = 2, hasEverWrong = true, favorite = true, importedAt = 123L,
        )

        svc.reconcile(listOf(source("v2.json", batchJson(stem = "新题干"))))

        val q = store.questions.single()
        assertEquals("新题干", q.stem)                 // 内容按文件刷新
        assertEquals(2, q.consecutivePerfect)   // ↓ 本地状态不被覆盖
        assertTrue(q.hasEverWrong)
        assertTrue(q.favorite)
        assertEquals(123L, q.importedAt)
    }

    @Test
    fun `撤销后重导——别处声明的停用照样补回`() = runBlocking {
        // 回归：停用声明在 high 批，题属 low 批。撤销 low 后重导，high 指纹未变会被跳过，
        // 若只重放变化批次，那道题就回到可用态（与 batch-0018 事故同类的漏停用）。
        val store = FakeReconcileStore()
        val svc = service(store)
        val low = source("low.json", batchJson(batchId = "bLow", batchOrder = 11, questionId = "newbie"))
        val high = source(
            "high.json",
            batchJson(batchId = "bHigh", batchOrder = 18, questionId = "other", retired = listOf("newbie")),
        )
        svc.reconcile(listOf(low, high))
        assertTrue("newbie" in store.inactiveIds)

        svc.undoBatch("bLow")
        val digest = svc.reconcile(listOf(low, high))

        assertEquals(1, digest.inserted)
        assertEquals("跳过的 high 批仍应贡献停用", 1, digest.retired)
        assertTrue(store.questions.first { it.questionId == "newbie" }.inactive)
    }

    @Test
    fun `重复对账幂等——第二次无写入`() = runBlocking {
        val store = FakeReconcileStore()
        val svc = service(store)
        svc.reconcile(listOf(source()))
        val second = svc.reconcile(listOf(source()))

        assertEquals(0, second.inserted)
        assertEquals(0, second.updated)
        assertEquals(1, second.unchangedBatches)
        assertEquals(1, store.questions.size)
    }

    @Test
    fun `同 ID 内容变化原位更新`() = runBlocking {
        val store = FakeReconcileStore()
        val svc = service(store)
        svc.reconcile(listOf(source("v1.json", batchJson(stem = "旧题干"))))
        val digest = svc.reconcile(listOf(source("v2.json", batchJson(stem = "新题干"))))

        assertEquals(1, digest.updated)
        assertEquals("新题干", store.questions.single().stem)
        assertEquals(1, store.questions.size) // 未新增
    }

    @Test
    fun `撤销批次导入级联清除题目与记录`() = runBlocking {
        val store = FakeReconcileStore()
        val svc = service(store)
        svc.reconcile(listOf(source()))
        store.answerIds += "q1"

        svc.undoBatch("b1")

        assertTrue(store.questions.isEmpty())
        assertTrue(store.records.isEmpty())
        assertTrue(store.answerIds.isEmpty())
    }

    @Test
    fun `撤销后重新对账可再次导入`() = runBlocking {
        val store = FakeReconcileStore()
        val svc = service(store)
        svc.reconcile(listOf(source()))
        svc.undoBatch("b1")
        val digest = svc.reconcile(listOf(source()))

        assertEquals(1, digest.inserted)
        assertEquals(1, store.questions.size)
    }
}