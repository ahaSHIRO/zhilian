package com.baiyin.zhilian.ui.components

import kotlin.math.abs

/**
 * 底栏拖动中与手感相关的纯计算（ADR-0010 修订）。
 *
 * 单独抽出来是为了可测：这些系数决定「拖到头有没有阻力感」「甩出去能拉多长」，
 * 调参时最容易把边界算错，而算错不会崩、只会手感不对。UI 层只负责把手指位移喂进来。
 */
internal object BottomBarDragMath {

    /** 两端越界阻尼：越界的位移只按此比例生效（越小越"拖不动"） */
    const val OVERSCROLL_RESISTANCE = 0.28f

    /** 速度拉伸上限（归一化速度被夹到此范围，避免甩得过猛） */
    const val MAX_STRETCH = 0.6f

    /** 沿运动方向的拉伸系数 */
    const val STRETCH_X = 0.28f

    /** 垂直方向的收窄系数（略小于 X，做出橡皮而非压扁的观感） */
    const val STRETCH_Y = 0.16f

    /**
     * 超出两端时把位移压成渐进值（越拖越难），手感上像有阻力；未超出则原样返回，
     * 保证中间段严格线性跟手。
     */
    fun overScrollDamped(raw: Float, tabCount: Int): Float {
        val max = (tabCount - 1).toFloat()
        return when {
            raw < 0f -> raw * OVERSCROLL_RESISTANCE
            raw > max -> max + (raw - max) * OVERSCROLL_RESISTANCE
            else -> raw
        }
    }

    /**
     * 位移折算成 tab 序号。以按下时的序号为基准、按「拖过几格」线性推进，
     * 与胶囊宽度无关（宽度只影响像素↔格数的换算，在调用方做）。
     */
    fun indexForDrag(startIndex: Float, dragPx: Float, slotPx: Float, tabCount: Int): Float {
        if (slotPx <= 0f) return startIndex
        return overScrollDamped(startIndex + dragPx / slotPx, tabCount)
    }

    /**
     * 胶囊的横向拉伸倍率。
     *
     * 取速度的**绝对值**：无论往左还是往右甩，液滴都沿运动方向拉长（横向变宽）；
     * 若直接用带符号速度，往左拖会变成横向收缩，观感像被压扁而非拉长。
     */
    fun stretchX(velocity: Float): Float =
        1f + abs(velocity).coerceAtMost(MAX_STRETCH) * STRETCH_X

    /** 胶囊的纵向收窄倍率：与横向互补，做出「体积守恒」的橡皮感（同样取绝对值） */
    fun stretchY(velocity: Float): Float =
        1f - abs(velocity).coerceAtMost(MAX_STRETCH) * STRETCH_Y
}
