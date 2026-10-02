package com.baiyin.zhilian.data.batch

import com.baiyin.zhilian.data.db.StemOwnerRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次导入规划单测（batch-spec-v1.md §应用级校验清单 1–8）。
 *
 * 这是本仓此前覆盖率为零、爆炸半径最大的一块：导入出错要么整批被拒、要么静默跳题。
 * 规则抽成纯模块后，清单里的每一条都能在这里钉死，不必插一台真机。
 * 与 tools/batch-check.py 的跨语言一致性由 [AppLevelFixturesTest] 的共同夹具约束。
 */
class BatchImportPlannerTest {

    private fun q(
        id: String = "11111111-1111-1111-1111-111111111111",
        type: String = "single_choice",
        category: String = "协程",
        stem: String = "题干",
        options: List<OptionDto>? = listOf(OptionDto("A", "甲"), OptionDto("B", "乙")),
        answer: JsonElement? = JsonPrimitive("A"),
        acceptableAnswers: List<String>? = null,
    ) = QuestionDto(
        questionId = id,
        type = type,
        category = category,
        stem = stem,
        options = options,
        answer = answer,
        acceptableAnswers = acceptableAnswers,
        explanation = "解析",
        source = SourceDto(title = "文档", accessedDate = "2026-01-01"),
    )

    private fun batch(
        questions: List<QuestionDto> = listOf(q()),
        retired: List<String> = emptyList(),
        batchOrder: Int = 1,
    ) = BatchFileDto(
        formatVersion = 1,
        batchId = "22222222-2222-2222-2222-222222222222",
        batchOrder = batchOrder,
        createdAt = "2026-01-01",
        subject = "kotlin",
        questions = questions,
        retiredQuestionIds = retired,
    )

    private fun emptyContext() = ImportContext(existingIds = emptySet(), stemOwners = emptyMap(), orderTaken = false)

    // ---- 批次级：清单 3 前半 / 4 / 6 ----

    @Test
    fun batch_rejects_duplicate_question_ids() {
        val rejection = BatchImportPlanner.validateBatch(batch(questions = listOf(q(), q())), orderTaken = false)
        assertEquals(BatchRules.QUESTION_ID_UNIQUE, rejection?.rule)
        assertTrue(rejection!!.reason.contains("批次内题目 ID 重复"))
    }

    @Test
    fun batch_rejects_retiring_its_own_new_question() {
        val rejection = BatchImportPlanner.validateBatch(
            batch(retired = listOf("11111111-1111-1111-1111-111111111111")),
            orderTaken = false,
        )
        assertEquals(BatchRules.RETIRED_NOT_IN_BATCH, rejection?.rule)
        assertTrue(rejection!!.reason.contains("停用列表包含本批次新题"))
    }

    @Test
    fun batch_rejects_taken_order_number() {
        val rejection = BatchImportPlanner.validateBatch(batch(batchOrder = 7), orderTaken = true)
        assertEquals(BatchRules.BATCH_ORDER_UNIQUE, rejection?.rule)
        assertTrue(rejection!!.reason.contains("批次顺序号 7"))
    }

    @Test
    fun batch_passes_with_no_violation() {
        assertNull(BatchImportPlanner.validateBatch(batch(), orderTaken = false))
    }

    // ---- 题目级：清单 1 / 2 / 5 ----

    @Test
    fun question_rejects_answer_referencing_unknown_option() {
        val rejection = BatchImportPlanner.validateQuestion(q(answer = JsonPrimitive("D")))
        assertEquals(BatchRules.ANSWER_REFERENCES_OPTION, rejection?.rule)
        assertEquals("答案引用了不存在的选项 D", rejection?.reason)
    }

    @Test
    fun question_rejects_duplicate_option_ids() {
        val dup = q(options = listOf(OptionDto("A", "甲"), OptionDto("A", "乙")))
        assertEquals(BatchRules.OPTION_ID_UNIQUE, BatchImportPlanner.validateQuestion(dup)?.rule)
    }

    @Test
    fun question_rejects_blank_category() {
        val rejection = BatchImportPlanner.validateQuestion(q(category = "   "))
        assertEquals(BatchRules.CATEGORY_NOT_BLANK, rejection?.rule)
        assertEquals("分类名为空", rejection?.reason)
    }

    @Test
    fun question_rejects_structural_gaps_schema_already_covers() {
        // 缺选项、缺答案是清单外的补充检查（Schema 已要求），规则号记 0
        assertEquals(BatchRules.EXTRA, BatchImportPlanner.validateQuestion(q(options = null))?.rule)
        assertEquals(BatchRules.EXTRA, BatchImportPlanner.validateQuestion(q(answer = null))?.rule)
    }

    @Test
    fun question_accepts_multiple_choice_with_known_options() {
        val ok = q(type = "multiple_choice", answer = JsonArray(listOf(JsonPrimitive("A"), JsonPrimitive("B"))))
        assertNull(BatchImportPlanner.validateQuestion(ok))
    }

