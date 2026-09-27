package com.baiyin.zhilian.data.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 填空匹配单测（batch-spec-v1.md：身份比对与填空匹配均在 NFC 规范化后进行）。
 */
class BlankMatchTest {

    @Test
    fun trims_whitespace_on_both_sides() {
        assertTrue(Scoring.isBlankMatch("  fun  ", listOf("fun")))
        assertTrue(Scoring.isBlankMatch("fun", listOf("  fun  ")))
    }

    @Test
    fun is_case_sensitive() {
        // README：填空匹配区分大小写
        assertFalse(Scoring.isBlankMatch("Fun", listOf("fun")))
        assertFalse(Scoring.isBlankMatch("FUN", listOf("fun")))
    }

    @Test
    fun matches_across_unicode_normalization_forms() {
        // 组合重音：NFD 分解形式 vs NFC 合成形式，视觉相同应判对
        val nfc = "caf\u00E9"          // café（单码点 é）
        val nfd = "cafe\u0301"         // café（e + 组合尖音）
        assertEquals(nfc, Scoring.normalizeBlank(nfd))
        assertTrue(Scoring.isBlankMatch(nfd, listOf(nfc)))
        assertTrue(Scoring.isBlankMatch(nfc, listOf(nfd)))
    }

    @Test
    fun accepts_any_of_multiple_acceptable_answers() {
        val acceptable = listOf("fun", "函数")
        assertTrue(Scoring.isBlankMatch(" fun ", acceptable))
        assertTrue(Scoring.isBlankMatch("函数", acceptable))
        assertFalse(Scoring.isBlankMatch("func", acceptable))
    }

    @Test
    fun rejects_non_matching_input() {
        assertFalse(Scoring.isBlankMatch("var", listOf("fun")))
    }

    @Test
    fun normalize_blank_is_nfc_plus_trim() {
        assertEquals("fun", Scoring.normalizeBlank("  fun  "))
        assertEquals("caf\u00E9", Scoring.normalizeBlank("cafe\u0301"))
    }
}
