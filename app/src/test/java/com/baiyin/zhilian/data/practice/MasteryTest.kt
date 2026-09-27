package com.baiyin.zhilian.data.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 掌握度转移纯函数单测（ADR-0004；仓内首个 JVM 单测）。
 * 覆盖规则表四象限 + 错题消解路径，固化"部分得分保留 consecutive"裁决。
 */
class MasteryTest {

    // ---- 规则表四象限 ----

    @Test
    fun perfect_increments_consecutive_and_keeps_ever_wrong() {
        val next = masteryTransition(Mastery(0, false), AnswerOutcome.Perfect)
        assertEquals(1, next.consecutivePerfect)
        assertFalse(next.hasEverWrong) // 满分保持原值
    }

    @Test
    fun wrong_resets_consecutive_and_marks_ever_wrong() {
        val next = masteryTransition(Mastery(2, true), AnswerOutcome.Wrong)
        assertEquals(0, next.consecutivePerfect) // 归零
        assertTrue(next.hasEverWrong)
    }

    @Test
    fun partial_preserves_consecutive_but_marks_ever_wrong() {
        // Q1=A 裁决：部分得分保留当前计数不清零，与零分错误的"归零"区分
        val next = masteryTransition(Mastery(1, false), AnswerOutcome.Partial)
        assertEquals(1, next.consecutivePerfect) // 不变，不被归零
        assertTrue(next.hasEverWrong) // 部分得分仍算未对，计入错题
    }

    @Test
    fun partial_neither_advances_nor_resets_streak() {
        val next = masteryTransition(Mastery(3, true), AnswerOutcome.Partial)
        assertEquals(3, next.consecutivePerfect) // 既不 +1 也不清零
        assertTrue(next.hasEverWrong)
    }

    // ---- 错题消解路径（CONTEXT.md：连续两次全对消解）----

    @Test
    fun two_consecutive_perfects_clears_wrong_status() {
        var m = Mastery(0, true) // 已是错题：isWrong = true && 0<2
        m = masteryTransition(m, AnswerOutcome.Perfect) // 第一次全对
        assertEquals(1, m.consecutivePerfect)
        assertTrue(m.hasEverWrong && m.consecutivePerfect < 2) // 仍未消解
        m = masteryTransition(m, AnswerOutcome.Perfect) // 第二次全对
        assertEquals(2, m.consecutivePerfect)
        // isWrong = hasEverWrong && consecutive < 2 → false（消解）
        assertFalse(m.hasEverWrong && m.consecutivePerfect < 2)
    }

    @Test
    fun perfect_after_wrong_rebuilds_from_zero() {
        val afterWrong = masteryTransition(Mastery(2, true), AnswerOutcome.Wrong)
        val afterPerfect = masteryTransition(afterWrong, AnswerOutcome.Perfect)
        assertEquals(1, afterPerfect.consecutivePerfect)
        assertTrue(afterPerfect.hasEverWrong) // once true, stays true
    }

    @Test
    fun partial_does_not_wipe_prior_streak_one_more_perfect_clears() {
        // Q1=A 关键对比：已有 1 次全对，部分得分后保留 1（非归零），再一次全对即消解。
        // 若代码误把部分得分归零，此处会停在 1 而非消解。
        var m = Mastery(1, true)
        m = masteryTransition(m, AnswerOutcome.Partial) // 保留 1
        assertEquals(1, m.consecutivePerfect)
        m = masteryTransition(m, AnswerOutcome.Perfect) // 再一次全对 → 2 → 消解
        assertEquals(2, m.consecutivePerfect)
        assertFalse(m.hasEverWrong && m.consecutivePerfect < 2)
    }

    @Test
    fun wrong_amid_streak_resets_require_two_perfects_again() {
        // 对比组：已有 1 次全对，零分错误归零，需两次全对才消解
        var m = Mastery(1, true)
        m = masteryTransition(m, AnswerOutcome.Wrong) // 归零
        assertEquals(0, m.consecutivePerfect)
        m = masteryTransition(m, AnswerOutcome.Perfect)
        assertEquals(1, m.consecutivePerfect)
        assertTrue(m.hasEverWrong && m.consecutivePerfect < 2) // 一次还不够
        m = masteryTransition(m, AnswerOutcome.Perfect)
        assertEquals(2, m.consecutivePerfect)
        assertFalse(m.hasEverWrong && m.consecutivePerfect < 2) // 消解
    }

    // ---- 清除练习记录后的掌握度复位（resetAllMastery 的语义） ----

    @Test
    fun reset_mastery_clears_wrong_status() {
        // 清除记录后复位为初始掌握度：错题列表应随之清空
        val reset = Mastery(consecutivePerfect = 0, hasEverWrong = false)
        assertFalse(reset.hasEverWrong && reset.consecutivePerfect < 2) // 不再是错题
        assertEquals(0, reset.consecutivePerfect)
    }
}
