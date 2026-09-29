package com.baiyin.zhilian.ui.screens.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.baiyin.zhilian.ui.components.ZhilianCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baiyin.zhilian.ui.theme.ZhilianSpacing
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R

/**
 * 统计页（ADR-0002）：数字卡片 + 进度条呈现 README 四项指标；不引入图表库。
 */
@Composable
fun StatsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    var firstAccuracy by remember { mutableStateOf<Double?>(null) }
    var overall by remember { mutableStateOf<Double?>(null) }
    var multiPerfect by remember { mutableStateOf<Double?>(null) }
    var avgScore by remember { mutableStateOf<Double?>(null) }
    var questionCount by remember { mutableStateOf(0) }
    var recordCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        firstAccuracy = container.practiceRepository.firstAttemptAccuracy()
        overall = container.practiceRepository.overallAccuracy()
        multiPerfect = container.practiceRepository.multipleChoicePerfectRate()
        avgScore = container.practiceRepository.averageScoreRate()
        container.database.questionDao().observeActiveCount().collect { questionCount = it }
    }
    LaunchedEffect(Unit) {
        container.database.answerRecordDao().observeCount().collect { recordCount = it }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ZhilianSpacing.screenEdge),
        verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.stackGap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.stackGap)) {
            StatCard(
                label = stringResource(R.string.stats_questions),
                value = questionCount.toString(),
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = stringResource(R.string.stats_records),
                value = recordCount.toString(),
                modifier = Modifier.weight(1f),
            )
        }
        StatCard(
            label = stringResource(R.string.stats_first_accuracy),
            value = percent(firstAccuracy),
            progress = firstAccuracy,
        )
        StatCard(
            label = stringResource(R.string.stats_overall_accuracy),
            value = percent(overall),
            progress = overall,
        )
        StatCard(
            label = stringResource(R.string.stats_multi_perfect),
            value = percent(multiPerfect),
            progress = multiPerfect,
        )
        StatCard(
            label = stringResource(R.string.stats_avg_score),
            value = percent(avgScore),
            progress = avgScore,
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, progress: Double? = null, modifier: Modifier = Modifier) {
    ZhilianCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(ZhilianSpacing.cardInner), verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium)
            progress?.let {
                LinearProgressIndicator(
                    progress = { it.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun percent(v: Double?): String = v?.let { "${(it * 100).toInt()}%" } ?: "—"
