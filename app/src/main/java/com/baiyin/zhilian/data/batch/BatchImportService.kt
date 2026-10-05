package com.baiyin.zhilian.data.batch

import androidx.room.withTransaction
import com.baiyin.zhilian.data.db.PendingDuplicateDao
import com.baiyin.zhilian.data.db.PendingDuplicateEntity
import com.baiyin.zhilian.data.db.ProcessedBatchDao
import com.baiyin.zhilian.data.db.ProcessedBatchEntity
import com.baiyin.zhilian.data.db.QuestionDao
import com.baiyin.zhilian.data.db.ZhilianDatabase
import com.baiyin.zhilian.data.question.QuestionContent
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

sealed class ImportOutcome {
    /** 整批失败：未产生任何库变更（或仅记录 FAILED 状态行） */
    data class Failed(
        val batchId: String?,
        val fileName: String,
        val reason: String,
    ) : ImportOutcome()

    /** 部分或全部成功 */
    data class Completed(
        val batchId: String,
        val batchOrder: Int,
        val fileName: String,
        val importedCount: Int,
        val skippedCount: Int,
        val failedCount: Int,
        val retiredCount: Int,
        val issues: List<BatchIssue>,
        /** 无害提示（如停用 ID 不在库）：不计失败、不影响导入状态 */
        val warnings: List<BatchIssue>,
        val duplicates: List<DuplicateCandidate>,
        val status: String, // IMPORTED / PARTIAL
    ) : ImportOutcome()
}

/**
 * 批次导入的适配器：Schema 校验 → 解析 → 单事务执行 [BatchImportPlanner] 的计划。
 *
 * 规则不在这里——应用级校验与规划全在 [BatchImportPlanner]（纯模块）。本类只剩与环境有关的事：
 * Room 事务与写入（[importStore]）、把库读成 [ImportContext] 快照（[BatchImportPlanner.buildImportContext]
 * 纯函数）。**SAF 文件读取留给调用方**（`BatchManageScreen`），测试因此不必构造 `Context`（ADR-0013）。
 *
 * [importStore] 覆盖 [importFromText] 的数据存取面；[resolveDuplicate] 与 observe 方法暂走 [db]
 * （渐进重构，后续可纳入统一 store）——[db] 为 null 时这些方法不可用（仅供测试构造）。
 */
