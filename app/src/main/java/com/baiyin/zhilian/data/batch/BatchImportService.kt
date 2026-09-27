package com.baiyin.zhilian.data.batch

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.baiyin.zhilian.data.db.AnswerRecordDao
import com.baiyin.zhilian.data.db.ProcessedBatchDao
import com.baiyin.zhilian.data.db.ProcessedBatchEntity
import com.baiyin.zhilian.data.db.PendingDuplicateDao
import com.baiyin.zhilian.data.db.PendingDuplicateEntity
import com.baiyin.zhilian.data.db.QuestionDao
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.db.ZhilianDatabase
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.text.Normalizer

/** 单条导入问题（跳过/失败原因），展示与持久化两用 */
@Serializable
data class BatchIssue(val questionId: String, val reason: String)

/** 疑似重复候选：新题与库内既有题 ID 相同题干（规格 v1 的判定方法） */
data class DuplicateCandidate(
    val batchId: String,
    val question: QuestionDto,
    val orderInBatch: Int,
    val existingQuestionId: String,
)

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
 * 批次导入引擎：Schema 校验（规格 v1）→ 应用级校验 → 事务导入。
 * 全部库操作在单个 Room 事务内；结果报告供 UI 呈现并持久化批次状态。
 */
class BatchImportService(
    private val context: Context,
    private val db: ZhilianDatabase,
) {
    private val schemaValidator = BatchSchemaValidator(context)
    private val questionDao: QuestionDao = db.questionDao()
    private val recordDao: AnswerRecordDao = db.answerRecordDao()
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

        // 3. 批次级应用校验（spec 应用级清单 3/4/6）
        val duplicateInBatch = batch.questions.groupBy { it.questionId }.filterValues { it.size > 1 }.keys
        if (duplicateInBatch.isNotEmpty()) {
            return ImportOutcome.Failed(batch.batchId, fileName, "批次内题目 ID 重复：${duplicateInBatch.first()}")
        }
        val questionIds = batch.questions.map { it.questionId }.toSet()
        val retiredSelf = batch.retiredQuestionIds.intersect(questionIds)
        if (retiredSelf.isNotEmpty()) {
            return ImportOutcome.Failed(batch.batchId, fileName, "停用列表包含本批次新题：${retiredSelf.first()}")
        }
        if (batchDao.isOrderTaken(batch.batchOrder)) {
            return ImportOutcome.Failed(batch.batchId, fileName, "批次顺序号 ${batch.batchOrder} 已被其他批次占用")
        }

        // 4. 题目级校验 + 疑似重复收集在事务内进行（需要查库）
        val now = System.currentTimeMillis()
        return db.withTransaction {
            val issues = mutableListOf<BatchIssue>()
            val warnings = mutableListOf<BatchIssue>()
            val duplicates = mutableListOf<DuplicateCandidate>()
            val toInsert = mutableListOf<QuestionEntity>()
            var skipped = 0

            val existingIds = questionDao.existingIds(batch.questions.map { it.questionId }).toSet()

            batch.questions.forEachIndexed { index, q ->
                when {
                    q.questionId in existingIds -> {
                        skipped++
                        issues += BatchIssue(q.questionId, "题目 ID 已存在，跳过")
                    }
                    else -> {
                        val appError = validateQuestion(q)
                        if (appError != null) {
                            issues += BatchIssue(q.questionId, appError)
                        } else {
                            val sameStem = questionDao.findByStem(normalizeIdentity(q.stem))
                            if (sameStem.isNotEmpty()) {
                                duplicates += DuplicateCandidate(batch.batchId, q, index, sameStem.first().questionId)
                            } else {
                                toInsert += toEntity(q, batch.batchOrder, index, now)
                            }
                        }
                    }
                }
            }

            if (toInsert.isNotEmpty()) questionDao.insertAll(toInsert)

            // 停用（幂等）；指向不存在题目的停用仅记入提示，不计失败、不影响导入状态
            val existingRetired = questionDao.existingIds(batch.retiredQuestionIds)
            val missingRetired = batch.retiredQuestionIds - existingRetired.toSet()
            if (existingRetired.isNotEmpty()) questionDao.markInactive(existingRetired)
            missingRetired.forEach { warnings += BatchIssue(it, "停用的题目 ID 不在题库中，已忽略") }

            // 待决疑似重复持久化
            if (duplicates.isNotEmpty()) {
                duplicateDao.insertAll(duplicates.map {
                    PendingDuplicateEntity(
                        batchId = it.batchId,
                        questionId = it.question.questionId,
                        questionJson = BatchJson.json.encodeToString(it.question),
                        orderInBatch = it.orderInBatch,
                        existingQuestionId = it.existingQuestionId,
                    )
                })
            }

            // failedCount 仅含真实校验失败（issues = 跳过 + 校验失败；停用提示走 warnings）
            val failedCount = issues.size - skipped
            val status = if (failedCount == 0 && duplicates.isEmpty()) "IMPORTED" else "PARTIAL"
            batchDao.upsert(
                ProcessedBatchEntity(
                    batchId = batch.batchId,
                    batchOrder = batch.batchOrder,
                    status = status,
                    importedCount = toInsert.size,
                    skippedCount = skipped,
                    failedCount = failedCount,
                    pendingDuplicateCount = duplicates.size,
                    issuesJson = issues.takeIf { it.isNotEmpty() }?.let { BatchJson.json.encodeToString(it) },
                    processedAt = System.currentTimeMillis(),
                    fileName = fileName,
                )
            )

            ImportOutcome.Completed(
                batchId = batch.batchId,
                batchOrder = batch.batchOrder,
                fileName = fileName,
                importedCount = toInsert.size,
                skippedCount = skipped,
                failedCount = failedCount,
                retiredCount = existingRetired.size,
                issues = issues,
                warnings = warnings,
                duplicates = duplicates,
                status = status,
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
                    questionDao.insertAll(listOf(toEntity(q, batchOrderOf(batchId), item.orderInBatch, System.currentTimeMillis())))
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
                        status = if (remaining == 0 && record.failedCount == 0) "IMPORTED" else record.status,
                    )
                )
            }
            remaining
        }
    }

    private suspend fun batchOrderOf(batchId: String): Int =
        batchDao.getById(batchId)?.batchOrder ?: 0

    /** 题目级应用校验（spec 清单 1/2/5）：返回 null 通过，否则中文原因 */
    private fun validateQuestion(q: QuestionDto): String? {
        if (q.type == "single_choice" || q.type == "multiple_choice") {
            val options = q.options ?: return "选择题缺少选项"
            val ids = options.map { it.optionId }
            if (ids.size != ids.toSet().size) return "选项标识重复"
            val referenced: List<String> = when (q.type) {
                "single_choice" -> listOf(q.answer?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                } ?: return "缺少答案")
                else -> q.answer?.let { el ->
                    (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                } ?: return "缺少答案"
            }
            val unknown = referenced.firstOrNull { it !in ids }
            if (unknown != null) return "答案引用了不存在的选项 $unknown"
        }
        // 分类/标签 NFC 双保险（Schema pattern 已查首尾空白）
        if (normalizeIdentity(q.category).isEmpty()) return "分类名为空"
        return null
    }

    private fun toEntity(q: QuestionDto, batchOrder: Int, orderInBatch: Int, importedAt: Long): QuestionEntity =
        QuestionEntity(
            questionId = q.questionId,
            type = q.type,
            subject = "kotlin", // v1 Schema 枚举仅 kotlin；扩科目时从批次 subject 字段读取
            category = normalizeIdentity(q.category),
            tagsJson = BatchJson.json.encodeToString(q.tags.map { normalizeIdentity(it) }),
            stem = q.stem,
            optionsJson = q.options?.let { BatchJson.json.encodeToString(it) },
            answerJson = if (q.type == "fill_in_blank") {
                BatchJson.json.encodeToString(q.acceptableAnswers ?: emptyList())
            } else {
                BatchJson.json.encodeToString(q.answer ?: kotlinx.serialization.json.JsonNull)
            },
            explanation = q.explanation,
            sourceJson = BatchJson.json.encodeToString(q.source),
            batchOrder = batchOrder,
            orderInBatch = orderInBatch,
            importedAt = importedAt,
        )

    companion object {
        /** 身份归一化：NFC + trim（spec：分类/标签/疑似重复比对统一使用） */
        fun normalizeIdentity(s: String): String =
            Normalizer.normalize(s.trim(), Normalizer.Form.NFC)
    }
}
