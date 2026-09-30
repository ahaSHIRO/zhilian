package com.baiyin.zhilian.ui.components

import com.baiyin.zhilian.data.BottomBarStyle

/**
 * 液态玻璃所需的最低 API：折射/高光走 AGSAL `RuntimeShader`。
 * 低于此版本只能退到 [BottomBarStyle.FROSTED]。
 */
const val LIQUID_GLASS_MIN_API = 33

/** 当前系统版本能否真正渲染液态玻璃（供设置页置灰判断） */
fun isLiquidGlassSupported(sdkInt: Int): Boolean = sdkInt >= LIQUID_GLASS_MIN_API

/**
 * 用户选择 + 系统能力 → 实际生效的效果。
 *
 * 单独抽成纯函数的原因：这是本功能唯一「会静默出错」的地方——选了液态玻璃却因版本
 * 悄悄退成磨砂，界面不会崩、只会不对，必须被测试锁住。
 */
fun resolveBottomBarStyle(selected: BottomBarStyle, sdkInt: Int): BottomBarStyle =
    if (selected == BottomBarStyle.LIQUID_GLASS && !isLiquidGlassSupported(sdkInt)) {
        BottomBarStyle.FROSTED
    } else {
        selected
    }
