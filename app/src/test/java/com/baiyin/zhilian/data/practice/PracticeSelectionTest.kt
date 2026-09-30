package com.baiyin.zhilian.data.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 练习筛选选择与级联收窄单测（CONTEXT.md「练习筛选条件」）。
 *
 * 锁的是配置页那几条「收窄」规则：科目变→分类收窄→标签收窄。规则原先散在三个
 * LaunchedEffect 里，只表现为「chip 莫名其妙消失/残留」，肉眼回归很难发现。
 */
class PracticeSelectionTest {

    @Test
    fun narrowing_categories_drops_selections_that_left_the_available_set() {
        val selection = PracticeSelection(subjects = setOf("kotlin"), categories = setOf("协程", "集合"))
        val narrowed = selection.withCategories(listOf("协程", "流"))
        assertEquals(setOf("协程"), narrowed.categories)
    }

    @Test
    fun narrowing_tags_drops_selections_that_left_the_available_set() {
        val selection = PracticeSelection(categories = setOf("协程"), tags = setOf("launch", "mutex"))
        assertEquals(setOf("mutex"), selection.withTags(listOf("mutex", "async")).tags)
    }

    @Test
    fun narrowing_keeps_the_selection_when_it_still_exists() {
        val selection = PracticeSelection(categories = setOf("协程"))
        assertEquals(selection, selection.withCategories(listOf("协程", "集合")))
    }

    @Test
    fun only_subject_is_set_for_a_single_subject_only() {
        assertEquals("kotlin", PracticeSelection(subjects = setOf("kotlin")).onlySubject)
        // 多选科目取并集，分类/标签不限定于单一科目
        assertNull(PracticeSelection(subjects = setOf("kotlin", "java")).onlySubject)
        assertNull(PracticeSelection().onlySubject)
    }

    @Test
    fun to_filter_carries_every_dimension() {
        val filter = PracticeSelection(
            subjects = setOf("kotlin"),
            categories = setOf("协程"),
            tags = setOf("launch"),
            types = setOf("single_choice"),
            onlyWrong = true,
            onlyFavorite = true,
            sequential = false,
            limit = 35,
        ).toFilter()

        assertEquals(setOf("kotlin"), filter.subjects)
        assertEquals(setOf("协程"), filter.categories)
        assertEquals(setOf("launch"), filter.tags)
        assertEquals(setOf("single_choice"), filter.types)
        assertTrue(filter.onlyWrong)
        assertTrue(filter.onlyFavorite)
        assertFalse(filter.sequential)
        assertEquals(35, filter.limit)
    }

    @Test
    fun defaults_are_unlimited_and_sequential() {
        val selection = PracticeSelection()
        assertEquals(PracticeSelection.DEFAULT_LIMIT, selection.limit)
        assertTrue(selection.sequential) // 默认顺序
        assertTrue(selection.toFilter().subjects.isEmpty()) // 空 = 不限
    }
}
