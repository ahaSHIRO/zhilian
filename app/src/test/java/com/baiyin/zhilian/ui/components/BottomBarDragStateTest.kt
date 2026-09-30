package com.baiyin.zhilian.ui.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拖动状态机的不变量测试（ADR-0010 修订）。
 *
 * 锁的是真实踩过的坑：**拖动期间按压态不得被撤销**。
 *
 * 现象：拖动跟手与玻璃都正常，但胶囊不膨胀（`progress` 全程 0）。
 * 根因：子项手势在父层 `consume()` 后收到「取消」，却仍无条件撤回按压，
 * 把父层刚建立的鼓起态拆掉（详见 pitfalls 2.12）。
 *
 * 这类 bug 不崩不报错、只表现为「动画不对劲」，肉眼回归很难发现，
 * 故这里锁**决策结果**（`releasePress()` 是否被接受）而非动画进度——
 * 决策是纯逻辑，无需驱动协程动画即可断言。
 */
class BottomBarDragStateTest {

    /** 用真实调度器即可：本测试只断言决策返回值与纯状态字段，不等待动画收敛 */
    private fun newState(tabCount: Int = 4, initialIndex: Int = 0): BottomBarDragState {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return BottomBarDragState(scope, tabCount, initialIndex)
    }

    @Test
    fun `releasePress during drag is rejected`() {
        val state = newState()

        state.press()
        state.beginDrag()

        assertFalse(
            "拖动期间 releasePress 必须被拒绝，否则胶囊不膨胀",
            state.releasePress(),
        )
        assertTrue(state.isDragging)
    }

    @Test
    fun `releasePress is accepted when not dragging`() {
        val state = newState()

        state.press()
        // 点击抬手的路径：未拖动，应当正常撤销
        assertTrue(state.releasePress())
    }

    @Test
    fun `press is idempotent while animation is in flight`() {
        val state = newState()

        // 父层与子项可能都上报按下；第二次起不得再启动动画（否则鼓起会抖动）
        assertTrue("首次按下应被接受", state.press())
        assertFalse("动画进行中不得重复启动", state.press())
    }

    @Test
    fun `drag clamps position inside range`() {
        val state = newState(tabCount = 4)

        state.beginDrag()
        state.drag(99f)
        assertEquals(3f, state.value, 1e-3f)

        state.drag(-99f)
        assertEquals(0f, state.value, 1e-3f)
    }

    @Test
    fun `nearestIndex rounds to closest tab`() {
        val state = newState(tabCount = 4)

        state.beginDrag()
        state.drag(0.4f)
        assertEquals(0, state.nearestIndex())

        state.drag(0.6f)
        assertEquals(1, state.nearestIndex())

        state.drag(2.5f)
        assertEquals(3, state.nearestIndex())
    }

    @Test
    fun `syncTo is ignored while dragging`() {
        val state = newState(tabCount = 4, initialIndex = 1)

        state.beginDrag()
        state.drag(2f)
        // 拖动中外部同步不得抢走位置（否则胶囊会被拽回选中项、与手指打架）
        state.syncTo(0)
        assertEquals(2f, state.value, 1e-3f)
    }

    @Test
    fun `settle ends dragging so press can be released again`() {
        val state = newState()

        state.press()
        state.beginDrag()
        assertFalse(state.releasePress())

        state.settle { }
        assertFalse("松手后不再处于拖动中", state.isDragging)
        // 拖动结束后撤销应恢复可用
        assertTrue(state.releasePress())
    }

    @Test
    fun `cancelDrag also ends dragging`() {
        val state = newState()

        state.press()
        state.beginDrag()
        state.cancelDrag()

        assertFalse(state.isDragging)
        assertTrue(state.releasePress())
    }
}
