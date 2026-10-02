package com.baiyin.zhilian.ui.screens.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.db.CategoryWrongRow
import com.baiyin.zhilian.ui.components.Loadable
import com.baiyin.zhilian.ui.components.ZhilianCard
import com.baiyin.zhilian.ui.components.collectAsLoadable
import com.baiyin.zhilian.ui.components.TabVerticalScrollColumn
import com.baiyin.zhilian.ui.theme.ZhilianSpacing
import kotlinx.coroutines.flow.combine

/** 一次性读取的聚合项（与两个计数流合成同一个门控） */
private data class StatsAggregates(
    val firstAccuracy: Double?,
    val overall: Double?,
    val multiPerfect: Double?,
    val avgScore: Double?,
    val wrongCount: Int,
    val topWrong: List<CategoryWrongRow>,
)

/** 首帧未就绪时的占位：不显示 0——「0 道题」「0 次作答」都是假事实 */
private const val PENDING = "—"

/**
 * 统计页（ADR-0002）：数字卡片 + 进度条呈现 README 四项指标；不引入图表库。
 * 2026-09-30 增设弱项卡：当前错题数 + 弱项分类 TOP3，指向"哪里弱练哪里"。
 *
 * 首帧门控（C3）：计数流与聚合项都到达前，一律显示 `—`、不画进度条、不显示弱项明细——
 * 原先进场瞬间会闪「0 / 0」与 0% 的进度条，随后才跳成真值。
 */
@Composable
fun StatsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    // 流持有稳定实例（否则每次重组都重订阅、白查库）；首帧门控走 collectAsLoadable，
    // 值到达后不会因重订阅回落首帧态（见 LoadableState 说明）
    val countsFlow = remember(container) {
        combine(
            container.questionBank.observeCount(),
            container.practiceStats.observeRecordCount(),
        ) { questions, records -> questions to records }
    }
    val counts by countsFlow.collectAsLoadable()

    var aggregates by remember { mutableStateOf<Loadable<StatsAggregates>>(Loadable.FirstLoad) }
    LaunchedEffect(Unit) {
        aggregates = Loadable.Data(
            StatsAggregates(
                firstAccuracy = container.practiceStats.firstAttemptAccuracy(),
                overall = container.practiceStats.overallAccuracy(),
                multiPerfect = container.practiceStats.multipleChoicePerfectRate(),
                avgScore = container.practiceStats.averageScoreRate(),
                wrongCount = container.practiceStats.wrongQuestionCount(),
                topWrong = container.practiceStats.topWrongCategories(),
            ),
        )
    }

    val countPair = (counts as? Loadable.Data)?.value
    val agg = (aggregates as? Loadable.Data)?.value
    val ready = countPair != null && agg != null

    TabVerticalScrollColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.stackGap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.stackGap)) {
            StatCard(
                label = stringResource(R.string.stats_questions),
                value = countPair?.first?.toString() ?: PENDING,
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = stringResource(R.string.stats_records),
                value = countPair?.second?.toString() ?: PENDING,
                modifier = Modifier.weight(1f),
            )
        }
        StatCard(
            label = stringResource(R.string.stats_first_accuracy),
            value = percent(agg?.firstAccuracy),
            progress = agg?.firstAccuracy,
        )
        StatCard(
            label = stringResource(R.string.stats_overall_accuracy),
            value = percent(agg?.overall),
            progress = agg?.overall,
        )
        StatCard(
            label = stringResource(R.string.stats_multi_perfect),
            value = percent(agg?.multiPerfect),
            progress = agg?.multiPerfect,
        )
        StatCard(
            label = stringResource(R.string.stats_avg_score),
            value = percent(agg?.avgScore),
            progress = agg?.avgScore,
        )
        StatCard(
            label = stringResource(R.string.stats_wrong_count),
            value = agg?.wrongCount?.toString() ?: PENDING,
            // 明细同样只在就绪后出现：首帧的「暂无错题」是假结论
            detail = agg?.topWrong
                ?.joinToString(" · ") { "${it.category} ${it.wrongCount}" }
                ?.ifEmpty { stringResource(R.string.stats_no_wrong) },
        )
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    progress: Double? = null,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    ZhilianCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(ZhilianSpacing.cardInner),
            verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium)
            // 进度条只在有真值时才画：首帧不画，也不拿 0% 冒充
            progress?.let {
                LinearProgressIndicator(
                    progress = { it.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun percent(v: Double?): String = v?.let { "${(it * 100).toInt()}%" } ?: PENDING
