package com.baiyin.zhilian.data.batch

import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.question.QuestionContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

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

/**
 * batch-spec-v1.md §应用级校验清单的规则号。
 *
 * 规则号是给机器用的身份：Kotlin 与 Python 两个实现各自判定后按号比对
 * （见 AppLevelFixturesTest 与 tools/batch-check.py --selftest），
 * 中文说明只给人看。少一条规则、或两侧对同一条规则判定不一，夹具就会红。
 */
object BatchRules {
    /** 1：`answer` 引用的每个 `optionId` 必须存在于该题 `options` */
    const val ANSWER_REFERENCES_OPTION = 1

    /** 2：`options` 内 `optionId` 互不重复 */
    const val OPTION_ID_UNIQUE = 2

    /** 3：`questionId` 互不重复（批次内 + 与已导入题库比对） */
    const val QUESTION_ID_UNIQUE = 3

    /** 4：`retiredQuestionIds` 不得包含本批次新题 */
    const val RETIRED_NOT_IN_BATCH = 4

    /** 5：分类名 NFC + trim 归一化后非空 */
    const val CATEGORY_NOT_BLANK = 5

    /** 6：`batchOrder` 不得与已导入批次重复 */
    const val BATCH_ORDER_UNIQUE = 6

    /** 7：`formatVersion` 必须为 1（由 Schema 的 const 承担，规划器不产出） */
    const val FORMAT_VERSION = 7

    /** 8：疑似重复（题干归一化后与本机某题相同且 ID 不同） */
    const val SUSPECTED_DUPLICATE = 8

    /** 0：清单之外、只有一端实现的补充检查（不该出现在共同夹具里） */
    const val EXTRA = 0
}

/** 应用级校验的拒绝：清单号 + 中文说明 */
data class BatchRejection(val rule: Int, val reason: String)

/**
 * 库内现状快照：规划一次导入所需的既有事实。
 *
 * 刻意做成纯数据而非 DAO 句柄——规划因此能在 JVM 单测里直接构造输入，
 * 不必拉起 Room。适配器负责把库读成这个快照。
 */
data class ImportContext(
    /** 候选题与停用 ID 中，本机题库已存在的 */
    val existingIds: Set<String>,
    /** 归一化题干 → 既有题目 ID（疑似重复判定，清单 8） */
    val stemOwners: Map<String, String>,
    /** 批次顺序号是否已被占用（清单 6） */
    val orderTaken: Boolean,
)

/** 导入计划：纯函数产物，适配器只负责按计划读写 */
data class ImportPlan(
    val toInsert: List<QuestionEntity>,
    val skippedCount: Int,
    val issues: List<BatchIssue>,
    val warnings: List<BatchIssue>,
    val duplicates: List<DuplicateCandidate>,
    /** 真正置 inactive 的既有题 */
    val retiredIds: List<String>,
    /** 停用列表里不在库的 ID（无害提示） */
    val missingRetired: List<String>,
) {
    /** 真实校验失败数（issues 含跳过；停用提示走 warnings，不计失败） */
    val failedCount: Int get() = issues.size - skippedCount

    /** 全部落库且无待决重复才算完成，否则部分成功、可重试 */
    val status: String
        get() = if (failedCount == 0 && duplicates.isEmpty()) STATUS_IMPORTED else STATUS_PARTIAL

    companion object {
        const val STATUS_IMPORTED = "IMPORTED"
        const val STATUS_PARTIAL = "PARTIAL"
    }
}

/**
 * 批次导入的应用级校验与规划（batch-spec-v1.md §应用级校验清单 1–8）。
 *
 * 纯模块：输入是批次 DTO + 库内现状快照 + 时间戳，输出是导入计划。
 * 文件读取、Schema 校验与事务写入都留在适配器（[BatchImportService]）里，
 * 本模块不碰 Context、Room 与系统时钟——规则因此可以被直接单测，而它们原先
 * 埋在 importFromUri 的 180 行里，与 I/O 和事务绞作一处，无处可测。
 *
 * 与电脑端 tools/batch-check.py 的镜像关系：本模块是应用内权威，两端的规则号
 * 集合由 docs/schema/app-level-fixtures.json 的共同用例钉住
 * （见 AppLevelFixturesTest 与 `python tools/batch-check.py --selftest`）。
 */
object BatchImportPlanner {

    /** 批次级校验（清单 3 前半 / 4 / 6）：null 通过，否则整批拒绝的原因 */
    fun validateBatch(batch: BatchFileDto, orderTaken: Boolean): BatchRejection? {
        val duplicateInBatch = batch.questions.groupBy { it.questionId }.filterValues { it.size > 1 }.keys
        if (duplicateInBatch.isNotEmpty()) {
            return BatchRejection(
                BatchRules.QUESTION_ID_UNIQUE,
                "批次内题目 ID 重复：${duplicateInBatch.first()}",
            )
        }
        val retiredSelf = batch.retiredQuestionIds.intersect(batch.questions.map { it.questionId }.toSet())
        if (retiredSelf.isNotEmpty()) {
            return BatchRejection(
                BatchRules.RETIRED_NOT_IN_BATCH,
                "停用列表包含本批次新题：${retiredSelf.first()}",
            )
        }
        if (orderTaken) {
            return BatchRejection(
                BatchRules.BATCH_ORDER_UNIQUE,
                "批次顺序号 ${batch.batchOrder} 已被其他批次占用",
            )
        }
        return null
    }

