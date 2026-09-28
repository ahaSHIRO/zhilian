package com.baiyin.zhilian.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown

/**
 * 题干/解析/选项的 Markdown 渲染（multiplatform-markdown-renderer，ADR-0002）。
 * 代码块高亮由 -code 模块默认注册；首版无图片（Schema 已拦截图片语法）。
 *
 * 代码块用 [WrappingCodeFence] 覆盖库默认实现：库默认给代码块套
 * `horizontalScroll`，在练习页卡片流（ADR-0003）下横滚手势会被
 * HorizontalPager 抢走，导致超宽代码行在真机上永久不可见（既不折行、
 * 也滚不动）。改为软换行后内容在卡片宽度内自动折行，完整可读。
 */
@Composable
fun QuestionMarkdown(
    content: String,
    modifier: Modifier = Modifier,
) {
    val components = remember {
        markdownComponents(codeFence = { model -> WrappingCodeFence(model) })
    }
    Markdown(
        content = content,
        components = components,
        modifier = modifier,
    )
}
