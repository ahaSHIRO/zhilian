package com.baiyin.zhilian.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baiyin.zhilian.ui.theme.ZhilianSpacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.BottomBarStyle
import com.baiyin.zhilian.ui.theme.ThemeMode
import com.baiyin.zhilian.ui.components.ZhilianCard
import com.baiyin.zhilian.ui.components.isLiquidGlassSupported
import com.baiyin.zhilian.ui.components.label
import com.baiyin.zhilian.ui.components.rememberBottomBarContentPadding
import kotlinx.coroutines.launch

/**
 * 设置屏：外观（深色模式三态，ADR-0002）+ 批次导入入口。
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenBatches: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val themeMode by container.settingsRepository.themeMode
        .collectAsStateWithLifecycle(initialValue = ThemeMode.FOLLOW_SYSTEM)
    val bottomBarStyle by container.settingsRepository.bottomBarStyle
        .collectAsStateWithLifecycle(initialValue = BottomBarStyle.LIQUID_GLASS)
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var showCleared by remember { mutableStateOf(false) }

    // 底栏是浮层、内容穿到它背后（ADR-0010）：末尾留出底栏高度，否则最后一张卡被永久遮住
    val bottomBarPadding = rememberBottomBarContentPadding()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ZhilianSpacing.screenEdge, vertical = ZhilianSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.lg),
    ) {
        // ---- 外观卡 ----
        ZhilianCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(ZhilianSpacing.cardInner),
                verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.stackGap),
            ) {
                Text(
                    text = stringResource(R.string.settings_appearance),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_theme_mode),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = {
                                scope.launch { container.settingsRepository.setThemeMode(mode) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ThemeMode.entries.size,
                            ),
                        ) {
                            Text(stringResource(mode.labelRes))
                        }
                    }
                }

                // ---- 底栏效果（ADR-0010）----
                Text(
                    text = stringResource(R.string.settings_bottom_bar_style),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val liquidGlassSupported = isLiquidGlassSupported(android.os.Build.VERSION.SDK_INT)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    BottomBarStyle.entries.forEachIndexed { index, styleOption ->
                        // 低版本设备的液态玻璃项置灰：AGSAL 折射需 Android 13+，
                        // 静默降级会让用户以为“选了没效果”，故直接不可选并给出说明
                        val enabled = styleOption != BottomBarStyle.LIQUID_GLASS || liquidGlassSupported
                        SegmentedButton(
                            selected = bottomBarStyle == styleOption,
                            enabled = enabled,
                            onClick = {
                                scope.launch { container.settingsRepository.setBottomBarStyle(styleOption) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = BottomBarStyle.entries.size,
                            ),
                        ) {
                            Text(styleOption.label())
                        }
                    }
                }
                if (!liquidGlassSupported) {
                    Text(
                        text = stringResource(R.string.bottom_bar_liquid_glass_requires),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- 数据卡 ----
        ZhilianCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(ZhilianSpacing.cardInner),
                verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.stackGap),
            ) {
                Text(
                    text = stringResource(R.string.settings_data),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(onClick = onOpenBatches, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_batch_import))
                }
                Text(
                    text = stringResource(R.string.settings_batch_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_clear_history))
                }
                Text(
                    text = stringResource(R.string.settings_clear_history_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(bottomBarPadding))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_history_confirm_title)) },
            text = { Text(stringResource(R.string.clear_history_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        container.practiceRepository.clearAllHistory()
                        showCleared = true
                    }
                }) { Text(stringResource(R.string.clear_history_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showCleared) {
        AlertDialog(
            onDismissRequest = { showCleared = false },
            confirmButton = {
                TextButton(onClick = { showCleared = false }) { Text(stringResource(R.string.ok)) }
            },
            text = { Text(stringResource(R.string.clear_history_done)) },
        )
    }
}

internal val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.FOLLOW_SYSTEM -> R.string.theme_mode_follow_system
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.DARK -> R.string.theme_mode_dark
    }