    /** 题目级校验（清单 1/2/5）：null 通过，否则该题的原因 */
    fun validateQuestion(q: QuestionDto): BatchRejection? {
        if (q.type == "single_choice" || q.type == "multiple_choice") {
            val options = q.options
                ?: return BatchRejection(BatchRules.EXTRA, "选择题缺少选项")
            val ids = options.map { it.optionId }
            if (ids.size != ids.toSet().size) {
                return BatchRejection(BatchRules.OPTION_ID_UNIQUE, "选项标识重复")
            }
            val referenced: List<String> = when (q.type) {
                "single_choice" -> listOf(
                    q.answer?.let { (it as? JsonPrimitive)?.content }
                        ?: return BatchRejection(BatchRules.EXTRA, "缺少答案")
                )
                else -> q.answer?.let { el ->
                    (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                } ?: return BatchRejection(BatchRules.EXTRA, "缺少答案")
            }
            val unknown = referenced.firstOrNull { it !in ids }
            if (unknown != null) {
                return BatchRejection(
                    BatchRules.ANSWER_REFERENCES_OPTION,
                    "答案引用了不存在的选项 $unknown",
                )
            }
        }
        // 分类/标签 NFC 双保险（Schema pattern 已查首尾空白）
        if (QuestionContent.normalizeIdentity(q.category).isEmpty()) {
            return BatchRejection(BatchRules.CATEGORY_NOT_BLANK, "分类名为空")
        }
        return null
    }

    /** 按清单 3 后半 / 8 逐题规划落库、跳过与待决重复 */
    fun plan(batch: BatchFileDto, context: ImportContext, importedAt: Long): ImportPlan {
        val issues = mutableListOf<BatchIssue>()
        val warnings = mutableListOf<BatchIssue>()
        val duplicates = mutableListOf<DuplicateCandidate>()
        val toInsert = mutableListOf<QuestionEntity>()
        var skipped = 0

        batch.questions.forEachIndexed { index, q ->
            when {
                q.questionId in context.existingIds -> {
                    skipped++
                    issues += BatchIssue(q.questionId, "题目 ID 已存在，跳过")
                }
                else -> {
                    val rejection = validateQuestion(q)
                    if (rejection != null) {
                        issues += BatchIssue(q.questionId, rejection.reason)
                    } else {
                        val owner = context.stemOwners[QuestionContent.normalizeIdentity(q.stem)]
                        if (owner != null) {
                            duplicates += DuplicateCandidate(batch.batchId, q, index, owner)
                        } else {
                            toInsert += toEntity(q, batch.subject, batch.batchOrder, index, importedAt)
                        }
                    }
                }
            }
        }

        val retiredIds = batch.retiredQuestionIds.filter { it in context.existingIds }
        val missingRetired = batch.retiredQuestionIds.filter { it !in context.existingIds }
        missingRetired.forEach { warnings += BatchIssue(it, "停用的题目 ID 不在题库中，已忽略") }

        return ImportPlan(toInsert, skipped, issues, warnings, duplicates, retiredIds, missingRetired)
    }

    /** 批次 DTO → 库实体（纯映射；身份归一化与 JSON 编码在此收口） */
    fun toEntity(
        q: QuestionDto,
        subject: String,
        batchOrder: Int,
        orderInBatch: Int,
        importedAt: Long,
    ): QuestionEntity =
        QuestionEntity(
            questionId = q.questionId,
            type = q.type,
            subject = subject, // 取自批次 subject 字段（Schema 枚举 kotlin / java / interview，arkts 占位）
            category = QuestionContent.normalizeIdentity(q.category),
            tagsJson = BatchJson.json.encodeToString(q.tags.map { QuestionContent.normalizeIdentity(it) }),
            // stem 也归一化落库：findStemOwners 用归一化值查询，若原样存则含首尾空白
            // 或 NFD 形式的题干会漏判疑似重复（spec §应用级校验 8）
            stem = QuestionContent.normalizeIdentity(q.stem),
            optionsJson = q.options?.let { BatchJson.json.encodeToString(it) },
            answerJson = if (q.type == "fill_in_blank") {
                BatchJson.json.encodeToString(q.acceptableAnswers ?: emptyList())
            } else {
                BatchJson.json.encodeToString(q.answer ?: JsonNull)
            },
            explanation = q.explanation,
            sourceJson = BatchJson.json.encodeToString(q.source),
            batchOrder = batchOrder,
            orderInBatch = orderInBatch,
            importedAt = importedAt,
        )
}
