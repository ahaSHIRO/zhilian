package com.baiyin.zhilian.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 知练主容器卡（ADR-0005）：题卡/结尾卡/统计卡/批次卡/题库条目共用。
 * 固定 16dp 圆角 + 2dp 柔和阴影 + 不透明白底（在背景光雾上悬浮）。
 * 调用方只传 modifier 与 content，样式不外露、不漂移。
 * [containerColor] 默认 surface 白底；可传 secondaryContainer 等做语义区分。
 * [border] 可选（如题库错题红框）；不传则无边框。
 */
@Composable
fun ZhilianCard(
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = border,
    ) {
        content()
    }
}

/**
 * 选项行卡（ADR-0005）：单选/多选/判断的每个选项。
 * 固定 12dp 圆角 + 无阴影 + 边框色按 选中/揭示/正误 派生，
 * 把原散在 PracticeSessionScreen 三处选项渲染的重复边框逻辑收口于此。
 *
 * 边框规则：
 * - revealed 且正解 → primary（揭示正确答案）
 * - revealed 且选中且选错 → error（标错）
 * - 选中 → primary
 * - 其他 → outlineVariant（淡）
 *
 * [containerColor] 默认 surface；判断题等可用 secondaryContainer 做选中填充。
 */
@Composable
fun ZhilianOptionRow(
    selected: Boolean,
    revealed: Boolean = false,
    isCorrect: Boolean = false,
    modifier: Modifier = Modifier,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    content: @Composable RowScope.() -> Unit,
) {
    val border = BorderStroke(
        1.dp,
        when {
            revealed && isCorrect -> MaterialTheme.colorScheme.primary
            revealed && selected && !isCorrect -> MaterialTheme.colorScheme.error
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        },
    )
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = border,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
