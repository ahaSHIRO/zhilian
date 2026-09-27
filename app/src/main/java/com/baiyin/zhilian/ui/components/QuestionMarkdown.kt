package com.baiyin.zhilian.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown

/**
 * 题干/解析/选项的 Markdown 渲染（multiplatform-markdown-renderer，ADR-0002）。
 * 代码块高亮由 -code 模块默认注册；首版无图片（Schema 已拦截图片语法）。
 */
@Composable
fun QuestionMarkdown(
    content: String,
    modifier: Modifier = Modifier,
) {
    Markdown(content = content, modifier = modifier)
}
