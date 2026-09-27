package com.baiyin.zhilian.ui.theme

import android.content.ContextWrapper
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * 知练主题：Material You 动态取色（ADR-0002）。
 * minSdk 31 远高于动态取色最低要求（API 31），无需静态配色回退；
 * 深浅色由 [ThemeMode] 三态决定，默认跟随系统。
 *
 * 系统栏图标深浅色在此统一同步为 App 主题的 darkTheme（而非系统深色模式）：
 * 当用户在 App 内强制切换深浅色而与系统不一致时，状态栏/小白条图标仍保持可读，
 * 见 docs/conventions/edge-to-edge.md。
 */
@Composable
fun ZhilianTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    // 备用静态配色（动态取色不可用时无法到达，保留单行以便快速切换）：
    // val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()

    // 系统栏样式跟随 App 主题（覆盖 MainActivity 的初始 auto 设置）；全透明 scrim，
    // 沉浸由内容避让保证。enableEdgeToEdge 可重复调用，此处即规范中的同步点。
    val view = LocalView.current
    LaunchedEffect(darkTheme) {
        // view.context 可能是 ContextThemeWrapper，需解包到 Activity
        var ctx = view.context
        while (ctx is ContextWrapper && ctx !is ComponentActivity) {
            ctx = ctx.baseContext
        }
        val activity = ctx as? ComponentActivity ?: return@LaunchedEffect
        val style = if (darkTheme) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        activity.enableEdgeToEdge(
            statusBarStyle = style,
            navigationBarStyle = style,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