    // ---- 规划：清单 3 后半 / 8 ----

    @Test
    fun already_existing_question_is_skipped_and_reported() {
        val item = q()
        val plan = BatchImportPlanner.plan(
            batch(questions = listOf(item)),
            emptyContext().copy(existingIds = setOf(item.questionId)),
            importedAt = 0L,
        )
        assertTrue(plan.toInsert.isEmpty())
        assertEquals(1, plan.skippedCount)
        assertEquals(0, plan.failedCount) // 跳过不算失败
        assertEquals(ImportPlan.STATUS_IMPORTED, plan.status)
    }

    @Test
    fun same_stem_with_other_id_is_flagged_as_duplicate_not_inserted() {
        val item = q(stem = "同一个题干")
        val plan = BatchImportPlanner.plan(
            batch(questions = listOf(item)),
            emptyContext().copy(stemOwners = mapOf("同一个题干" to "existing-id")),
            importedAt = 0L,
        )
        assertTrue(plan.toInsert.isEmpty())
        assertEquals(1, plan.duplicates.size)
        assertEquals("existing-id", plan.duplicates.first().existingQuestionId)
        // 有待决重复即未完成，可重试（statusLabel 语义）
        assertEquals(ImportPlan.STATUS_PARTIAL, plan.status)
    }

    @Test
    fun stem_lookup_uses_normalized_identity() {
        // 归一化后的题干才能命中既有题：首尾空白 / NFD 形式不该漏判
        val item = q(stem = "  题干  ")
        val plan = BatchImportPlanner.plan(
            batch(questions = listOf(item)),
            emptyContext().copy(stemOwners = mapOf("题干" to "existing-id")),
            importedAt = 0L,
        )
        assertEquals(1, plan.duplicates.size)
    }

    @Test
    fun valid_question_goes_to_insert_with_batch_metadata() {
        val item = q()
        val plan = BatchImportPlanner.plan(batch(questions = listOf(item), batchOrder = 9), emptyContext(), importedAt = 55L)
        assertEquals(1, plan.toInsert.size)
        val entity = plan.toInsert.first()
        assertEquals(9, entity.batchOrder)
        assertEquals(0, entity.orderInBatch)
        assertEquals("kotlin", entity.subject)
        assertEquals(55L, entity.importedAt)
        assertEquals(ImportPlan.STATUS_IMPORTED, plan.status)
    }

    @Test
    fun invalid_question_is_reported_and_does_not_fail_the_whole_batch() {
        val plan = BatchImportPlanner.plan(
            batch(questions = listOf(q(id = "bad", answer = JsonPrimitive("D")), q(id = "good"))),
            emptyContext(),
            importedAt = 0L,
        )
        assertEquals(1, plan.toInsert.size)
        assertEquals(1, plan.failedCount)
        assertEquals(ImportPlan.STATUS_PARTIAL, plan.status)
    }

    @Test
    fun retiring_known_and_unknown_ids_splits_into_action_and_warning() {
        val plan = BatchImportPlanner.plan(
            batch(retired = listOf("known", "missing")),
            emptyContext().copy(existingIds = setOf("known")),
            importedAt = 0L,
        )
        assertEquals(listOf("known"), plan.retiredIds)
        assertEquals(listOf("missing"), plan.missingRetired)
        assertEquals(1, plan.warnings.size)
        assertEquals(0, plan.failedCount) // 无害提示不计失败、不影响状态
        assertEquals(ImportPlan.STATUS_IMPORTED, plan.status)
    }

    // ---- 实体映射 ----

    @Test
    fun entity_mapping_normalizes_identity_fields() {
        val item = q(category = "  协程  ", stem = "cafe\u0301", type = "fill_in_blank", options = null, answer = null, acceptableAnswers = listOf("fun"))
        val entity = BatchImportPlanner.toEntity(item, subject = "kotlin", batchOrder = 1, orderInBatch = 3, importedAt = 0L)

        assertEquals("协程", entity.category)
        assertEquals("caf\u00E9", entity.stem) // NFC
        assertEquals(3, entity.orderInBatch)
        // 填空题的 answer_json 取 acceptableAnswers 而非 answer 节点
        assertEquals("""["fun"]""", entity.answerJson)
        assertNull(entity.optionsJson)
    }

    @Test
    fun entity_mapping_encodes_options_and_source_as_json() {
        val entity = BatchImportPlanner.toEntity(q(), subject = "kotlin", batchOrder = 1, orderInBatch = 0, importedAt = 0L)
        assertNotNull(entity.optionsJson)
        assertTrue(entity.optionsJson!!.contains("\"optionId\":\"A\""))
        assertTrue(entity.sourceJson.contains("文档"))
        assertEquals("[]", entity.tagsJson)
    }

    // ---- 完成态判定与裁决结果（ADR-0012）----
    // resolveDuplicate 的多 DAO 事务原子性靠端到端验证（MuMu），但「裁决 → 状态升级 → 计数增量」
    // 这步规则现已是可直接问的纯函数——这里把四象限与计数增量钉死，事务体里就只剩读写。

