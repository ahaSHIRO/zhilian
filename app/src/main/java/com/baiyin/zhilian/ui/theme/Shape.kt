package com.baiyin.zhilian.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 知练圆角规范（design-tokens.md §三）。
 *
 * 对齐 M3 Shapes 五槽；small 与 extraSmall 复用 8（项目无更小圆角需求）。
 * ZhilianCard 取 large（16）、ZhilianOptionRow 取 medium（12）。
 */
val ZhilianShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

/**
 * 半模态面板圆角（design-tokens §3.2）：extraLarge + 底部贴屏幕边取 0。
 *
 * 三屏（解析面板 / 题量面板 / 题库详情）共用此派生——原先一字不差手写三处，改圆角策略要同步三处。
 */
val Shapes.sheetShape: Shape
    get() = extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
