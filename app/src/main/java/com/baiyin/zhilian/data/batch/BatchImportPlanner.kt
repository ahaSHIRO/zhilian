package com.baiyin.zhilian.data.batch

import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.db.StemOwnerRow
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

    /** 9：多选题的 `answer` 不得覆盖全部选项（全选题没有区分度） */
    const val ANSWER_NOT_ALL_OPTIONS = 9

    /** 10：解析里显式声明的答案必须与 `answer` 一致（声明与答案键脱钩） */
    const val DECLARED_ANSWER_MISMATCH = 10

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

    /**
     * 全部落库且无待决重复才算完成，否则部分成功、可重试。
     * 判定走 [BatchImportPlanner.batchStatus] 同源纯函数（ADR-0012），不在别处内联。
     */
    val status: String
        get() = BatchImportPlanner.batchStatus(failedCount, duplicates.isNotEmpty())

    companion object {
        const val STATUS_IMPORTED = "IMPORTED"
        const val STATUS_PARTIAL = "PARTIAL"
    }
}

/**
 * 疑似重复裁决的落库结果（ADR-0012）：新状态 + 已导入数增量。
 * 窄值对象，不耦合 `ProcessedBatchEntity`——纯函数只需这两个字段回传。
 */
internal data class ResolutionOutcome(
    val newStatus: String,
    val newImportedCount: Int,
)

