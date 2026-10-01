package com.baiyin.zhilian.ui.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设计 token 守卫（design-tokens.md §5.4「测试面」的落地，2026-10-01）。
 *
 * 两条防线：
 * 1. **值断言**——锁三维 token 档位值：手滑改档（比如把 lg 从 16 改成 18）在这里即红，
 *    而不是等真机逐屏目检；
 * 2. **源码扫描**——锁三条零豁免硬线：圆角字面量只许在 Shape.kt、字号/sp 只许在
 *    Type.kt、Bold 只许在白名单文件。未来新 screen 的逃逸在 `gradlew test` 即红。
 *
 * 有意不扫的：`.dp` 间距字面量豁免太多（底栏玻璃几何、边框 1dp、elevation——
 * design-tokens §2.4），机械断言会常年误红，靠评审与 §2.4 豁免清单约束。
 */
class DesignTokenGuardTest {

    /* ---------------- 值断言 ---------------- */

    @Test
    fun `spacing scale holds the five token values`() {
        assertEquals(4.dp, ZhilianSpacing.xs)
        assertEquals(8.dp, ZhilianSpacing.sm)
        assertEquals(12.dp, ZhilianSpacing.md)
        assertEquals(16.dp, ZhilianSpacing.lg)
        assertEquals(24.dp, ZhilianSpacing.xl)
    }

    @Test
    fun `spacing aliases point at their scale slots`() {
        assertEquals(ZhilianSpacing.lg, ZhilianSpacing.screenEdge)
        assertEquals(ZhilianSpacing.lg, ZhilianSpacing.cardInner)
        assertEquals(ZhilianSpacing.md, ZhilianSpacing.cardInnerCompact)
        assertEquals(ZhilianSpacing.md, ZhilianSpacing.stackGap)
    }

    @Test
    fun `shapes hold the documented corner radii`() {
        // CornerShape.topStart 是 CornerSize（非 Dp）；small 与 extraSmall 复用 8（design-tokens §3.1）
        assertEquals(CornerSize(8.dp), ZhilianShapes.extraSmall.topStart)
        assertEquals(CornerSize(8.dp), ZhilianShapes.small.topStart)
        assertEquals(CornerSize(12.dp), ZhilianShapes.medium.topStart)
        assertEquals(CornerSize(16.dp), ZhilianShapes.large.topStart)
        assertEquals(CornerSize(20.dp), ZhilianShapes.extraLarge.topStart)
    }

    /** 档名 → (字号, 字重, 行高)，与 design-tokens §4.1 逐行对表 */
    private val typographySpec = listOf(
        "bodyLarge" to Triple(16.sp, FontWeight.Normal, 24.sp),
        "bodyMedium" to Triple(14.sp, FontWeight.Normal, 20.sp),
        "bodySmall" to Triple(12.sp, FontWeight.Normal, 16.sp),
        "titleLarge" to Triple(22.sp, FontWeight.Medium, 28.sp),
        "titleMedium" to Triple(16.sp, FontWeight.Medium, 24.sp),
        "titleSmall" to Triple(14.sp, FontWeight.Medium, 20.sp),
        "headlineMedium" to Triple(28.sp, FontWeight.Medium, 34.sp),
        "headlineSmall" to Triple(24.sp, FontWeight.Medium, 30.sp),
        "labelLarge" to Triple(14.sp, FontWeight.Medium, 20.sp),
        "labelMedium" to Triple(12.sp, FontWeight.Medium, 16.sp),
        "labelSmall" to Triple(11.sp, FontWeight.Medium, 16.sp),
    )

    @Test
    fun `typography holds the documented slot specs`() {
        val actual = mapOf(
            "bodyLarge" to ZhilianTypography.bodyLarge,
            "bodyMedium" to ZhilianTypography.bodyMedium,
            "bodySmall" to ZhilianTypography.bodySmall,
            "titleLarge" to ZhilianTypography.titleLarge,
            "titleMedium" to ZhilianTypography.titleMedium,
            "titleSmall" to ZhilianTypography.titleSmall,
            "headlineMedium" to ZhilianTypography.headlineMedium,
            "headlineSmall" to ZhilianTypography.headlineSmall,
            "labelLarge" to ZhilianTypography.labelLarge,
            "labelMedium" to ZhilianTypography.labelMedium,
            "labelSmall" to ZhilianTypography.labelSmall,
        )
        typographySpec.forEach { (name, expected) ->
            val (size, weight, lineHeight) = expected
            val style = actual.getValue(name)
            assertEquals("$name 字号", size, style.fontSize)
            assertEquals("$name 字重", weight, style.fontWeight)
            assertEquals("$name 行高", lineHeight, style.lineHeight)
        }
    }

    /* ---------------- 源码扫描 ---------------- */

    /** 从测试工作目录向上找 ui 源码根（app/src/main/java/com/baiyin/zhilian/ui） */
    private fun uiSourceDir(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = dir?.resolve("app/src/main/java/com/baiyin/zhilian/ui")
            if (candidate?.isDirectory == true) return candidate
            dir = dir?.parentFile
        }
        error("找不到 ui 源码目录：从 ${System.getProperty("user.dir")} 向上 6 层未发现 app/src/main/java/com/baiyin/zhilian/ui")
    }

    /** [pattern] 命中的 .kt 文件必须全部落在 [whitelist]（相对 ui 根的路径）内 */
    private fun assertOnlyFilesMatch(pattern: String, whitelist: Set<String>) {
        val regex = Regex(pattern)
        val root = uiSourceDir()
        val violations = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative in whitelist) {
                    null
                } else if (regex.containsMatchIn(file.readText())) {
                    relative
                } else {
                    null
                }
            }
            .toList()
        assertTrue(
            "设计 token 逃逸（design-tokens.md）：/$pattern/ 只允许出现在白名单 $whitelist，违规文件：$violations",
            violations.isEmpty(),
        )
    }

    @Test
    fun `rounded corner literals live only in Shape kt`() {
        // 只拦「数字开头」的字面量；WrappingCodeFence 的 RoundedCornerShape(cornerSize)
        // 是库组件尺寸透传（design-tokens §3.2 豁免），不匹配此正则
        assertOnlyFilesMatch("RoundedCornerShape\\(\\s*\\d", setOf("theme/Shape.kt"))
    }

    @Test
    fun `sp and fontSize literals live only in Type kt`() {
        assertOnlyFilesMatch("fontSize\\s*=|\\b\\d+(?:\\.\\d+)?\\.sp\\b", setOf("theme/Type.kt"))
    }

    @Test
    fun `bold emphasis stays within the whitelist`() {
        // 白名单两处：Type.kt（§4.2 注释本身）、WrappingCodeFence.kt（Markdown **粗体**语法渲染，
        // 属内容渲染而非 UI 强调——design-tokens §4.2 三类场景之外的既存豁免）
        assertOnlyFilesMatch(
            "FontWeight\\.Bold",
            setOf("theme/Type.kt", "components/WrappingCodeFence.kt"),
        )
    }
}
