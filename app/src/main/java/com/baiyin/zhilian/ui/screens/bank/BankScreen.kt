package com.baiyin.zhilian.ui.screens.bank

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baiyin.zhilian.ui.theme.ZhilianSpacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.batch.BatchJson
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.ui.components.QuestionMarkdown
import com.baiyin.zhilian.ui.components.ZhilianCard
import com.baiyin.zhilian.ui.components.rememberBottomBarContentPadding
import kotlinx.coroutines.launch

/**
 * 题库页：分类 chips 筛选 + 范围（错题/收藏）+ 题目列表；点开只读详情弹层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val allQuestions by container.database.questionDao().observeActive()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by container.database.questionDao().observeCategories()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var selectedCategories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var onlyWrong by remember { mutableStateOf(false) }
    var onlyFavorite by remember { mutableStateOf(false) }
    var detail: QuestionEntity? by remember { mutableStateOf(null) }

    val filtered = allQuestions.filter { q ->
        (selectedCategories.isEmpty() || q.category in selectedCategories) &&
            (!onlyWrong || q.isWrong) &&
            (!onlyFavorite || q.favorite)
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = ZhilianSpacing.screenEdge)) {
        if (categories.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = ZhilianSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm),
            ) {
                categories.take(4).forEach { category ->
                    FilterChip(
                        selected = category in selectedCategories,
                        onClick = {
                            selectedCategories = if (category in selectedCategories) {
                                selectedCategories - category
                            } else selectedCategories + category
                        },
                        label = { Text(category) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm),
            ) {
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
        }

        if (filtered.isEmpty()) {
            Text(
                stringResource(R.string.bank_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = ZhilianSpacing.xl),
            )
        }

        // 底栏是浮层、内容穿到它背后（ADR-0010）：列表底部留出底栏高度，
        // 否则最后一道题被永久遮住。用 contentPadding 而非外层 padding，内容才能滚到底栏背后。
        val bottomBarPadding = rememberBottomBarContentPadding()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = ZhilianSpacing.sm,
                bottom = bottomBarPadding,
            ),
        ) {
            items(filtered, key = { it.questionId }) { q ->
                ZhilianCard(
                    modifier = Modifier.fillMaxWidth().clickable { detail = q },
                    border = if (q.isWrong) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    } else null,
                ) {
                    Column(modifier = Modifier.padding(ZhilianSpacing.cardInnerCompact)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                q.category,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "  #" + q.typeLabel(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 题干预览（纯文本截断，不渲染 Markdown）
                        Text(
                            q.stem.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                        )
                        Text(
                            buildString {
                                if (q.isWrong) append(stringResource(R.string.badge_wrong) + " ")
                                if (q.favorite) append(stringResource(R.string.badge_favorite))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (q.isWrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    detail?.let { q ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZhilianSpacing.screenEdge),
                verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm),
            ) {
                Text(q.category, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                QuestionMarkdown(content = q.stem)
                Text(
                    stringResource(R.string.bank_detail_answer),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(answerPreview(q), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.bank_detail_explanation),
                    style = MaterialTheme.typography.titleSmall,
                )
                QuestionMarkdown(content = q.explanation)
                TextButton(
                    onClick = { scope.launch { container.database.questionDao().setFavorite(q.questionId, !q.favorite) } },
                ) {
                    Text(
                        if (q.favorite) stringResource(R.string.favorite_remove)
                        else stringResource(R.string.favorite_add)
                    )
                }
                TextButton(onClick = { detail = null }, modifier = Modifier.padding(bottom = ZhilianSpacing.xl)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

private fun QuestionEntity.typeLabel(): String = when (type) {
    "single_choice" -> "单选"
    "multiple_choice" -> "多选"
    "true_false" -> "判断"
    "fill_in_blank" -> "填空"
    else -> type
}

/** 详情页答案预览（人读形式） */
private fun answerPreview(q: QuestionEntity): String = when (q.type) {
    "true_false" -> if (
        runCatching { BatchJson.json.decodeFromString<Boolean>(q.answerJson) }.getOrDefault(false)
    ) "正确" else "错误"
    "fill_in_blank" -> runCatching {
        BatchJson.json.decodeFromString<List<String>>(q.answerJson).joinToString(" / ")
    }.getOrDefault("-")
    "multiple_choice" -> runCatching {
        BatchJson.json.decodeFromString<List<String>>(q.answerJson).sorted().joinToString("、")
    }.getOrDefault("-")
    else -> runCatching { BatchJson.json.decodeFromString<String>(q.answerJson) }.getOrDefault("-")
}
