package com.baiyin.zhilian.data.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 批次内容指纹单测：稳定性（只重导出不该判为变了）与敏感性（内容一变必须判出来）。
 * 指纹是对账「跳过已对账批次」的判据，错判要么白跑、要么漏更新。
 */
class BatchFingerprintTest {

    private fun q(id: String = "q1", stem: String = "题干") = QuestionDto(
        questionId = id,
        type = "single_choice",
        category = "协程",
        tags = listOf("flow"),
        stem = stem,
        options = listOf(OptionDto("A", "甲"), OptionDto("B", "乙")),
        answer = kotlinx.serialization.json.JsonPrimitive("A"),
        explanation = "解析",
        source = SourceDto(title = "文档", accessedDate = "2026-01-01"),
    )

    private fun batch(
        questions: List<QuestionDto> = listOf(q()),
        retired: List<String> = emptyList(),
        batchId: String = "b1",
        batchOrder: Int = 1,
        subject: String = "kotlin",
        createdAt: String = "2026-01-01",
        formatVersion: Int = 1,
    ) = BatchFileDto(
        formatVersion = formatVersion,
        batchId = batchId,
        batchOrder = batchOrder,
        createdAt = createdAt,
        subject = subject,
        questions = questions,
        retiredQuestionIds = retired,
    )

    @Test
    fun `同样内容指纹一致`() {
        assertEquals(BatchFingerprint.of(batch()), BatchFingerprint.of(batch()))
    }

    @Test
    fun `导出元数据变化不改变指纹`() {
        // createdAt / formatVersion / batchId 与题库内容无关，不应触发对账
        val a = batch(createdAt = "2026-01-01")
        val b = batch(createdAt = "2026-09-09", batchId = "other")
        assertEquals(BatchFingerprint.of(a), BatchFingerprint.of(b))
    }

    @Test
    fun `题干变化改变指纹`() {
        assertNotEquals(BatchFingerprint.of(batch()), BatchFingerprint.of(batch(questions = listOf(q(stem = "新题干")))))
    }

    @Test
    fun `停用列表变化改变指纹`() {
        assertNotEquals(BatchFingerprint.of(batch()), BatchFingerprint.of(batch(retired = listOf("old"))))
    }

    @Test
    fun `顺序号与科目变化改变指纹`() {
        assertNotEquals(BatchFingerprint.of(batch()), BatchFingerprint.of(batch(batchOrder = 2)))
        assertNotEquals(BatchFingerprint.of(batch()), BatchFingerprint.of(batch(subject = "java")))
    }

    @Test
    fun `题序变化改变指纹`() {
        val two = listOf(q("q1"), q("q2", stem = "乙题"))
        assertNotEquals(
            BatchFingerprint.of(batch(questions = two)),
            BatchFingerprint.of(batch(questions = two.reversed())),
        )
    }
}