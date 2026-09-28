package com.baiyin.zhilian.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.material.MarkdownBasicText
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 代码块渲染：**软换行**替代库默认的横向滚动。
 *
 * 为什么不用库默认实现：multiplatform-markdown-renderer 的代码块用
 * `Modifier.horizontalScroll(...)` 包裹文本，宽度不受父容器约束，因此
 * `softWrap` 永不触发——超宽代码行既不折行也显示不全。
 *
 * 更关键的是本项目练习页采用卡片流（ADR-0003）：HorizontalPager 占据左右
 * 滑手势，代码块的横向滚动**抢不到手势**（实测长滑会直接切到下一题），
 * 于是超宽行在真机上永久不可见。改用软换行后不再依赖横滚手势，
 * 内容在卡片宽度内自动折行，完整可读。
 *
 * 语法高亮与背景/圆角/内边距沿用库的样式来源（LocalMarkdown*），
 * 只把「滚动」换成「折行」。
 */
@Composable
fun WrappingCodeFence(model: MarkdownComponentModel) {
    MarkdownCodeFence(
        content = model.content,
        node = model.node,
        style = model.typography.code,
    ) { code, language, style ->
        WrappingHighlightedCode(code = code, language = language, style = style)
    }
}

@Composable
private fun WrappingHighlightedCode(
    code: String,
    language: String?,
    style: TextStyle,
) {
    val backgroundCodeColor = LocalMarkdownColors.current.codeBackground
    val cornerSize = LocalMarkdownDimens.current.codeBackgroundCornerSize
    val codeBlockPadding = LocalMarkdownPadding.current.codeBlock

    // 高亮构建是纯函数（非 @Composable），放到后台协程里做，避免大段代码阻塞组合
    val darkTheme = isSystemInDarkTheme()
    val highlights: AnnotatedString by produceState(
        initialValue = AnnotatedString(text = code),
        key1 = code,
        key2 = language,
        key3 = darkTheme,
    ) {
        val job = launch(Dispatchers.Default) {
            value = buildHighlightedCode(code, language, darkTheme)
        }
        awaitDispose { job.cancel() }
    }

    MarkdownCodeBackground(
        color = backgroundCodeColor,
        shape = RoundedCornerShape(cornerSize),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        // 关键差异：不套 horizontalScroll，文本按父容器宽度软换行
        MarkdownBasicText(
            text = highlights,
            style = style,
            softWrap = true,
            modifier = Modifier.fillMaxWidth().padding(codeBlockPadding),
        )
    }
}

/** 与库内实现一致：用 snipme highlights 产出带颜色的 AnnotatedString（纯函数，可在协程中调用） */
private fun buildHighlightedCode(
    code: String,
    language: String?,
    darkTheme: Boolean,
): AnnotatedString {
    val builder = Highlights.Builder().theme(SyntaxThemes.default(darkMode = darkTheme))
    val syntaxLanguage = language?.let { SyntaxLanguage.getByName(it) }
    val codeHighlights = builder
        .code(code)
        .let { if (syntaxLanguage != null) it.language(syntaxLanguage) else it }
        .build()
        .getHighlights()
    return buildAnnotatedString {
        append(code)
        codeHighlights.forEach {
            val span = when (it) {
                is ColorHighlight -> SpanStyle(color = Color(it.rgb).copy(alpha = 1f))
                is BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            addStyle(span, it.location.start, it.location.end)
        }
    }
}
