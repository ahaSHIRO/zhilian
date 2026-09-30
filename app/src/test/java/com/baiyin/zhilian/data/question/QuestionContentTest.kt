package com.baiyin.zhilian.data.question

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题目内容解码单测。
 *
 * 这里锁的是「一份解码服务四个调用方」的前提：四种题型的分派必须类型正确，
 * 缺字段与坏 JSON 一律兜底而不抛。判分、练习卡、题库预览与导入都踩在这块地面上，
 * 谁读错，另外三处跟着错。
 */
class QuestionContentTest {

    @Test
    fun options_decode_choice_rows() {
        val rows = QuestionContent.options("""[{"optionId":"A","text":"甲"},{"optionId":"B","text":"乙"}]""")
        assertEquals(2, rows.size)
        assertEquals("A", rows[0].optionId)
        assertEquals("乙", rows[1].text)
    }

    @Test
    fun options_missing_or_broken_is_empty() {
        assertTrue(QuestionContent.options(null).isEmpty())
        assertTrue(QuestionContent.options("{not json").isEmpty())
    }

    @Test
    fun single_answer_reads_string_and_degrades_to_null() {
        assertEquals("A", QuestionContent.singleAnswer("\"A\""))
        assertNull(QuestionContent.singleAnswer("[\"A\"]"))
        assertNull(QuestionContent.singleAnswer("{not json"))
    }

    @Test
    fun multiple_answers_reads_array() {
        assertEquals(listOf("A", "C"), QuestionContent.multipleAnswers("""["A","C"]"""))
        assertTrue(QuestionContent.multipleAnswers("\"A\"").isEmpty())
    }

    @Test
    fun true_false_answer_reads_boolean() {
        assertEquals(true, QuestionContent.trueFalseAnswer("true"))
        assertEquals(false, QuestionContent.trueFalseAnswer("false"))
        // 坏 JSON 按 false 兜底，不把练习页带崩
        assertEquals(false, QuestionContent.trueFalseAnswer("{not json"))
    }

    @Test
    fun blank_acceptables_share_the_array_shape() {
        assertEquals(listOf("fun", "函数"), QuestionContent.blankAcceptables("""["fun","函数"]"""))
    }

    @Test
    fun correct_option_ids_are_type_dispatched_once() {
        assertEquals(setOf("A"), QuestionContent.correctOptionIds("single_choice", "\"A\""))
        assertEquals(setOf("A", "C"), QuestionContent.correctOptionIds("multiple_choice", """["A","C"]"""))
        // 判断与填空没有选项，易错点：不能把 true / ["x"] 当成选项 ID
        assertTrue(QuestionContent.correctOptionIds("true_false", "true").isEmpty())
        assertTrue(QuestionContent.correctOptionIds("fill_in_blank", """["x"]""").isEmpty())
    }

    @Test
    fun source_decodes_and_degrades_to_null() {
        val source = QuestionContent.source("""{"title":"文档","accessedDate":"2026-01-01"}""")
        assertEquals("文档", source?.title)
        assertNull(QuestionContent.source("{not json"))
    }

    @Test
    fun normalize_identity_is_nfc_plus_trim() {
        assertEquals("fun", QuestionContent.normalizeIdentity("  fun  "))
        // NFD 分解形式（e + 组合尖音）应折叠为 NFC 单码点形式
        assertEquals("caf\u00E9", QuestionContent.normalizeIdentity("cafe\u0301"))
        assertEquals("caf\u00E9", QuestionContent.normalizeIdentity("  cafe\u0301 "))
    }
}
