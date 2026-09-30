package com.baiyin.zhilian.ui.components

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首帧门控单测：**重订阅不得把门控重新关上**。
 *
 * 锁的是真机上的「题库页疯狂闪烁」：在组合里现造 Flow（每次重组都是新实例）会让收集重启，
 * 而首帧态若绑在「每次收集开始」上，数据到达 → 重组 → 重订阅 → 首帧态 → 再取数据 会自持成环，
 * 整页内容一闪一闪。
 */
class LoadableTest {

    @Test
    fun `初始为首帧态`() {
        assertEquals(Loadable.FirstLoad, LoadableState<Int>().value)
    }

    @Test
    fun `收到值后给出数据`() = runBlocking {
        val state = LoadableState<Int>()

        state.collectFrom(flowOf(1, 2))

        assertEquals(Loadable.Data(2), state.value)
    }

    @Test
    fun `重订阅不会让门控重新关上`() = runBlocking {
        val state = LoadableState<Int>()
        state.collectFrom(flowOf(7))
        assertEquals(Loadable.Data(7), state.value)

        // 模拟「Flow 实例变化」或「回前台导致收集重启」
        state.collectFrom(flowOf(7))

        assertEquals(
            "重订阅回落首帧态会让整页内容消失再出现",
            Loadable.Data(7),
            state.value,
        )
    }

    @Test
    fun `没有值可发的流停在首帧态`() = runBlocking {
        val state = LoadableState<Int>()

        state.collectFrom(emptyFlow())

        assertTrue(state.value is Loadable.FirstLoad)
    }
}
