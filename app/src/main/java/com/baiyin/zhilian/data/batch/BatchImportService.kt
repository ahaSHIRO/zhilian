package com.baiyin.zhilian.data.batch

import android.content.Context
import android.net.Uri
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
 * 批次导入的适配器：读文件（SAF）→ Schema 校验 → 解析 → 单事务执行 [BatchImportPlanner] 的计划。
 *
 * 规则不在这里——应用级校验与规划全在 [BatchImportPlanner]（纯模块）。本类只剩三件
 * 与环境有关的事：Context/Uri 读文件、Room 事务、把库读成 [ImportContext] 快照。
 * 因此「规则对不对」不再需要真的插一台设备才能问。
 */
class BatchImportService(
    private val context: Context,
    private val db: ZhilianDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val schemaValidator = BatchSchemaValidator(
        context.assets.open("batch-v1.schema.json").bufferedReader().use { it.readText() }
    )
    private val questionDao: QuestionDao = db.questionDao()
    private val batchDao: ProcessedBatchDao = db.processedBatchDao()
    private val duplicateDao: PendingDuplicateDao = db.pendingDuplicateDao()

    suspend fun importFromUri(uri: Uri, fileName: String): ImportOutcome {
        val text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return ImportOutcome.Failed(null, fileName, "无法读取文件")
        } catch (e: Exception) {
            return ImportOutcome.Failed(null, fileName, "读取文件失败：${e.message}")
        }

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
        return db.withTransaction {
            val existing = ImportContext(
                existingIds = questionDao.existingIds(candidateIds + batch.retiredQuestionIds).toSet(),
                stemOwners = questionDao.findStemOwners(candidateStems).associate { it.stem to it.questionId },
                orderTaken = batchDao.isOrderTaken(batch.batchOrder),
            )
            val rejection = BatchImportPlanner.validateBatch(batch, existing.orderTaken)
            if (rejection != null) {
                return@withTransaction ImportOutcome.Failed(batch.batchId, fileName, rejection.reason)
            }
            val plan = BatchImportPlanner.plan(batch, existing, now)

            if (plan.toInsert.isNotEmpty()) questionDao.insertAll(plan.toInsert)
            // 停用（幂等）；指向不存在题目的停用仅记入提示，不计失败、不影响导入状态
            if (plan.retiredIds.isNotEmpty()) questionDao.markInactive(plan.retiredIds)
            // 待决疑似重复持久化
            if (plan.duplicates.isNotEmpty()) {
                duplicateDao.insertAll(plan.duplicates.map {
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

            batchDao.upsert(
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

            ImportOutcome.Completed(
                batchId = batch.batchId,
                batchOrder = batch.batchOrder,
                fileName = fileName,
                importedCount = plan.toInsert.size,
                skippedCount = plan.skippedCount,
                failedCount = plan.failedCount,
                retiredCount = plan.retiredIds.size,
                issues = plan.issues,
                warnings = plan.warnings,
                duplicates = plan.duplicates,
                status = plan.status,
            )
        }
    }

    /** 人工决策疑似重复：导入该题或放弃。返回剩余待决数。 */
    suspend fun resolveDuplicate(batchId: String, questionId: String, import: Boolean): Int {
        return db.withTransaction {
            val item = duplicateDao.get(batchId, questionId)
                ?: return@withTransaction duplicateDao.countByBatch(batchId)
            duplicateDao.delete(batchId, questionId)
            if (import) {
                val q = BatchJson.json.decodeFromString<QuestionDto>(item.questionJson)
                val stillExists = questionDao.existingIds(listOf(questionId)).isNotEmpty()
                if (!stillExists) {
                    questionDao.insertAll(
                        listOf(
                            BatchImportPlanner.toEntity(
                                q = q,
                                subject = item.subject, // 科目随待决项保存，可独立恢复
                                batchOrder = batchOrderOf(batchId),
                                orderInBatch = item.orderInBatch,
                                importedAt = clock(),
                            )
                        )
                    )
                }
            }
            val remaining = duplicateDao.countByBatch(batchId)
            val record = batchDao.getById(batchId)
            if (record != null) {
                batchDao.upsert(
                    record.copy(
                        pendingDuplicateCount = remaining,
                        importedCount = if (import) record.importedCount + 1 else record.importedCount,
                        // 全部待决已清且无失败 → 升级为完成
                        status = if (remaining == 0 && record.failedCount == 0) {
                            ImportPlan.STATUS_IMPORTED
                        } else {
                            record.status
                        },
                    )
                )
            }
            remaining
        }
    }

    private suspend fun batchOrderOf(batchId: String): Int =
        batchDao.getById(batchId)?.batchOrder ?: 0

    /** 已处理批次（批次管理页） */
    fun observeProcessedBatches(): Flow<List<ProcessedBatchEntity>> = batchDao.observeAll()

    /** 待决疑似重复（批次管理页） */
    fun observePendingDuplicates(): Flow<List<PendingDuplicateEntity>> = duplicateDao.observeAll()
}
