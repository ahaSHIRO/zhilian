package com.baiyin.zhilian.ui.screens.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.practice.PracticeFilter
import kotlinx.coroutines.launch

/**
 * 练习配置页：科目 + 分类 + 题型 + 范围（全部/错题/收藏）+ 顺序/随机 + 数量。
 * 条件组合按 README 必须全部满足；题量不足时以现有题开练，不重复补足。
 *
 * 分类挂在科目下：选中科目后，分类 chips 只显示该科目下的分类，
 * 避免 Java 与 Kotlin 的同名分类混在一起（CONTEXT.md 的科目/分类身份规范）。
 * 科目与分类均为空时表示"不限"，三个维度可任意组合。
 */
@Composable
fun PracticeHomeScreen(
    container: AppContainer,
    onStartPractice: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var subjects by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedSubjects by remember { mutableStateOf<Set<String>>(emptySet()) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedCategories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var types by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTypes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var onlyWrong by remember { mutableStateOf(false) }
    var onlyFavorite by remember { mutableStateOf(false) }
    var sequential by remember { mutableStateOf(true) }
    var limit by remember { mutableStateOf(20) }
    var matchedCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // 科目与题型列表只随题库变化，取一次即可
    LaunchedEffect(Unit) {
        subjects = container.practiceRepository.distinctSubjects()
        types = container.practiceRepository.distinctTypes()
    }

    // 分类跟随所选科目：单选科目时显示该科目的分类；未选或多选时显示全部（多选场景下并集更实用）
    LaunchedEffect(selectedSubjects) {
        val only = selectedSubjects.singleOrNull()
        categories = container.practiceRepository.distinctCategories(only)
        // 科目变化后，已选分类若不在新列表里则清掉，避免出现"选中了但看不到的 chip"
        selectedCategories = selectedCategories.intersect(categories.toSet())
    }

    fun currentFilter() = PracticeFilter(
        subjects = selectedSubjects,
        categories = selectedCategories,
        types = selectedTypes,
        onlyWrong = onlyWrong,
        onlyFavorite = onlyFavorite,
        sequential = sequential,
        limit = limit,
    )

    // 实时预览符合条件的题数（走计数接口，不受题量上限截断；与顺序/随机无关故不列入 key）
    LaunchedEffect(selectedSubjects, selectedCategories, selectedTypes, onlyWrong, onlyFavorite, limit) {
        matchedCount = container.practiceRepository.countMatching(currentFilter())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- 科目 ----
        if (subjects.isNotEmpty()) {
            Text(stringResource(R.string.practice_filter_subject), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                subjects.forEach { subject ->
                    FilterChip(
                        selected = subject in selectedSubjects,
                        onClick = {
                            selectedSubjects = if (subject in selectedSubjects) {
                                selectedSubjects - subject
                            } else {
                                selectedSubjects + subject
                            }
                        },
                        label = { Text(subjectLabel(subject)) },
                    )
                }
            }
        }

        // ---- 分类（跟随科目）----
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

        // ---- 题型 ----
        if (types.isNotEmpty()) {
            Text(stringResource(R.string.practice_filter_type), style = MaterialTheme.typography.titleMedium)
            types.chunked(4).forEach { rowTypes ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowTypes.forEach { type ->
                        FilterChip(
                            selected = type in selectedTypes,
                            onClick = {
                                selectedTypes = if (type in selectedTypes) {
                                    selectedTypes - type
                                } else {
                                    selectedTypes + type
                                }
                            },
                            label = { Text(typeLabel(type)) },
                        )
                    }
                }
            }
        }

        // ---- 范围 ----
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

        // ---- 顺序 ----
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

        // ---- 题量 ----
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

/** 科目代码 → 展示名（Schema 里是小写代码） */
private fun subjectLabel(code: String): String = when (code) {
    "kotlin" -> "Kotlin"
    "java" -> "Java"
    else -> code
}

/** 题型代码 → 展示名 */
@Composable
private fun typeLabel(code: String): String = when (code) {
    "single_choice" -> stringResource(R.string.type_single_choice)
    "multiple_choice" -> stringResource(R.string.type_multiple_choice)
    "true_false" -> stringResource(R.string.type_true_false)
    "fill_in_blank" -> stringResource(R.string.type_fill_in_blank)
    else -> code
}