/**
 * 批次导入的应用级校验与规划（batch-spec-v1.md §应用级校验清单 1–10）。
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

    /**
     * 批次完成态判定（ADR-0012）：**唯一落点**——[ImportPlan.status] 与 [resolveOutcome] 共用同源，
     * 不在别处内联同一规则。无失败且无待决才算完成，否则部分成功、可重试。
     *
     * @param hasPending 是否仍有待决（导入规划期 = 产出待决非空；裁决后 = 剩余待决 > 0）
     */
    internal fun batchStatus(failedCount: Int, hasPending: Boolean): String =
        if (failedCount == 0 && !hasPending) ImportPlan.STATUS_IMPORTED else ImportPlan.STATUS_PARTIAL

    /**
     * 疑似重复裁决后的落库结果（ADR-0012）：状态升级 + 已导入数增量。
     *
     * 规则在此一处；[BatchImportService] 的 `resolveDuplicate` 只做事务与 I/O，按返回值写——
     * 原先该判定内联在它的事务体里，与 [ImportPlan.status] 双写且判据语义微差
     * （规划期看「产出待决列表是否空」，裁决后看「剩余计数是否为零」）。
     *
     * 入参刻意收窄成原始值而非 `ProcessedBatchEntity`：本函数只读失败数与已导入数两字段，
     * 窄入参让单测不必构造整个实体（与 `masteryTransition` 收窄值对象同一取舍）。
     *
     * @param import 本次裁决是否导入该题（导入则已导入数 +1）
     * @param remaining 裁决后剩余待决数（> 0 表示批次仍未完成）
     */
    internal fun resolveOutcome(
        failedCount: Int,
        importedCount: Int,
        import: Boolean,
        remaining: Int,
    ): ResolutionOutcome = ResolutionOutcome(
        newStatus = batchStatus(failedCount, hasPending = remaining > 0),
        newImportedCount = if (import) importedCount + 1 else importedCount,
    )

    /**
     * 组装 [ImportContext] 快照（ADR-0013）：把 DAO 返回的原始结果收成规划所需的现状快照。
     *
     * 纯组装（去重 / 映射）在此一处——若接口直接返回 `Set` / `Map`，这段逻辑就藏进实现、无法纯测。
     */
    internal fun buildImportContext(
        existingIds: List<String>,
        stemOwners: List<StemOwnerRow>,
        orderTaken: Boolean,
    ): ImportContext = ImportContext(
        existingIds = existingIds.toSet(),
        stemOwners = stemOwners.associate { it.stem to it.questionId },
        orderTaken = orderTaken,
    )

    /**
     * [ImportPlan] → [ImportOutcome.Completed] 映射（ADR-0013）：字段拷贝与透传。
     *
     * 原先内联在 `importFromUri` 的事务体里，与 `resolveDuplicate` 的状态映射手写两处、易漂移。
     */
    internal fun toOutcome(
        plan: ImportPlan,
        batchId: String,
        batchOrder: Int,
        fileName: String,
    ): ImportOutcome.Completed = ImportOutcome.Completed(
        batchId = batchId,
        batchOrder = batchOrder,
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

    /**
     * 清单 10 的解析侧判定：抽出解析里所有「显式声明答案」的字母集合（已排除否定语境）。
     *
     * 习语按题型分档——多选只认「整集声明」（答案是 / 答案为 / 正确项是）。单选里常见的
     * 「…原因，选 C。」是在解释**为什么 C 入选**，放到多选语境会误报
     * （2026-10-05 实测 batch-0024 第 7 题）。
     */
    private val declPatternsSingle = listOf(
        Regex("""为什么对\s*[：:]\s*选\s*([A-E](?:\s*[、,，/和及]\s*[A-E])*)"""),
        Regex("""答案是\s*[「"'（(]?\s*([A-E](?:\s*[、,，/和及]\s*[A-E])*)"""),
        Regex("""答案为\s*[「"'（(]?\s*([A-E](?:\s*[、,，/和及]\s*[A-E])*)"""),
        Regex("""正确项是\s*([A-E](?:\s*[、,，/和及]\s*[A-E])*)"""),
        Regex("""故选\s*([A-E])"""),
        Regex("""选\s*([A-E])\s*[。.]"""),
    )

    private val declPatternsMulti = listOf(
        Regex("""答案是\s*[「"'（(]?\s*([A-E](?:\s*[、,，/和及]\s*[A-E])*)"""),
        Regex("""答案为\s*[「"'（(]?\s*([A-E](?:\s*[、,，/和及]\s*[A-E])*)"""),
        Regex("""正确项是\s*([A-E](?:\s*[、,，/和及]\s*[A-E])*)"""),
    )

    /** 否定语境守卫：命中点前一字为 不/别/勿，或命中点后紧跟「不选 / 选错」等，一律不算声明 */
    private val declNegTail = listOf("不选", "别选", "勿选", "没选", "选错")

    private val letterPattern = Regex("[A-E]")

    internal fun declaredAnswerSets(explanation: String, qtype: String): Set<Set<String>> {
        val pats = if (qtype == "single_choice") declPatternsSingle else declPatternsMulti
        val found = mutableSetOf<Set<String>>()
        pats.forEach { pat ->
            pat.findAll(explanation).forEach { m ->
                val pre = explanation.substring(maxOf(0, m.range.first - 4), m.range.first).trimEnd()
                val tail = explanation.substring(m.range.last + 1, minOf(explanation.length, m.range.last + 5))
                val negated = (pre.isNotEmpty() && pre.last() in "不别勿") || declNegTail.any { it in tail }
                if (!negated) {
                    found += letterPattern.findAll(m.groupValues[1]).map { it.value }.toSet()
                }
            }
        }
        return found
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
            // 清单 9：多选题的 answer 不得覆盖全部选项（全选题没有区分度；实测该形态正是
            // 「解析已判定某项不入选、答案键却把它收进来」这类矛盾的外在表现）
            if (q.type == "multiple_choice" && ids.size >= 2 && referenced.toSet() == ids.toSet()) {
                return BatchRejection(
                    BatchRules.ANSWER_NOT_ALL_OPTIONS,
                    "多选题的答案覆盖了全部 ${ids.size} 个选项，全选题没有区分度",
                )
            }
            // 清单 10：解析里显式声明的答案必须与 answer 一致（声明与答案键脱钩）
            val declared = declaredAnswerSets(q.explanation, q.type)
            if (declared.isNotEmpty() && declared != setOf(referenced.toSet())) {
                val shown = declared.map { it.sorted().joinToString("") }.sorted().joinToString("、")
                return BatchRejection(
                    BatchRules.DECLARED_ANSWER_MISMATCH,
                    "解析显式声明的答案是 $shown，与 answer ${referenced.sorted().joinToString("")} 不符",
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
