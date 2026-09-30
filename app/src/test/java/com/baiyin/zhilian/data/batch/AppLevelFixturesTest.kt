package com.baiyin.zhilian.data.batch

import com.baiyin.zhilian.data.question.QuestionContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 应用级校验清单 1–8 的跨语言共同夹具（docs/schema/app-level-fixtures.json）。
 *
 * 为什么需要它：同一套规则有两份实现——应用内权威 [BatchImportPlanner]，与电脑端
 * 投放前的 tools/batch-check.py。两侧各改各的、谁也不会立刻发现，直到某个批次
 * 「电脑端放行、手机端被拒」（或反过来）。共同夹具把两端的判定按规则号钉在同一组
 * 用例上：任何一侧漏掉或改判一条规则，这一测或 `--selftest` 就会红。
 *
 * 每个用例只制造一处违规，两端因此不会因短路顺序不同而给出不同的规则集合。
 * 清单 7（formatVersion）由 Schema 承担，不在夹具内。
 */
class AppLevelFixturesTest {

    @Serializable
    private data class Fixtures(val cases: List<FixtureCase>)

    @Serializable
    private data class FixtureCase(
        val name: String,
        val batch: BatchFileDto,
        val library: List<LibraryBatch> = emptyList(),
        val rules: List<Int> = emptyList(),
    )

    @Serializable
    private data class LibraryBatch(
        val batchId: String,
        val batchOrder: Int,
        val questions: List<LibraryQuestion> = emptyList(),
    )

    @Serializable
    private data class LibraryQuestion(val questionId: String, val stem: String)

    @Test
    fun planner_rules_match_the_shared_fixture() {
        val fixtures = load()
        assertTrue("夹具为空", fixtures.cases.isNotEmpty())
        fixtures.cases.forEach { case ->
            assertEquals("用例「${case.name}」的规则集合不符", case.rules.toSet(), rulesFired(case))
        }
    }

    @Test
    fun fixture_covers_every_rule_the_planner_can_emit() {
        // 覆盖度自检：夹具必须把规划器能产出的规则号都演一遍，
        // 否则「两边一致」可能只是因为两边都还没实现某条规则。
        val covered = load().cases.flatMap { it.rules }.toSet()
        val emittable = setOf(
            BatchRules.ANSWER_REFERENCES_OPTION,
            BatchRules.OPTION_ID_UNIQUE,
            BatchRules.QUESTION_ID_UNIQUE,
            BatchRules.RETIRED_NOT_IN_BATCH,
            BatchRules.CATEGORY_NOT_BLANK,
            BatchRules.BATCH_ORDER_UNIQUE,
            BatchRules.SUSPECTED_DUPLICATE,
        )
        assertEquals("夹具未覆盖：${emittable - covered}", emittable, covered)
    }

    /**
     * 用夹具的输入跑一遍生产校验，收集规则号。
     * 批次级与题目级校验都显式跑（生产里批次级会短路，这里要两侧口径一致地全跑）。
     */
    private fun rulesFired(case: FixtureCase): Set<Int> {
        val rules = mutableSetOf<Int>()
        val orderTaken = case.library.any { it.batchOrder == case.batch.batchOrder }

        BatchImportPlanner.validateBatch(case.batch, orderTaken)?.let { rules += it.rule }
        case.batch.questions.forEach { q ->
            BatchImportPlanner.validateQuestion(q)?.let { rules += it.rule }
        }

        val plan = BatchImportPlanner.plan(
            batch = case.batch,
            context = ImportContext(
                existingIds = case.library.flatMap { b -> b.questions.map { it.questionId } }.toSet(),
                stemOwners = case.library.flatMap { b -> b.questions }
                    .associate { QuestionContent.normalizeIdentity(it.stem) to it.questionId },
                orderTaken = orderTaken,
            ),
            importedAt = 0L,
        )
        if (plan.skippedCount > 0) rules += BatchRules.QUESTION_ID_UNIQUE
        if (plan.duplicates.isNotEmpty()) rules += BatchRules.SUSPECTED_DUPLICATE
        return rules
    }

    /** 夹具放在仓库 docs/schema 下（与它钉住的规格同处），故需从模块目录往上找一层 */
    private fun load(): Fixtures {
        val candidates = listOf(
            File("docs/schema/app-level-fixtures.json"),
            File("../docs/schema/app-level-fixtures.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到共同夹具，试过：${candidates.joinToString { it.absolutePath }}")
        return BatchJson.json.decodeFromString<Fixtures>(file.readText())
    }
}