class BatchImportService internal constructor(
    private val importStore: ImportStore,
    private val schemaValidator: BatchSchemaValidator,
    private val db: ZhilianDatabase?, // 仅供 resolveDuplicate/observe（ADR-0013 渐进重构）
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val questionDao: QuestionDao? = db?.questionDao()
    private val batchDao: ProcessedBatchDao? = db?.processedBatchDao()
    private val duplicateDao: PendingDuplicateDao? = db?.pendingDuplicateDao()

    /** 批次导入编排：Schema 校验 → 解析 → 事务内组装快照 → 规划 → 按计划写 → 映射结果 */
    suspend fun importFromText(text: String, fileName: String): ImportOutcome {
        // 1. Schema 校验（含 formatVersion 锁定、题型字段互斥、ID 格式等）
        val schemaIssues = schemaValidator.validate(text)
        if (schemaIssues != null) {
            val summary = schemaIssues.take(5).joinToString("\n") { "${it.path}: ${it.message}" }
            return ImportOutcome.Failed(
                null, fileName,
                "Schema 校验未通过（${schemaIssues.size} 处）\n$summary",
            )
        }

        // 2. DTO 解析
        val batch = try {
            BatchJson.json.decodeFromString<BatchFileDto>(text)
        } catch (e: SerializationException) {
            return ImportOutcome.Failed(null, fileName, BatchSchemaValidator.describeParseError(e))
        }

        val now = clock()
        val candidateIds = batch.questions.map { it.questionId }
        val candidateStems = batch.questions.map { QuestionContent.normalizeIdentity(it.stem) }.distinct()

        // 3~4. 单一事务内：把库读成现状快照 → 规划 → 按计划写
        return importStore.inTransaction {
            val existing = BatchImportPlanner.buildImportContext(
                existingIds = importStore.findExistingIds(candidateIds + batch.retiredQuestionIds),
                stemOwners = importStore.findStemOwners(candidateStems),
                orderTaken = importStore.isOrderTaken(batch.batchOrder),
            )
            val rejection = BatchImportPlanner.validateBatch(batch, existing.orderTaken)
            if (rejection != null) {
                return@inTransaction ImportOutcome.Failed(batch.batchId, fileName, rejection.reason)
            }
            val plan = BatchImportPlanner.plan(batch, existing, now)

            if (plan.toInsert.isNotEmpty()) importStore.insertQuestions(plan.toInsert)
            // 停用（幂等）；指向不存在题目的停用仅记入提示，不计失败、不影响导入状态
            if (plan.retiredIds.isNotEmpty()) importStore.markInactive(plan.retiredIds)
            // 待决疑似重复持久化
            if (plan.duplicates.isNotEmpty()) {
                importStore.insertPendingDuplicates(plan.duplicates.map {
                    PendingDuplicateEntity(
                        batchId = it.batchId,
                        questionId = it.question.questionId,
                        questionJson = BatchJson.json.encodeToString(it.question),
                        subject = batch.subject,
                        orderInBatch = it.orderInBatch,
                        existingQuestionId = it.existingQuestionId,
                    )
                })
            }

            importStore.upsertBatch(
                ProcessedBatchEntity(
                    batchId = batch.batchId,
                    batchOrder = batch.batchOrder,
                    status = plan.status,
                    importedCount = plan.toInsert.size,
                    skippedCount = plan.skippedCount,
                    failedCount = plan.failedCount,
                    pendingDuplicateCount = plan.duplicates.size,
                    issuesJson = plan.issues.takeIf { it.isNotEmpty() }?.let { BatchJson.json.encodeToString(it) },
                    processedAt = now,
                    fileName = fileName,
                )
            )

            BatchImportPlanner.toOutcome(plan, batch.batchId, batch.batchOrder, fileName)
        }
    }

    /** 人工决策疑似重复：导入该题或放弃。返回剩余待决数。（暂走 db，ADR-0013 渐进重构） */
    suspend fun resolveDuplicate(batchId: String, questionId: String, import: Boolean): Int {
        val database = db ?: error("resolveDuplicate 需要 db（ADR-0013 渐进重构）")
        val dupDao = duplicateDao ?: error("resolveDuplicate 需要 db（ADR-0013 渐进重构）")
        val qDao = questionDao ?: error("resolveDuplicate 需要 db（ADR-0013 渐进重构）")
        val bDao = batchDao ?: error("resolveDuplicate 需要 db（ADR-0013 渐进重构）")
        return database.withTransaction {
            val item = dupDao.get(batchId, questionId)
                ?: return@withTransaction dupDao.countByBatch(batchId)
            dupDao.delete(batchId, questionId)
            if (import) {
                val q = BatchJson.json.decodeFromString<QuestionDto>(item.questionJson)
                val stillExists = qDao.existingIds(listOf(questionId)).isNotEmpty()
                if (!stillExists) {
                    qDao.insertAll(
                        listOf(
                            BatchImportPlanner.toEntity(
                                q = q,
                                subject = item.subject, // 科目随待决项保存，可独立恢复
                                batchOrder = bDao.getById(batchId)?.batchOrder ?: 0,
                                orderInBatch = item.orderInBatch,
                                importedAt = clock(),
                                batchId = item.batchId,
                            )
                        )
                    )
                }
            }
            val remaining = dupDao.countByBatch(batchId)
            val record = bDao.getById(batchId)
            if (record != null) {
                // 状态升级与已导入数增量收口在纯函数（ADR-0012）：原先此处内联的完成态判定
                // 与 ImportPlan.status 双写，且判据语义微差（剩余计数 vs 待决列表）
                val outcome = BatchImportPlanner.resolveOutcome(
                    failedCount = record.failedCount,
                    importedCount = record.importedCount,
                    import = import,
                    remaining = remaining,
                )
                bDao.upsert(
                    record.copy(
                        pendingDuplicateCount = remaining,
                        importedCount = outcome.newImportedCount,
                        status = outcome.newStatus,
                    )
                )
            }
            remaining
        }
    }

    /** 已处理批次（批次管理页） */
    fun observeProcessedBatches(): Flow<List<ProcessedBatchEntity>> =
        batchDao?.observeAll() ?: error("observe 需要 db（ADR-0013 渐进重构）")

    /** 待决疑似重复（批次管理页） */
    fun observePendingDuplicates(): Flow<List<PendingDuplicateEntity>> =
        duplicateDao?.observeAll() ?: error("observe 需要 db（ADR-0013 渐进重构）")
}
