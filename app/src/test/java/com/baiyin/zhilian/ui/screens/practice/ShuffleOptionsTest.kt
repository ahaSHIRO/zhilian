package com.baiyin.zhilian.ui.screens.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选项稳定打乱单测（PracticeSessionScreen.stablyShuffled）。
 *
 * 背景：真机仅抽查 3 题时「恰好原序」无法与「没打乱」区分（4 选项有 1/24 概率原序），
 * 故逻辑层以下列断言全量覆盖：双射性、同 seed 稳定性、开关行为、统计性。
 */
class ShuffleOptionsTest {

    private val abcd = listOf("A", "B", "C", "D")

    @Test
    fun disabled_returns_original_order() {
        assertEquals(abcd, abcd.stablyShuffled(false, 12345))
    }

    @Test
    fun same_seed_always_yields_same_permutation() {
        val first = abcd.stablyShuffled(true, 42)
        repeat(100) { assertEquals(first, abcd.stablyShuffled(true, 42)) }
    }

    @Test
    fun permutation_is_bijective() {
        // 100 个 seed 下都不丢元素、不重复（否则判分与解析对不上）
        repeat(100) { seed ->
            val out = abcd.stablyShuffled(true, seed)
            assertEquals(abcd.size, out.size)
            assertEquals(abcd.toSet(), out.toSet())
        }
    }

    @Test
    fun empty_and_single_element_survive() {
        assertEquals(emptyList<String>(), emptyList<String>().stablyShuffled(true, 1))
        assertEquals(listOf("A"), listOf("A").stablyShuffled(true, 1))
    }

    @Test
    fun two_element_options_shuffle() {
        // 3 选项题（含干扰项少的单选）也要正常重排
        val out = listOf("A", "B", "C").stablyShuffled(true, 7)
        assertEquals(setOf("A", "B", "C"), out.toSet())
    }

    @Test
    fun shuffled_results_vary_across_seeds() {
        // seed 未生效的退化 bug 会恒返回同一排列——100 seed 至少出现 2 种排列
        val distinct = (0 until 100).map { abcd.stablyShuffled(true, it) }.toSet()
        assertTrue("distinct=${distinct.size}", distinct.size >= 2)
    }

    @Test
    fun occasional_identity_is_legal_but_rare() {
        // 1/24 概率恰好原序是合法随机结果（真机第 1 题即此巧合）；
        // 1000 seed 中原序应占少数（期望约 42），若全部原序说明打乱没跑
        val identityCount = (0 until 1000).count { abcd.stablyShuffled(true, it) == abcd }
        assertTrue("identityCount=$identityCount", identityCount < 200)
    }
}
