package com.baiyin.zhilian.ui.screens.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.practice.PracticeFilter
import kotlinx.coroutines.launch

/**
 * 练习配置页：分类 + 范围（全部/错题/收藏）+ 顺序/随机 + 数量。
 * 条件组合按 README 必须全部满足；题量不足时以现有题开练，不重复补足。
 */
@Composable
fun PracticeHomeScreen(
    container: AppContainer,
    onStartPractice: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedCategories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var onlyWrong by remember { mutableStateOf(false) }
    var onlyFavorite by remember { mutableStateOf(false) }
    var sequential by remember { mutableStateOf(true) }
    var limit by remember { mutableStateOf(20) }
    var matchedCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        container.database.questionDao().observeCategories().collect { categories = it }
    }

    fun currentFilter() = PracticeFilter(
        categories = selectedCategories,
        onlyWrong = onlyWrong,
        onlyFavorite = onlyFavorite,
        sequential = sequential,
        limit = limit,
    )

    // 实时预览符合条件的题数
    LaunchedEffect(selectedCategories, onlyWrong, onlyFavorite, sequential, limit) {
        matchedCount = container.practiceRepository.pickQuestions(currentFilter()).size
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.practice_filter_category), style = MaterialTheme.typography.titleMedium)
        if (categories.isEmpty()) {
            Text(
                stringResource(R.string.practice_empty_bank),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 单层分类 chips，多选
            categories.chunked(3).forEach { rowCategories ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowCategories.forEach { category ->
                        FilterChip(
                            selected = category in selectedCategories,
                            onClick = {
                                selectedCategories = if (category in selectedCategories) {
                                    selectedCategories - category
                                } else {
                                    selectedCategories + category
                                }
                            },
                            label = { Text(category) },
                        )
                    }
                }
            }
        }

        Text(stringResource(R.string.practice_filter_scope), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = onlyWrong,
                onClick = { onlyWrong = !onlyWrong },
                label = { Text(stringResource(R.string.practice_scope_wrong)) },
            )
            FilterChip(
                selected = onlyFavorite,
                onClick = { onlyFavorite = !onlyFavorite },
                label = { Text(stringResource(R.string.practice_scope_favorite)) },
            )
        }

        Text(stringResource(R.string.practice_filter_order), style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = sequential,
                onClick = { sequential = true },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.practice_order_sequential)) }
            SegmentedButton(
                selected = !sequential,
                onClick = { sequential = false },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.practice_order_random)) }
        }

        Text(stringResource(R.string.practice_filter_limit), style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(10, 20, 50).forEachIndexed { index, n ->
                SegmentedButton(
                    selected = limit == n,
                    onClick = { limit = n },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                ) { Text("$n") }
            }
        }

        Text(
            stringResource(R.string.practice_matched_count, matchedCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = {
                scope.launch {
                    val questions = container.practiceRepository.pickQuestions(currentFilter())
                    if (questions.isNotEmpty()) {
                        onStartPractice(questions.map { it.questionId })
                    }
                }
            },
            enabled = matchedCount > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.practice_start))
        }
    }
}
