package com.baiyin.zhilian.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.ui.theme.ThemeMode
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
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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
    }
}

internal val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.FOLLOW_SYSTEM -> R.string.theme_mode_follow_system
        ThemeMode.LIGHT -> R.string.theme_mode_light
        ThemeMode.DARK -> R.string.theme_mode_dark
    }
