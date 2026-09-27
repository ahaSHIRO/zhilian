package com.baiyin.zhilian.ui.theme

/**
 * 深色模式三态：跟随系统 / 强制浅色 / 强制深色。
 * 由设置页选择，DataStore 持久化。
 */
enum class ThemeMode {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK,
}
