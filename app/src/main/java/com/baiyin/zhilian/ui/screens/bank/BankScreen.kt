package com.baiyin.zhilian.ui.screens.bank

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CornerSize
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.question.QuestionContent
import com.baiyin.zhilian.ui.components.Loadable
import com.baiyin.zhilian.ui.components.QuestionMarkdown
import com.baiyin.zhilian.ui.components.ZhilianCard
import com.baiyin.zhilian.ui.components.collectAsLoadable
import com.baiyin.zhilian.ui.components.TabLazyColumn
import com.baiyin.zhilian.ui.components.typeLabel
import com.baiyin.zhilian.ui.theme.ZhilianSpacing
import kotlinx.coroutines.launch

/**
 * 题库页：分类 chips 筛选 + 范围（错题/收藏）+ 题目列表；点开只读详情弹层。
 *
 * 首帧门控（C3）：题目与分类两个流都到达前不渲染任何终态文案——否则会先闪一句
 * 「没有符合条件的题目。」，随后列表插入。分类 chips 一行横向滚动，不再截断为前 4 个。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankScreen(container: AppContainer, modifier: Modifier = Modifier) {
    // 流持有稳定实例：否则每次重组都重订阅一次（功能正确，但白查一次库）。
    // 首帧门控走 collectAsLoadable：值到达后不会因重订阅回落首帧态（见 LoadableState 说明）
    val questionsFlow = remember(container) { container.questionBank.observeQuestions() }
    val categoriesFlow = remember(container) { container.questionBank.observeCategories() }
    val questionsLoad by questionsFlow.collectAsLoadable()
    val categoriesLoad by categoriesFlow.collectAsLoadable()

    val ready = questionsLoad is Loadable.Data && categoriesLoad is Loadable.Data
    val allQuestions = (questionsLoad as? Loadable.Data)?.value.orEmpty()
    val categories = (categoriesLoad as? Loadable.Data)?.value.orEmpty()

    val scope = rememberCoroutineScope()
    var selectedCategories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var onlyWrong by remember { mutableStateOf(false) }
    var onlyFavorite by remember { mutableStateOf(false) }

    /** 详情面板只记 id：题目状态由流驱动，收藏写完面板立即反映（C6） */
    var detailId by remember { mutableStateOf<String?>(null) }

    val filtered = allQuestions.filter { q ->
        (selectedCategories.isEmpty() || q.category in selectedCategories) &&
            (!onlyWrong || q.isWrong) &&
            (!onlyFavorite || q.favorite)
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = ZhilianSpacing.screenEdge)) {
        if (ready) {
            if (categories.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = ZhilianSpacing.sm)) {
                    // 分类 chips 横向滚动：原先 take(4) 会让第 5 个分类既看不到也选不了。
                    // 用 LazyRow 而非「Row + horizontalScroll」：只组合可见的 chip，分类多时不白建节点
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm),
                    ) {
                        items(categories) { category ->
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
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = ZhilianSpacing.sm),
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
            }

            if (filtered.isEmpty()) {
                Text(
                    stringResource(R.string.bank_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = ZhilianSpacing.xl),
                )
            }

            // 底栏是浮层、内容穿到它背后（ADR-0010）：底部留白由 TabLazyColumn 外壳叠加，
            // 这里只管顶部间距——内容才能滚到底栏背后（别改成外层 padding）
            TabLazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm),
                contentPadding = PaddingValues(top = ZhilianSpacing.sm),
            ) {
                items(filtered, key = { it.questionId }) { q ->
                    ZhilianCard(
                        modifier = Modifier.fillMaxWidth().clickable { detailId = q.questionId },
                        border = if (q.isWrong) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        } else {
                            null
                        },
                    ) {
                        Column(modifier = Modifier.padding(ZhilianSpacing.cardInnerCompact)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    q.category,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    "  #" + typeLabel(q.type),
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
                                color = if (q.isWrong) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val openId = detailId
    if (openId != null) {
        // 按 id 订阅：抱点击瞬间的快照会让收藏按钮永远读旧值——点了不换文案、
        // 再点还是写同一个目标值，从面板里根本取消不了收藏。流同样 remember 住以免重订阅
        val questionFlow = remember(openId) { container.questionBank.observeQuestion(openId) }
        val question by questionFlow.collectAsStateWithLifecycle(initialValue = null)
        val q = question
        if (q != null) {
            ModalBottomSheet(
                onDismissRequest = { detailId = null },
                // 面板圆角走 token 槽位（design-tokens §3.2）：extraLarge=20dp，底部贴边取 0
                shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
            ) {
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
                        onClick = { scope.launch { container.questionBank.setFavorite(q.questionId, !q.favorite) } },
                    ) {
                        Text(
                            if (q.favorite) stringResource(R.string.favorite_remove)
                            else stringResource(R.string.favorite_add)
                        )
                    }
                    TextButton(onClick = { detailId = null }, modifier = Modifier.padding(bottom = ZhilianSpacing.xl)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

/** 详情页答案预览（人读形式）；答案解码走 [QuestionContent]，与判分同一份读取 */
private fun answerPreview(q: QuestionEntity): String = when (q.type) {
    "true_false" -> if (QuestionContent.trueFalseAnswer(q.answerJson)) "正确" else "错误"
    "fill_in_blank" -> QuestionContent.blankAcceptables(q.answerJson).joinToString(" / ").ifEmpty { "-" }
    "multiple_choice" -> QuestionContent.multipleAnswers(q.answerJson).sorted().joinToString("、").ifEmpty { "-" }
    else -> QuestionContent.singleAnswer(q.answerJson) ?: "-"
}
