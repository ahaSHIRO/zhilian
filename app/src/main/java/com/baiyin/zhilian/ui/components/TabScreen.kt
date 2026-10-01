package com.baiyin.zhilian.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import com.baiyin.zhilian.ui.theme.ZhilianSpacing

/**
 * tab 屏的两种内容外壳——**底部留白由外壳自动叠加**（ADR-0010：内容要穿到底栏背后，
 * 玻璃才有可折射物；见 docs/conventions/edge-to-edge.md §底栏穿透）。
 *
 * 为什么要有外壳：底部空间有两个计算者——外壳不给底部避让、屏内容自己留白。漏了会让
 * 最后一项被底栏永久遮住；自己在外层加 padding 又会把内容截在底栏之上、让招牌的液态
 * 玻璃失去折射物。两种错都**静默**，且只有真机滚到底才看得见。把留白收进外壳后，
 * 页面代码既不必记得算、也没有机会算错。
 */

/** `verticalScroll` 型 tab 屏：垂直滚动 + 内容末尾自动补底栏留白 */
@Composable
fun TabVerticalScrollColumn(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    contentPadding: PaddingValues = PaddingValues(ZhilianSpacing.screenEdge),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(state)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
    ) {
        content()
        // 末尾留白：底栏是浮层，内容要能滚到它背后。⚠️ 别把它换成外层 padding（穿透会失效）
        BottomBarTrailingSpacer()
    }
}

/** `LazyColumn` 型 tab 屏：底部留白叠加进 contentPadding，其余方向沿用调用方传入 */
@Composable
fun TabLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    val direction = LocalLayoutDirection.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = state,
        verticalArrangement = verticalArrangement,
        // 底部一律由外壳给（调用方传进来的 bottom 会被覆盖）——避免「两个计算者各算一遍」
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(direction),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(direction),
            bottom = rememberBottomBarReservedHeight(),
        ),
        content = content,
    )
}
