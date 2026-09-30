package com.baiyin.zhilian.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 底栏拖动纯计算测试（ADR-0010 修订）。
 *
 * 锁两件容易调坏又不会崩的行为：
 * 1. **中间段严格线性**——拖一格的位移必须正好推进一格，否则跟手会漂；
 * 2. **两端有阻尼且不失控**——拖出边界只能是渐进值，绝不能反超或跳变。
 */
class BottomBarDragMathTest {

    private val tabCount = 4
    private val slotPx = 100f

    // ---- overScrollDamped：边界阻尼 ----

    @Test
    fun `inside range keeps raw value untouched`() {
        // 0..3 区间内原样返回：中间段必须严格线性，否则胶囊跟不上手指
        assertEquals(0f, BottomBarDragMath.overScrollDamped(0f, tabCount), 1e-4f)
        assertEquals(1.5f, BottomBarDragMath.overScrollDamped(1.5f, tabCount), 1e-4f)
        assertEquals(3f, BottomBarDragMath.overScrollDamped(3f, tabCount), 1e-4f)
    }

    @Test
    fun `beyond left edge is damped`() {
        val damped = BottomBarDragMath.overScrollDamped(-1f, tabCount)
        // 被压缩到原位移的一小部分，且方向不变（仍为负）
        assertEquals(-BottomBarDragMath.OVERSCROLL_RESISTANCE, damped, 1e-4f)
    }

    @Test
    fun `beyond right edge is damped`() {
        val damped = BottomBarDragMath.overScrollDamped(4f, tabCount)
        // 从末格起只前进被阻尼过的一小段
        assertEquals(
            3f + BottomBarDragMath.OVERSCROLL_RESISTANCE,
            damped,
            1e-4f,
        )
    }

    @Test
    fun `damping never overshoots the raw value`() {
        // 阻尼后的越界位移绝不能反超原始位移（否则拖到头反而"飞出去"）
        val raw = 10f
        val damped = BottomBarDragMath.overScrollDamped(raw, tabCount)
        assertEquals(true, damped < raw)
    }

    // ---- indexForDrag：位移折算 ----

    @Test
    fun `dragging one slot advances exactly one tab`() {
        // 从第 0 格向右拖一整格 → 正好到第 1 格
        assertEquals(
            1f,
            BottomBarDragMath.indexForDrag(startIndex = 0f, dragPx = slotPx, slotPx = slotPx, tabCount = tabCount),
            1e-4f,
        )
    }

    @Test
    fun `dragging half slot advances half tab`() {
        // 半个槽位 = 半格，保证跟手是连续的而非吸附跳格
        assertEquals(
            1.5f,
            BottomBarDragMath.indexForDrag(startIndex = 1f, dragPx = slotPx / 2f, slotPx = slotPx, tabCount = tabCount),
            1e-4f,
        )
    }

    @Test
    fun `dragging far right stops with damping near last tab`() {
        val index = BottomBarDragMath.indexForDrag(
            startIndex = 0f,
            dragPx = slotPx * 20f,
            slotPx = slotPx,
            tabCount = tabCount,
        )
        // 远超末格时只能停在末格附近（阻尼区间内），不会跑飞
        assertEquals(true, index > 3f)
        assertEquals(true, index < 3f + 20f * BottomBarDragMath.OVERSCROLL_RESISTANCE)
    }

    @Test
    fun `zero slot width is safe`() {
        // 布局尚未测量出宽度时 slotPx 可能为 0，不能除零产出 NaN
        val index = BottomBarDragMath.indexForDrag(
            startIndex = 2f,
            dragPx = 50f,
            slotPx = 0f,
            tabCount = tabCount,
        )
        assertEquals(2f, index, 1e-4f)
    }

    // ---- 拉伸 ----

    @Test
    fun `no velocity means no stretch`() {
        assertEquals(1f, BottomBarDragMath.stretchX(0f), 1e-4f)
        assertEquals(1f, BottomBarDragMath.stretchY(0f), 1e-4f)
    }

    @Test
    fun `stretch is equal in magnitude for both directions`() {
        // 关键：往左甩与往右甩都必须「拉长」。若直接用带符号速度，往左会算成收缩（曾在实现里踩到）
        assertEquals(
            BottomBarDragMath.stretchX(0.4f),
            BottomBarDragMath.stretchX(-0.4f),
            1e-4f,
        )
        // 两个方向都必须是拉伸（>1），而不只是一个方向
        assertEquals(true, BottomBarDragMath.stretchX(0.4f) > 1f)
        assertEquals(true, BottomBarDragMath.stretchX(-0.4f) > 1f)
    }

    @Test
    fun `stretch is clamped at extreme velocity`() {
        // 甩得再猛也不能无限拉长
        val maxX = BottomBarDragMath.stretchX(100f)
        assertEquals(
            1f + BottomBarDragMath.MAX_STRETCH * BottomBarDragMath.STRETCH_X,
            maxX,
            1e-4f,
        )
        // 横向拉长时纵向必须同时收窄（体积近似守恒的橡皮感）
        assertEquals(true, BottomBarDragMath.stretchY(100f) < 1f)
        assertEquals(true, BottomBarDragMath.stretchY(-100f) < 1f)
    }
}
