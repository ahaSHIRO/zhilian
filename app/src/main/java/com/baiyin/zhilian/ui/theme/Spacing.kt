package com.baiyin.zhilian.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 知练间距规范（design-tokens.md §二）。
 *
 * 8 基准五档 + 少量语义别名（克制，只给高频复用点，避免每 dp 起名成浅封装）。
 * 调用方优先用语义别名表达意图（如 cardInner、screenEdge），需要灵活时用尺度档。
 * 卡片流 HorizontalPager 的 contentPadding 属功能露出边，不纳入本 token。
 */
object ZhilianSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp

    /** 屏级 Column 水平边距（默认）；卡片流页见 design-tokens.md §2.4 用 xl */
    val screenEdge get() = lg

    /** ZhilianCard 默认卡内边距 */
    val cardInner get() = lg

    /** 紧凑列表卡（题库 / 批次）内边距 */
    val cardInnerCompact get() = md

    /** 元素间距默认；密集场景用 sm */
    val stackGap get() = md
}
