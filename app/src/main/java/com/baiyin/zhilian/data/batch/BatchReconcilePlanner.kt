package com.baiyin.zhilian.data.batch

import com.baiyin.zhilian.data.db.ProcessedBatchEntity
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.question.QuestionContent

/** 一个已成功解析的批次候选（扫描侧产物），带入对账规划 */
data class BatchCandidate(
    val fileName: String,
    val batch: BatchFileDto,
    val fingerprint: String,
)

/** 对账所需的库内现状快照（纯数据，规划因此可在 JVM 里直接构造） */
data class ReconcileContext(
    /** 库内全部题目（含停用），按 questionId */
    val existing: Map<String, QuestionEntity>,
    /** 已处理批次的内容指纹：batchId → 上次对账指纹 */
    val storedFingerprints: Map<String, String>,
    /** 已被占用的批次顺序号：batchOrder → batchId */
    val ordersInUse: Map<Int, String>,
)

/**
 * 被拒批次：不应用任何写入，进「待处理」并给原因。
 * 与「疑似重复待决」不同——后者是逐题人工裁决，前者是整批拒绝。
 */
data class BlockedBatch(
    val fileName: String,
    val batchId: String?,
    val batchOrder: Int?,
    val reason: String,
)

/** 单批对账产物（中间态） */
data class BatchPlan(
    val batchId: String,
    val batchOrder: Int,
    val fileName: String,
    val fingerprint: String,
    val toInsert: List<QuestionEntity>,
    val toUpdate: List<QuestionEntity>,
    val retiredIds: List<String>,
    val duplicates: List<DuplicateCandidate>,
    val issues: List<BatchIssue>,
    val skippedCount: Int,
) {
    /**
     * 真实失败数 = 校验失败且写入 issues 的题数。
     * **不减去 [skippedCount]**——对账路径下「跳过」是「同 ID 且内容未变、无需写入」，
     * 根本不进 issues；减它会把失败数算成负数、把正常批次误判为 PARTIAL。
     */
    val failedCount: Int get() = issues.size

    val status: String get() = BatchImportPlanner.batchStatus(failedCount, duplicates.isNotEmpty())
}

/**
 * 目录级对账计划：适配器只负责**按两阶段**执行——先写 `toInsert`/`toUpdate`，
 * 再统一 `markInactive(retireIds)`。停用放最后是整个模型的要害：使批次导入顺序
 * 不再影响结果（根治 batch-0018 早于 batch-0011 导入时停用静默丢失一类的问题）。
 */
data class ReconcilePlan(
    val toInsert: List<QuestionEntity>,
    val toUpdate: List<QuestionEntity>,
    /** 待统一停用的题目 ID：各**变化批次**停用列表并集 ∩（现有 ∪ 本轮新增） */
    val retireIds: List<String>,
    /** 停用列表里不在库的 ID（无害提示，不计失败） */
    val retiredMissing: List<String>,
    val duplicates: List<DuplicateCandidate>,
    val batchRecords: List<ProcessedBatchEntity>,
    val blocked: List<BlockedBatch>,
    val changedBatches: Int,
    val unchangedBatches: Int,
)

/**
 * 题库对账的目录级规划（ADR-0017）。纯模块：不碰 Context / Room / 系统时钟。
 *
 * 逐题规则（题目级校验、疑似重复、实体映射）复用 [BatchImportPlanner] 这一权威单源；
 * 本模块只加**目录级**三件事：内容指纹判「已对账」、顺序号冲突判「待处理」、
 * 以及把各批的停用列表并成一次统一停用。
 */
object BatchReconcilePlanner {

