package com.baiyin.zhilian.data.batch

import com.baiyin.zhilian.data.db.ProcessedBatchEntity
import com.baiyin.zhilian.data.db.QuestionEntity
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录级对账规划单测（ADR-0017）：指纹跳过、同 ID 更新、两阶段停用、顺序号冲突、疑似重复。
 *
 * 这是「电脑端改题 → 手机自动生效」的核心规则面；停用并集那一测专门钉死 batch-0018
 * 那类「跨批次停用因导入顺序丢失」的问题（先写题、后统一停用后不再发生）。
 */
class BatchReconcilePlannerTest {

    private fun q(
        id: String = "q1",
        stem: String = "题干",
        answer: kotlinx.serialization.json.JsonElement = JsonPrimitive("A"),
    ) = QuestionDto(
        questionId = id,
        type = "single_choice",
        category = "协程",
        tags = emptyList(),
        stem = stem,
        options = listOf(OptionDto("A", "甲"), OptionDto("B", "乙")),
        answer = answer,
        explanation = "解析",
        source = SourceDto(title = "文档", accessedDate = "2026-01-01"),
    )

    private fun batch(
        questions: List<QuestionDto> = listOf(q()),
        retired: List<String> = emptyList(),
        batchId: String = "b1",
        batchOrder: Int = 1,
    ) = BatchFileDto(
        formatVersion = 1,
        batchId = batchId,
        batchOrder = batchOrder,
        createdAt = "2026-01-01",
        subject = "kotlin",
        questions = questions,
        retiredQuestionIds = retired,
    )

    private fun candidate(batch: BatchFileDto, fileName: String = "b.json") =
        BatchCandidate(fileName, batch, BatchFingerprint.of(batch))

    /** 用同一 DTO 的映射产物当「库内现状」，保证内容列逐字相等 */
    private fun existingFrom(q: QuestionDto, batchId: String = "b1", batchOrder: Int = 1, orderInBatch: Int = 0) =
        BatchImportPlanner.toEntity(q, "kotlin", batchOrder, orderInBatch, 0L, batchId)

    private fun context(existing: List<QuestionEntity> = emptyList(), fingerprints: Map<String, String> = emptyMap()) =
        ReconcileContext(existing.associateBy { it.questionId }, fingerprints, emptyMap())

    @Test
    fun `新题进入插入计划`() {
        val plan = BatchReconcilePlanner.plan(listOf(candidate(batch())), context(), now = 5L)
        assertEquals(1, plan.toInsert.size)
        assertEquals("q1", plan.toInsert.first().questionId)
        assertEquals("b1", plan.toInsert.first().batchId)
        assertEquals(1, plan.changedBatches)
        assertEquals(0, plan.blocked.size)
    }

    @Test
    fun `同 ID 内容变化进入更新计划`() {
        val prior = existingFrom(q())
        val changed = batch(questions = listOf(q(stem = "改过的题干")))
        val plan = BatchReconcilePlanner.plan(listOf(candidate(changed)), context(listOf(prior)), now = 5L)
        assertTrue(plan.toInsert.isEmpty())
        assertEquals(1, plan.toUpdate.size)
        assertEquals("改过的题干", plan.toUpdate.first().stem)
    }

    @Test
    fun `同 ID 内容未变不产生写入`() {
        val prior = existingFrom(q())
        val plan = BatchReconcilePlanner.plan(listOf(candidate(batch())), context(listOf(prior)), now = 5L)
        assertTrue(plan.toInsert.isEmpty())
        assertTrue(plan.toUpdate.isEmpty())
        assertEquals(1, plan.changedBatches) // 变的是「有记录但指纹为空」，故仍处理该批
    }

    @Test
    fun `同 ID 未变不计失败——记录仍为完成态`() {
        // 回归：failedCount 若照搬旧 plan() 的「issues.size - skippedCount」，这里会是 -1 → 误判 PARTIAL
        val prior = existingFrom(q())
        val plan = BatchReconcilePlanner.plan(listOf(candidate(batch())), context(listOf(prior)), now = 5L)
        val record = plan.batchRecords.single()
        assertEquals(0, record.failedCount)
        assertEquals("IMPORTED", record.status)
        assertEquals(1, record.skippedCount) // 未变，计为跳过
        assertEquals(0, record.importedCount)
    }

