package com.baiyin.zhilian.data.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标签筛选判定单测。
 *
 * 重点覆盖一个具体的坑：标签以 JSON 数组存储，若改用 LIKE 拼串搜标签，
 * JSON 对标签内引号的转义会导致误命中（搜 `b` 命中 `a"b`），
 * 故实现走解析后集合判定——这里把那类输入钉死，防止有人日后"简化"回 LIKE。
 */
class QuestionTagsTest {

    @Test
    fun matches_any_selected_tag() {
        // 同一维度多选取并集：选中的标签命中任一即可
        assertTrue(QuestionTags.matches("""["coroutines","mutex"]""", setOf("mutex", "channel")))
        assertFalse(QuestionTags.matches("""["coroutines","mutex"]""", setOf("channel")))
    }

    @Test
    fun empty_selection_matches_everything() {
        // 空集合 = 不限该维度，与其余筛选维度的空集合语义一致
        assertTrue(QuestionTags.matches("""["coroutines"]""", emptySet()))
        assertTrue(QuestionTags.matches("[]", emptySet()))
    }

    @Test
    fun does_not_match_by_prefix() {
        // 前缀相近的标签是两个标签，不是同一个
        assertFalse(QuestionTags.matches("""["coroutine"]""", setOf("coroutines")))
        assertFalse(QuestionTags.matches("""["coroutines"]""", setOf("coroutine")))
    }

    @Test
    fun does_not_match_across_quoted_tag() {
        // 反例钉死：标签 a"b 在库里存为 ["a\"b"]，它不含 b 标签。
        // LIKE 拼串搜 "b" 会命中这行，把一道没有 b 标签的题选进来。
        val stored = "[\"a\\\"b\"]"
        assertFalse(QuestionTags.matches(stored, setOf("b")))
        assertTrue(QuestionTags.matches(stored, setOf("a\"b")))
    }

    @Test
    fun empty_tags_match_nothing_when_filtering() {
        assertFalse(QuestionTags.matches("[]", setOf("coroutines")))
    }

    @Test
    fun malformed_json_degrades_to_no_tags() {
        // 解析失败按"该行无标签"处理：不抛异常把练习页带崩，也绝不误命中
        assertEquals(emptyList<String>(), QuestionTags.decode("{not json"))
        assertFalse(QuestionTags.matches("{not json", setOf("coroutines")))
    }

    @Test
    fun distinct_merges_and_sorts_across_questions() {
        val merged = QuestionTags.distinct(
            listOf("""["coroutines","mutex"]""", """["channel"]""", """["coroutines"]""", "[]")
        )
        assertEquals(listOf("channel", "coroutines", "mutex"), merged)
    }
}