    @Test
    fun batch_status_is_imported_only_without_failure_and_pending() {
        // 四象限：仅「无失败 && 无待决」判完成，其余三格一律 PARTIAL
        assertEquals(ImportPlan.STATUS_IMPORTED, BatchImportPlanner.batchStatus(failedCount = 0, hasPending = false))
        assertEquals(ImportPlan.STATUS_PARTIAL, BatchImportPlanner.batchStatus(failedCount = 0, hasPending = true))
        assertEquals(ImportPlan.STATUS_PARTIAL, BatchImportPlanner.batchStatus(failedCount = 1, hasPending = false))
        assertEquals(ImportPlan.STATUS_PARTIAL, BatchImportPlanner.batchStatus(failedCount = 1, hasPending = true))
    }

    @Test
    fun resolve_outcome_importing_last_pending_completes_and_bumps_count() {
        val outcome = BatchImportPlanner.resolveOutcome(
            failedCount = 0, importedCount = 2, import = true, remaining = 0,
        )
        assertEquals(ImportPlan.STATUS_IMPORTED, outcome.newStatus)
        assertEquals(3, outcome.newImportedCount)
    }

    @Test
    fun resolve_outcome_importing_with_pending_left_stays_partial() {
        val outcome = BatchImportPlanner.resolveOutcome(
            failedCount = 0, importedCount = 2, import = true, remaining = 1,
        )
        assertEquals(ImportPlan.STATUS_PARTIAL, outcome.newStatus)
        assertEquals(3, outcome.newImportedCount)
    }

    @Test
    fun resolve_outcome_skipping_does_not_bump_imported_count() {
        val outcome = BatchImportPlanner.resolveOutcome(
            failedCount = 0, importedCount = 2, import = false, remaining = 0,
        )
        assertEquals(ImportPlan.STATUS_IMPORTED, outcome.newStatus)
        assertEquals(2, outcome.newImportedCount)
    }

    @Test
    fun resolve_outcome_skipping_with_pending_left_stays_partial() {
        val outcome = BatchImportPlanner.resolveOutcome(
            failedCount = 0, importedCount = 2, import = false, remaining = 1,
        )
        assertEquals(ImportPlan.STATUS_PARTIAL, outcome.newStatus)
        assertEquals(2, outcome.newImportedCount)
    }

    @Test
    fun resolve_outcome_never_completes_a_batch_with_failures() {
        // 有失败时即使待决清空也不升 IMPORTED——原内联实现此处保留 record.status，
        // 是「有待决却已记 IMPORTED」这类不一致态的潜在漂移点，收口后统一为 PARTIAL
        val outcome = BatchImportPlanner.resolveOutcome(
            failedCount = 1, importedCount = 2, import = true, remaining = 0,
        )
        assertEquals(ImportPlan.STATUS_PARTIAL, outcome.newStatus)
        assertEquals(3, outcome.newImportedCount)
    }

    // ---- buildImportContext / toOutcome（ADR-0013）----

    @Test
    fun build_import_context_dedupes_and_maps() {
        val ctx = BatchImportPlanner.buildImportContext(
            existingIds = listOf("a", "b", "a"), // 去重
            stemOwners = listOf(
                StemOwnerRow("id1", "题干1"),
                StemOwnerRow("id2", "题干2"),
                StemOwnerRow("id3", "题干1"), // 同题干 → 取后值
            ),
            orderTaken = true,
        )
        assertEquals(setOf("a", "b"), ctx.existingIds)
        assertEquals(mapOf("题干1" to "id3", "题干2" to "id2"), ctx.stemOwners)
        assertTrue(ctx.orderTaken)
    }

    @Test
    fun to_outcome_copies_fields_and_transparent_status() {
        val plan = ImportPlan(
            toInsert = emptyList(),
            skippedCount = 1,
            issues = listOf(BatchIssue("id1", "跳过"), BatchIssue("id2", "失败")), // 2 issue，1 跳过 → failed=1
            warnings = listOf(BatchIssue("id3", "警告")),
            duplicates = emptyList(),
            retiredIds = listOf("old"),
            missingRetired = listOf("missing"),
        )
        val outcome = BatchImportPlanner.toOutcome(plan, "batch-1", 9001, "test.json")

        assertEquals("batch-1", outcome.batchId)
        assertEquals(9001, outcome.batchOrder)
        assertEquals("test.json", outcome.fileName)
        assertEquals(0, outcome.importedCount) // toInsert 空
        assertEquals(1, outcome.skippedCount)
        assertEquals(1, outcome.failedCount) // issues.size - skippedCount = 2-1
        assertEquals(1, outcome.retiredCount)
        assertEquals(2, outcome.issues.size)
        assertEquals(1, outcome.warnings.size)
        assertEquals(ImportPlan.STATUS_PARTIAL, outcome.status) // failedCount>0 → PARTIAL
    }
}