    /**
     * @param candidates 已解析成功的批次（解析/Schema 失败的走 [parseBlocked]）
     * @param parseBlocked 解析或 Schema 阶段就被拒的批次（由适配器识别）
     */
    fun plan(
        candidates: List<BatchCandidate>,
        context: ReconcileContext,
        now: Long,
        parseBlocked: List<BlockedBatch> = emptyList(),
    ): ReconcilePlan {
        val blocked = parseBlocked.toMutableList()
        val batchPlans = mutableListOf<BatchPlan>()
        var unchanged = 0

        // 顺序号冲突：目录内同序不同批，或与已入库记录撞序（本批自身同号不算冲突）
        val orderOwners = mutableMapOf<Int, MutableSet<String>>()
        candidates.forEach { c ->
            orderOwners.getOrPut(c.batch.batchOrder) { mutableSetOf() }.add(c.batch.batchId)
        }

        // 题干归属：库内未停用题 + 本轮已排入插入的题（跨批次的疑似重复同样要拦）
        val stemOwners = context.existing.values
            .filter { !it.inactive }
            .associate { QuestionContent.normalizeIdentity(it.stem) to it.questionId }
            .toMutableMap()

        candidates.sortedWith(compareBy({ it.batch.batchOrder }, { it.fileName })).forEach { c ->
            val batch = c.batch

            // 指纹一致 = 已对账，整批不动（也不重算停用：上次已生效）
            if (context.storedFingerprints[batch.batchId] == c.fingerprint) {
                unchanged++
                return@forEach
            }

            val conflict = orderOwners[batch.batchOrder]!!.any { it != batch.batchId } ||
                context.ordersInUse[batch.batchOrder]?.let { it != batch.batchId } == true
            if (conflict) {
                blocked += BlockedBatch(
                    c.fileName, batch.batchId, batch.batchOrder,
                    "批次顺序号 ${batch.batchOrder} 与其他批次冲突",
                )
                return@forEach
            }

            // 批次级拒绝：批内 ID 重复 / 停用列表含本批新题（顺序号冲突已单独处理）
            BatchImportPlanner.validateBatch(batch, orderTaken = false)?.let {
                blocked += BlockedBatch(c.fileName, batch.batchId, batch.batchOrder, it.reason)
                return@forEach
            }

            val toInsert = mutableListOf<QuestionEntity>()
            val toUpdate = mutableListOf<QuestionEntity>()
            val duplicates = mutableListOf<DuplicateCandidate>()
            val issues = mutableListOf<BatchIssue>()
            var skipped = 0

            batch.questions.forEachIndexed { index, q ->
                val prior = context.existing[q.questionId]
                if (prior != null) {
                    val entity = BatchImportPlanner.toEntity(
                        q, batch.subject, batch.batchOrder, index, prior.importedAt, batch.batchId,
                    )
                    if (contentDiffers(prior, entity)) toUpdate += entity else skipped++
                } else {
                    val rejection = BatchImportPlanner.validateQuestion(q)
                    if (rejection != null) {
                        issues += BatchIssue(q.questionId, rejection.reason)
                    } else {
                        val stem = QuestionContent.normalizeIdentity(q.stem)
                        val owner = stemOwners[stem]
                        if (owner != null && owner != q.questionId) {
                            duplicates += DuplicateCandidate(batch.batchId, q, index, owner)
                        } else {
                            toInsert += BatchImportPlanner.toEntity(
                                q, batch.subject, batch.batchOrder, index, now, batch.batchId,
                            )
                            stemOwners[stem] = q.questionId
                        }
                    }
                }
            }

            batchPlans += BatchPlan(
                batchId = batch.batchId,
                batchOrder = batch.batchOrder,
                fileName = c.fileName,
                fingerprint = c.fingerprint,
                toInsert = toInsert,
                toUpdate = toUpdate,
                retiredIds = batch.retiredQuestionIds,
                duplicates = duplicates,
                issues = issues,
                skippedCount = skipped,
            )
        }

        // 跨批次合并：同 questionId 只留先到者（按 batchOrder 升序处理，低序批次优先）
        val toInsertAll = mutableListOf<QuestionEntity>()
        val toUpdateAll = mutableListOf<QuestionEntity>()
        val seenIds = mutableSetOf<String>()
        batchPlans.forEach { p ->
            p.toInsert.forEach { if (seenIds.add(it.questionId)) toInsertAll += it }
            p.toUpdate.forEach { if (seenIds.add(it.questionId)) toUpdateAll += it }
        }

        // 停用并集：以**写入后**的题目集合为界，故 0018 停用 0011 新增题这类跨批次停用也成立
        val postWriteIds = context.existing.keys + toInsertAll.map { it.questionId }
        val allRetired = batchPlans.flatMap { it.retiredIds }.distinct()
        val retireIds = allRetired.filter { it in postWriteIds }
        val retiredMissing = allRetired.filter { it !in postWriteIds }

        val records = batchPlans.map { p ->
            ProcessedBatchEntity(
                batchId = p.batchId,
                batchOrder = p.batchOrder,
                status = p.status,
                importedCount = p.toInsert.size + p.toUpdate.size,
                skippedCount = p.skippedCount,
                failedCount = p.failedCount,
                pendingDuplicateCount = p.duplicates.size,
                issuesJson = p.issues.takeIf { it.isNotEmpty() }?.let { BatchJson.json.encodeToString(it) },
                processedAt = now,
                fileName = p.fileName,
                contentHash = p.fingerprint,
            )
        }

        return ReconcilePlan(
            toInsert = toInsertAll,
            toUpdate = toUpdateAll,
            retireIds = retireIds,
            retiredMissing = retiredMissing,
            duplicates = batchPlans.flatMap { it.duplicates },
            batchRecords = records,
            blocked = blocked,
            changedBatches = batchPlans.size,
            unchangedBatches = unchanged,
        )
    }

    /** 只比较**文件可表达的内容列**；本地状态（inactive / 掌握度 / 收藏 / importedAt）不参与 */
    internal fun contentDiffers(a: QuestionEntity, b: QuestionEntity): Boolean =
        a.type != b.type ||
            a.subject != b.subject ||
            a.category != b.category ||
            a.tagsJson != b.tagsJson ||
            a.stem != b.stem ||
            a.optionsJson != b.optionsJson ||
            a.answerJson != b.answerJson ||
            a.explanation != b.explanation ||
            a.sourceJson != b.sourceJson ||
            a.batchOrder != b.batchOrder ||
            a.orderInBatch != b.orderInBatch ||
            a.batchId != b.batchId
}