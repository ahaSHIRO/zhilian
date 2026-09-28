package com.baiyin.zhilian.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.ui.screens.bank.BankScreen
import com.baiyin.zhilian.ui.screens.practice.PracticeHomeScreen
import com.baiyin.zhilian.ui.screens.practice.PracticeSessionScreen
import com.baiyin.zhilian.ui.screens.settings.BatchManageScreen
import com.baiyin.zhilian.ui.screens.settings.SettingsScreen
import com.baiyin.zhilian.ui.screens.stats.StatsScreen

/** 子页面路由（不在底部导航显示） */
const val ROUTE_PRACTICE_SESSION = "practice_session"
const val ROUTE_BATCHES = "batches"

/** tab 序号；非 tab 路由（会话页、批次管理页）返回 null */
private fun tabIndex(route: String?): Int? =
    TopLevelDestination.entries.firstOrNull { it.route == route }?.ordinal

/** tab 间切换的内容进入：方向感知横滑（往右切从右进，往回切从左进），SharedAxisX 简化版 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSlideEnter(): EnterTransition? {
    val from = tabIndex(initialState.destination.route) ?: return null
    val to = tabIndex(targetState.destination.route) ?: return null
    if (from == to) return null
    return slideInHorizontally(tween(300)) { if (from < to) it / 4 else -it / 4 } +
        fadeIn(tween(300))
}

/** tab 间切换的内容退出：与进入镜像，滑向相反方向的 1/4 处并淡出 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSlideExit(): ExitTransition? {
    val from = tabIndex(initialState.destination.route) ?: return null
    val to = tabIndex(targetState.destination.route) ?: return null
    if (from == to) return null
    return slideOutHorizontally(tween(300)) { if (from < to) -it / 4 else it / 4 } +
        fadeOut(tween(300))
}

/**
 * 底部导航四个 tab 的 composable 注册：挂方向感知横滑。
 * 转场 lambda 返回 null 表示回落到 NavHost 默认（淡入淡出），故会话页等
 * 非 tab 路由参与导航时两侧都走默认转场，不受横滑影响。
 */
private fun NavGraphBuilder.tabDestination(
    route: String,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(
    route,
    enterTransition = { tabSlideEnter() },
    exitTransition = { tabSlideExit() },
    popEnterTransition = { tabSlideEnter() },
    popExitTransition = { tabSlideExit() },
    content = content,
)

/**
 * 单 Scaffold + 底部导航 + NavHost 的应用外壳。
 * 沉浸式避让由 Scaffold innerPadding 与 NavigationBar 默认 inset 完成，
 * 规范见 docs/conventions/edge-to-edge.md。
 */
@Composable
fun ZhilianApp(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = TopLevelDestination.entries.any { top ->
        currentDestination?.hierarchy?.any { it.route == top.route } == true
    }

    Scaffold(
        modifier = modifier,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == destination.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    // 单一级栈：回到起点再切目的地，保存/恢复各页状态
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(
                                        if (selected) destination.selectedIconRes
                                        else destination.unselectedIconRes
                                    ),
                                    contentDescription = stringResource(destination.labelRes),
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.PRACTICE.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            tabDestination(TopLevelDestination.PRACTICE.route) {
                PracticeHomeScreen(
                    container = container,
                    onStartPractice = { questionIds ->
                        navController.currentBackStackEntry?.savedStateHandle?.set("questionIds", questionIds)
                        navController.navigate(ROUTE_PRACTICE_SESSION)
                    },
                )
            }
            tabDestination(TopLevelDestination.BANK.route) {
                BankScreen(container = container)
            }
            tabDestination(TopLevelDestination.STATS.route) {
                StatsScreen(container = container)
            }
            tabDestination(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(
                    container = container,
                    onOpenBatches = { navController.navigate(ROUTE_BATCHES) },
                )
            }
            composable(ROUTE_PRACTICE_SESSION) {
                // 题目 ID 由发起页 savedStateHandle 传递，经 NavController.previousBackStackEntry 读取（官方模式）
                val questionIds = navController.previousBackStackEntry
                    ?.savedStateHandle?.get<List<String>>("questionIds").orEmpty()
                PracticeSessionScreen(
                    container = container,
                    questionIds = questionIds,
                    onExit = { navController.popBackStack() },
                )
            }
            composable(ROUTE_BATCHES) {
                BatchManageScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
