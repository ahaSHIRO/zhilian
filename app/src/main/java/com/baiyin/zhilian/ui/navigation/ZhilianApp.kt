package com.baiyin.zhilian.ui.navigation

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
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.baiyin.zhilian.data.SettingsRepository
import com.baiyin.zhilian.ui.screens.bank.BankScreen
import com.baiyin.zhilian.ui.screens.practice.PracticeScreen
import com.baiyin.zhilian.ui.screens.settings.SettingsScreen
import com.baiyin.zhilian.ui.screens.stats.StatsScreen

/**
 * 单 Scaffold + 底部导航 + NavHost 的应用外壳。
 * 沉浸式避让由 Scaffold innerPadding 与 NavigationBar 默认 inset 完成，
 * 规范见 docs/conventions/edge-to-edge.md。
 */
@Composable
fun ZhilianApp(
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        modifier = modifier,
        bottomBar = {
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
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.PRACTICE.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.PRACTICE.route) { PracticeScreen() }
            composable(TopLevelDestination.BANK.route) { BankScreen() }
            composable(TopLevelDestination.STATS.route) { StatsScreen() }
            composable(TopLevelDestination.SETTINGS.route) {
                SettingsScreen(settingsRepository = settingsRepository)
            }
        }
    }
}
