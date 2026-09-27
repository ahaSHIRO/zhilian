package com.baiyin.zhilian.ui.navigation

import androidx.annotation.DrawableRes
import com.baiyin.zhilian.R

/**
 * 底部导航一级目的地。路由即字符串常量，嵌套页面路由以 "目的地路由/子路径" 命名。
 * 图标为 Material Symbols 矢量资源（outlined 常态 / fill1 选中态），
 * ADR-0002：不使用已停更的 androidx material-icons 库。
 */
enum class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    @DrawableRes val selectedIconRes: Int,
    @DrawableRes val unselectedIconRes: Int,
) {
    PRACTICE(
        route = "practice",
        labelRes = R.string.tab_practice,
        selectedIconRes = R.drawable.ic_tab_practice_filled,
        unselectedIconRes = R.drawable.ic_tab_practice,
    ),
    BANK(
        route = "bank",
        labelRes = R.string.tab_bank,
        selectedIconRes = R.drawable.ic_tab_bank_filled,
        unselectedIconRes = R.drawable.ic_tab_bank,
    ),
    STATS(
        route = "stats",
        labelRes = R.string.tab_stats,
        selectedIconRes = R.drawable.ic_tab_stats_filled,
        unselectedIconRes = R.drawable.ic_tab_stats,
    ),
    SETTINGS(
        route = "settings",
        labelRes = R.string.tab_settings,
        selectedIconRes = R.drawable.ic_tab_settings_filled,
        unselectedIconRes = R.drawable.ic_tab_settings,
    ),
}
