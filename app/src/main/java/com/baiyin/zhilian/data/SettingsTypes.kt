package com.baiyin.zhilian.data

/**
 * 深色模式三态（设置项，DataStore 持久化）。
 *
 * 声明在数据层而非 ui/theme：设置仓要按它读写，UI 反过来引用，
 * 依赖方向必须单向朝下（原先设置仓反向 import ui.theme / ui.components）。
 */
enum class ThemeMode {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK,
}
