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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.baiyin.zhilian.data.BottomBarStyle
import com.baiyin.zhilian.data.practice.SessionArgs
import com.baiyin.zhilian.ui.components.ZhilianBottomBar
import com.baiyin.zhilian.ui.components.resolveBottomBarStyle
import com.baiyin.zhilian.ui.screens.bank.BankScreen
import com.baiyin.zhilian.ui.screens.practice.PracticeHomeScreen
import com.baiyin.zhilian.ui.screens.practice.PracticeSessionScreen
import com.baiyin.zhilian.ui.screens.settings.BatchManageScreen
import com.baiyin.zhilian.ui.screens.settings.SettingsScreen
import com.baiyin.zhilian.ui.screens.stats.StatsScreen
import com.baiyin.zhilian.ui.theme.LocalZhilianDarkTheme
import com.baiyin.zhilian.ui.theme.drawZhilianFog
import com.baiyin.zhilian.ui.theme.zhilianBaseColor
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

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

/** 二级页 push 进入：从右全屏滑入；下层页静止不动（无淡入淡出、无视差缩放） */
private fun secondaryEnter(): EnterTransition =
    slideInHorizontally(tween(NAV_SLIDE_DURATION)) { it }

private fun secondaryExit(): ExitTransition = ExitTransition.None

private fun secondaryPopEnter(): EnterTransition = EnterTransition.None

/** 二级页 pop 退出：全屏向右滑出（经典水平滑动，非跟手；预测性返回已停用，见 ADR-0009） */
private fun secondaryPopExit(): ExitTransition =
    slideOutHorizontally(tween(NAV_SLIDE_DURATION)) { it }

private const val NAV_SLIDE_DURATION = 300

/**
 * 底部导航四个 tab 的 composable 注册：挂方向感知横滑。
 * 转场 lambda 返回 null 表示回落到 NavHost 默认（二级页经典水平侧滑，下层静止），故会话页等
 * 非 tab 路由参与导航时走侧滑，tab 间切换则用方向感知横滑。
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
 * 单 Scaffold + 悬浮底栏 + NavHost 的应用外壳。
 *
 * 底栏是**浮层**（ADR-0010）：内容延伸到屏幕底部、穿到底栏背后，玻璃才有内容可折射。
 * 因此这里只避让**状态栏**，底部避让由各 tab 屏自行用 `rememberBottomBarContentPadding()`
 * 留白（`LazyColumn` 走 `contentPadding`，`verticalScroll` 在末尾加 Spacer）。
 * 子系统避让总规范见 docs/conventions/edge-to-edge.md。
 *
 * 背景光雾与底栏折射源是同一份：外壳录一份 layerBackdrop（底色 + 光雾 + 页面内容），
 * 底栏从中取样——两处若各画一套，光雾位置必然漂移。
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
    val selectedRoute = TopLevelDestination.entries
        .firstOrNull { top -> currentDestination?.hierarchy?.any { it.route == top.route } == true }
        ?.route

    // 导航意图模块（C2/C9）：四个入口共用「一次意图至多一次导航」的来源页守卫。
    // 依赖全部惰性读取 navController，故 remember(navController) 不会因重组而拿到旧闭包。
    val navigation = remember(navController) {
        PracticeNavigation(
            currentRoute = { navController.currentDestination?.route },
            navigateTo = { route -> navController.navigate(route) },
            popBack = { navController.popBackStack() },
            writeSessionArgs = { args ->
                navController.currentBackStackEntry?.savedStateHandle?.set(KEY_SESSION_ARGS, args)
            },
            clearSessionArgs = {
                navController.previousBackStackEntry
                    ?.savedStateHandle?.remove<SessionArgs>(KEY_SESSION_ARGS)
            },
        )
    }

    val style by container.settingsRepository.bottomBarStyle
        .collectAsStateWithLifecycle(initialValue = BottomBarStyle.LIQUID_GLASS)
    // 低版本设备静默退到磨砂：纯函数解析，便于单测（ADR-0010）
    val effectiveStyle = resolveBottomBarStyle(style, android.os.Build.VERSION.SDK_INT)

    val dark = LocalZhilianDarkTheme.current
    val backdrop = rememberLayerBackdrop {
        drawRect(zhilianBaseColor(dark))
        drawZhilianFog(dark)
        drawContent()
    }

    Box(modifier.fillMaxSize()) {
        Scaffold(
            // containerColor 透明是为了透出雾蓝背景层（BackgroundFog），但这会让
            // contentColorFor(Transparent) 解析为 Unspecified，LocalContentColor 断链回落
            // 到默认黑色——页面上所有未写显式颜色的裸 Text（各页 section 标题）会黑字。
            // 必须显式给 contentColor 补回当前主题的 onSurface。
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            // 底栏改由下方浮层绘制；Scaffold 只负责状态栏避让
            bottomBar = {},
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = TopLevelDestination.PRACTICE.route,
                modifier = Modifier
                    .layerBackdrop(backdrop)
                    // 只避让状态栏：底部留给内容穿透底栏
                    .padding(top = innerPadding.calculateTopPadding()),
                // 二级页默认转场：经典水平侧滑（下层静止，无淡入淡出）；tab 间切换由 tabDestination 覆盖。
                // 不含 predictivePop*——预测性返回已在 Manifest 停用（ADR-0009）。
                enterTransition = { secondaryEnter() },
                exitTransition = { secondaryExit() },
                popEnterTransition = { secondaryPopEnter() },
                popExitTransition = { secondaryPopExit() },
            ) {
                tabDestination(TopLevelDestination.PRACTICE.route) {
                    PracticeHomeScreen(
                        container = container,
                        onStartPractice = { questionIds, shuffleOptions ->
                            navigation.startPractice(questionIds, shuffleOptions)
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
                        onOpenBatches = { navigation.openBatches() },
                    )
                }
                composable(ROUTE_PRACTICE_SESSION) {
                    // 会话参数由发起页写入其 savedStateHandle，经 previousBackStackEntry 读取（官方模式）
                    val args = navController.previousBackStackEntry
                        ?.savedStateHandle?.get<SessionArgs>(KEY_SESSION_ARGS)
                    PracticeSessionScreen(
                        container = container,
                        args = args,
                        onExit = { navigation.exitSession() },
                    )
                }
                composable(ROUTE_BATCHES) {
                    BatchManageScreen(
                        container = container,
                        onBack = { navigation.closeBatches() },
                    )
                }
            }
        }

        if (showBottomBar) {
            ZhilianBottomBar(
                style = effectiveStyle,
                destinations = TopLevelDestination.entries,
                selectedRoute = selectedRoute,
                onSelect = { destination ->
                    navController.navigate(destination.route) {
                        // 单一级栈：回到起点再切目的地，保存/恢复各页状态
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
