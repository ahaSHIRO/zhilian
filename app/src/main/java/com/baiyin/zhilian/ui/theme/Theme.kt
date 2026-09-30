package com.baiyin.zhilian.ui.theme

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalView

/**
 * App 主题解析后的深色态，供无法直接拿 themeMode 的深层组件使用。
 * 必须用它替代 isSystemInDarkTheme()——后者跟系统走，App 设为深色而
 * 系统为浅色时会错拿浅色调色板（代码块高亮曾因此黑字难读）。
 */
val LocalZhilianDarkTheme = staticCompositionLocalOf { false }

/**
 * 知练主题：固定雾蓝配色（ADR-0005，弃 Material You 动态取色）。
 *
 * 三色分工：primary #4A7AA8 撑骨架（白底文字 4.53 达 AA）、
 * primaryContainer #9db7d4 雾蓝填充层、accent #95feff 透亮青蓝作点缀。
 * 背景由 [BackgroundFog] 错落蓝色 radialGradient 光雾层替代纯色。
 * 深浅色由 [ThemeMode] 三态决定，默认跟随系统。
 *
 * 系统栏图标深浅色在此统一同步为 App 主题（而非系统深色模式），
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
    val colorScheme = if (darkTheme) ZhilianDarkScheme else ZhilianLightScheme

    val view = LocalView.current
    LaunchedEffect(darkTheme) {
        var ctx = view.context
        while (ctx is ContextWrapper && ctx !is ComponentActivity) {
            ctx = ctx.baseContext
        }
        val activity = ctx as? ComponentActivity ?: return@LaunchedEffect
        val style = if (darkTheme) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        }
        activity.enableEdgeToEdge(
            statusBarStyle = style,
            navigationBarStyle = style,
        )
    }

    CompositionLocalProvider(LocalZhilianDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ZhilianTypography,
            shapes = ZhilianShapes,
            content = {
                Box(Modifier.fillMaxSize()) {
                    BackgroundFog(darkTheme = darkTheme)
                    content()
                }
            },
        )
    }
}

/**
 * 背景光雾层：底色 + 错落蓝色 radialGradient 光斑。
 * 绘制命令抽在 [drawZhilianFog]，与 App 外壳的底栏 backdrop 共用同一套实现。
 */
@Composable
private fun BackgroundFog(darkTheme: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .background(zhilianBaseColor(darkTheme))
            .drawBehind { drawZhilianFog(darkTheme) }
    )
}

/** 知练底色（深浅两态）；底栏 backdrop 与光雾层共用，避免两处各写一份 */
internal fun zhilianBaseColor(darkTheme: Boolean): Color =
    if (darkTheme) Color(0xFF0F1419) else Color(0xFFF4F6F8)

/**
 * 光雾绘制命令：底色之上的错落蓝色 radialGradient 光斑。
 * radialGradient 中心实、边缘衰减，衰减曲线本身即高斯式柔和，无需 RenderEffect。
 * 光斑只用 primary/accent 的极低透明度，整屏透出淡蓝雾感；卡片不透明白底在雾上悬浮。
 *
 * 之所以抽成独立 DrawScope 扩展：底栏液态玻璃要折射**完整**背景，App 外壳录
 * backdrop 时必须把同一套光雾画进去；两处各写一份必然漂移（ADR-0010）。
 *
 * 光斑位置按实际尺寸比例定位（radialGradient 的 center 是像素坐标，
 * 必须拿到 size 再换算，不能用固定像素或比例值直接传）。
 */
internal fun DrawScope.drawZhilianFog(darkTheme: Boolean) {
    val fog = if (darkTheme) Color(0xFF1B2A3A) else Color(0xFF9DB7D4)
    val accent = Color(0xFF95FEFF)
    // 左上主雾斑
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(fog.copy(alpha = 0.20f), Color.Transparent),
            center = Offset(size.width * 0.18f, size.height * 0.14f),
            radius = size.minDimension * 0.85f,
        )
    )
    // 右下副雾斑
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(fog.copy(alpha = 0.14f), Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.68f),
            radius = size.minDimension * 1.0f,
        )
    )
    // 底部透青点缀
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = if (darkTheme) 0.05f else 0.07f),
                Color.Transparent,
            ),
            center = Offset(size.width * 0.5f, size.height * 0.96f),
            radius = size.minDimension * 0.7f,
        )
    )
}

/** 浅色雾蓝 ColorScheme（ADR-0005） */
private val ZhilianLightScheme = lightColorScheme(
    primary = Color(0xFF4A7AA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9DB7D4),
    onPrimaryContainer = Color(0xFF0E2238),
    secondary = Color(0xFF5B7B9A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1ECF4),
    onSecondaryContainer = Color(0xFF2A3F52),
    tertiary = Color(0xFF6B8A9E),
    onTertiary = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1F24),
    surfaceVariant = Color(0xFFEEF1F4),
    onSurfaceVariant = Color(0xFF5A6470),
    surfaceContainerLow = Color(0xFFF4F6F8),
    surfaceContainer = Color(0xFFEEF1F4),
    surfaceContainerHigh = Color(0xFFE8ECF0),
    outline = Color(0xFFC4CCD4),
    outlineVariant = Color(0xFFDDE3E9),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDEBEC),
    onErrorContainer = Color(0xFF9F2F2D),
    background = Color(0xFFF4F6F8),
    onBackground = Color(0xFF1A1F24),
)

/** 深色雾蓝 ColorScheme（ADR-0005） */
private val ZhilianDarkScheme = darkColorScheme(
    primary = Color(0xFF9CC0E8),
    onPrimary = Color(0xFF0E2238),
    primaryContainer = Color(0xFF2A4A6A),
    onPrimaryContainer = Color(0xFFD4E3F4),
    secondary = Color(0xFFA4BAD2),
    onSecondary = Color(0xFF0F1F30),
    secondaryContainer = Color(0xFF2E3F52),
    onSecondaryContainer = Color(0xFFC8D6E4),
    tertiary = Color(0xFFB0C2D4),
    onTertiary = Color(0xFF0F1F2A),
    surface = Color(0xFF161B20),
    onSurface = Color(0xFFE3E7EC),
    surfaceVariant = Color(0xFF1B222A),
    onSurfaceVariant = Color(0xFF9BA5B0),
    surfaceContainerLow = Color(0xFF0F1419),
    surfaceContainer = Color(0xFF1B222A),
    surfaceContainerHigh = Color(0xFF222B34),
    outline = Color(0xFF3D4750),
    outlineVariant = Color(0xFF2A323A),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D1D),
    onErrorContainer = Color(0xFFF9DAD6),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFE3E7EC),
)
