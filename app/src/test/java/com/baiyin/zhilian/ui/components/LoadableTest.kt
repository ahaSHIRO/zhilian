package com.baiyin.zhilian.ui.components

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 首帧门控单测：第一个值到达之前必须能被识别出来——它正是「不要渲染假终态」的依据。
 */
class LoadableTest {

    @Test
    fun `首个值到达前给出首帧态`() = runBlocking {
        val emitted = flowOf("a", "b").asLoadable().toList()

        assertEquals(
            listOf(Loadable.FirstLoad, Loadable.Data("a"), Loadable.Data("b")),
            emitted,
        )
    }

    @Test
    fun `没有值可发的流一直停在首帧态`() = runBlocking {
        assertEquals(listOf(Loadable.FirstLoad), emptyFlow<String>().asLoadable().toList())
    }
}
