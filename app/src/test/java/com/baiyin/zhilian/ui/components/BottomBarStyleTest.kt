package com.baiyin.zhilian.ui.components

import com.baiyin.zhilian.data.BottomBarStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏效果解析测试（ADR-0010）。
 *
 * 这里锁的是本功能唯一会「静默出错」的行为：用户选了液态玻璃、设备却不支持时，
 * 必须退到磨砂而不是原样返回（否则渲染阶段取不到 AGSAL，界面不会崩、只会不对）。
 */
class BottomBarStyleTest {

    @Test
    fun `liquid glass supported from API 33`() {
        assertFalse(isLiquidGlassSupported(32))
        assertTrue(isLiquidGlassSupported(LIQUID_GLASS_MIN_API))
        assertTrue(isLiquidGlassSupported(34))
    }

    @Test
    fun `liquid glass falls back to frosted below API 33`() {
        assertEquals(
            BottomBarStyle.FROSTED,
            resolveBottomBarStyle(BottomBarStyle.LIQUID_GLASS, sdkInt = 32),
        )
        assertEquals(
            BottomBarStyle.FROSTED,
            resolveBottomBarStyle(BottomBarStyle.LIQUID_GLASS, sdkInt = 31),
        )
    }

    @Test
    fun `liquid glass kept from API 33 on`() {
        assertEquals(
            BottomBarStyle.LIQUID_GLASS,
            resolveBottomBarStyle(BottomBarStyle.LIQUID_GLASS, sdkInt = 33),
        )
        assertEquals(
            BottomBarStyle.LIQUID_GLASS,
            resolveBottomBarStyle(BottomBarStyle.LIQUID_GLASS, sdkInt = 37),
        )
    }

    @Test
    fun `other styles unaffected by api level`() {
        // 标准与磨砂都不依赖 AGSAL，任何版本都原样保留
        assertEquals(
            BottomBarStyle.STANDARD,
            resolveBottomBarStyle(BottomBarStyle.STANDARD, sdkInt = 31),
        )
        assertEquals(
            BottomBarStyle.FROSTED,
            resolveBottomBarStyle(BottomBarStyle.FROSTED, sdkInt = 31),
        )
    }
}
