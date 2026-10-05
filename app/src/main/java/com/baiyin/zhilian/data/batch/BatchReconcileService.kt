package com.baiyin.zhilian.data.batch

import com.baiyin.zhilian.data.db.PendingDuplicateEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString

/** 一个待对账的来源：文件名 + 文本（读取失败为 null，计为待处理） */
data class BatchSource(val fileName: String, val text: String?)

/** 待处理批次的摘要项（持久化到本机设置，供批次管理页展示） */
@Serializable
data class BlockedBatchDigest(val fileName: String, val reason: String)

/**
 * 一次对账的结果摘要（Q8：有变更时在批次导入页留一条，不弹窗）。
 * 可序列化以便持久化到 DataStore。
 */
@Serializable
data class ReconcileDigest(
    val at: Long = 0L,
    val changedBatches: Int = 0,
    val unchangedBatches: Int = 0,
    val inserted: Int = 0,
    val updated: Int = 0,
    val retired: Int = 0,
    val pendingDuplicates: Int = 0,
    val blocked: List<BlockedBatchDigest> = emptyList(),
) {
    /** 有实质变更或待处理项才值得留摘要；纯「无变化」不打扰 */
    val hasChanges: Boolean
        get() = inserted > 0 || updated > 0 || retired > 0 || pendingDuplicates > 0 || blocked.isNotEmpty()
}

/**
 * 题库对账编排（ADR-0017）：Schema 校验 → 解析 → 单事务内组装现状快照 → 目录级规划
 * （[BatchReconcilePlanner]）→ **两阶段**写入（先写题、后统一停用）。
 *
 * 规则不在本类：应用级校验与规划全在纯模块；本类只剩与环境相关的事——把来源文本
 * 解析成 DTO、读库快照、按计划写（[ReconcileStore]）。SAF 读取留调用方
 * （[BatchReconciler] / 批次管理页），测试因此不必构造 `Context`。
 */
class BatchReconcileService internal constructor(
    private val store: ReconcileStore,
    private val schemaValidator: BatchSchemaValidator,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 对整个目录做一次对账。**幂等**：同一批来源重复调用不产生额外变更。
     *
     * 单事务内先写所有批次的新增与更新，再统一执行停用——停用取**目录级并集**
     * （含指纹未变被跳过的批次），故「导入顺序」（batch-0018 早于 batch-0011）与
     * 「撤销后重导」两条漏停用通道都不会发生。规则在 [BatchReconcilePlanner]，本类只编排。
     */
    suspend fun reconcile(sources: List<BatchSource>): ReconcileDigest {
        val candidates = mutableListOf<BatchCandidate>()
        val blocked = mutableListOf<BlockedBatch>()
        sources.forEach { src ->
            val text = src.text
            if (text == null) {
                blocked += BlockedBatch(src.fileName, null, null, "无法读取文件")
                return@forEach
            }
            val schemaIssues = schemaValidator.validate(text)
            if (schemaIssues != null) {
                blocked += BlockedBatch(
                    src.fileName, null, null,
                    "Schema 校验未通过（${schemaIssues.size} 处）",
                )
                return@forEach
            }
            val batch = try {
                BatchJson.json.decodeFromString<BatchFileDto>(text)
            } catch (e: SerializationException) {
                blocked += BlockedBatch(src.fileName, null, null, BatchSchemaValidator.describeParseError(e))
                return@forEach
            }
            candidates += BatchCandidate(src.fileName, batch, BatchFingerprint.of(batch))
        }

        val now = clock()
        return store.inTransaction {
            val records = store.allBatchRecords()
            val context = ReconcileContext(
                existing = store.allQuestions().associateBy { it.questionId },
                storedFingerprints = records.associate { it.batchId to it.contentHash },
                ordersInUse = records.associate { it.batchOrder to it.batchId },
            )
            val plan = BatchReconcilePlanner.plan(candidates, context, now, blocked)

            // —— 阶段一：写题（新增 + 更新）——
            if (plan.toInsert.isNotEmpty()) store.insertQuestions(plan.toInsert)
            if (plan.toUpdate.isNotEmpty()) store.updateQuestions(plan.toUpdate)
            // —— 阶段二：统一停用（放最后，跨批次停用也成立）——
            if (plan.retireIds.isNotEmpty()) store.markInactive(plan.retireIds)

            if (plan.duplicates.isNotEmpty()) {
                val subjectByBatch = candidates.associate { it.batch.batchId to it.batch.subject }
                store.insertPendingDuplicates(
                    plan.duplicates.map {
                        PendingDuplicateEntity(
                            batchId = it.batchId,
                            questionId = it.question.questionId,
                            questionJson = BatchJson.json.encodeToString(it.question),
                            subject = subjectByBatch[it.batchId] ?: "",
                            orderInBatch = it.orderInBatch,
                            existingQuestionId = it.existingQuestionId,
                        )
                    }
                )
            }
            if (plan.batchRecords.isNotEmpty()) store.upsertBatchRecords(plan.batchRecords)

            ReconcileDigest(
                at = now,
                changedBatches = plan.changedBatches,
                unchangedBatches = plan.unchangedBatches,
                inserted = plan.toInsert.size,
                updated = plan.toUpdate.size,
                retired = plan.retireIds.size,
                pendingDuplicates = plan.duplicates.size,
                blocked = plan.blocked.map { BlockedBatchDigest(it.fileName, it.reason) },
            )
        }
    }

    /**
     * 撤销某批次导入（Q6）：删该批题目 + 那些题的作答记录 + 该批待决项 + 已处理记录。
     * 按 `batch_id` 定位，故不误伤别的批次；撤销后文件仍在目录，可正常重导。
     */
    suspend fun undoBatch(batchId: String) = store.inTransaction {
        val ids = store.questionIdsByBatch(batchId)
        if (ids.isNotEmpty()) {
            store.deleteAnswerRecords(ids)
            store.deleteQuestionsByBatch(batchId)
        }
        store.deletePendingDuplicatesByBatch(batchId)
        store.deleteBatchRecord(batchId)
    }
}