    @Test
    fun `指纹一致整批跳过`() {
        val b = batch()
        val plan = BatchReconcilePlanner.plan(
            listOf(candidate(b)),
            context(fingerprints = mapOf("b1" to BatchFingerprint.of(b))),
            now = 5L,
        )
        assertTrue(plan.toInsert.isEmpty())
        assertTrue(plan.toUpdate.isEmpty())
        assertEquals(0, plan.changedBatches)
        assertEquals(1, plan.unchangedBatches)
        assertTrue(plan.batchRecords.isEmpty())
    }

    @Test
    fun `跨批次停用对同轮新增题也成立`() {
        // batch-0018 场景：低序批次新增的题被高序批次停用。两阶段（先写后停）保证它生效。
        val insert = batch(questions = listOf(q(id = "newbie")), batchId = "bLow", batchOrder = 11)
        val retire = batch(questions = listOf(q(id = "other")), retired = listOf("newbie"), batchId = "bHigh", batchOrder = 18)
        val plan = BatchReconcilePlanner.plan(listOf(candidate(insert), candidate(retire)), context(), now = 5L)
        assertTrue(plan.toInsert.any { it.questionId == "newbie" })
        assertEquals(listOf("newbie"), plan.retireIds)
        assertTrue(plan.retiredMissing.isEmpty())
    }

    @Test
    fun `顺序号冲突整批进待处理`() {
        val a = batch(batchId = "b1", batchOrder = 5)
        val b = batch(batchId = "b2", batchOrder = 5)
        val plan = BatchReconcilePlanner.plan(listOf(candidate(a), candidate(b)), context(), now = 5L)
        assertEquals(2, plan.blocked.size)
        assertTrue(plan.toInsert.isEmpty())
        assertTrue(plan.blocked.all { it.reason.contains("顺序号 5") })
    }

    @Test
    fun `批内 ID 重复整批进待处理`() {
        val dup = batch(questions = listOf(q(id = "x"), q(id = "x", stem = "另一题")))
        val plan = BatchReconcilePlanner.plan(listOf(candidate(dup)), context(), now = 5L)
        assertEquals(1, plan.blocked.size)
        assertTrue(plan.toInsert.isEmpty())
    }

    @Test
    fun `疑似重复不进插入而待人工裁决`() {
        val prior = existingFrom(q(id = "existing", stem = "同一个题干"))
        val incoming = batch(questions = listOf(q(id = "new-id", stem = "同一个题干")))
        val plan = BatchReconcilePlanner.plan(listOf(candidate(incoming)), context(listOf(prior)), now = 5L)
        assertTrue(plan.toInsert.isEmpty())
        assertEquals(1, plan.duplicates.size)
        assertEquals("existing", plan.duplicates.first().existingQuestionId)
    }

    @Test
    fun `停用不在库的 ID 记为无害提示`() {
        val b = batch(retired = listOf("ghost"))
        val plan = BatchReconcilePlanner.plan(listOf(candidate(b)), context(), now = 5L)
        assertTrue(plan.retireIds.isEmpty())
        assertEquals(listOf("ghost"), plan.retiredMissing)
    }

    @Test
    fun `内容比对忽略本地状态列`() {
        val prior = existingFrom(q()).copy(
            inactive = true,
            consecutivePerfect = 3,
            hasEverWrong = true,
            favorite = true,
            importedAt = 999L,
        )
        val plan = BatchReconcilePlanner.plan(listOf(candidate(batch())), context(listOf(prior)), now = 5L)
        // 本地状态不属于「内容」，不该被判成变化
        assertTrue(plan.toUpdate.isEmpty())
    }

    @Test
    fun `已处理记录写入指纹与计数`() {
        val b = batch()
        val plan = BatchReconcilePlanner.plan(listOf(candidate(b)), context(), now = 7L)
        val record: ProcessedBatchEntity = plan.batchRecords.single()
        assertEquals(BatchFingerprint.of(b), record.contentHash)
        assertEquals(1, record.importedCount)
        assertEquals(7L, record.processedAt)
    }
}