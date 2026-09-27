package com.baiyin.zhilian.ui.screens.practice

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.baiyin.zhilian.data.batch.BatchJson
import com.baiyin.zhilian.data.batch.OptionDto
import com.baiyin.zhilian.data.batch.SourceDto
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.practice.SubmitSummary
import com.baiyin.zhilian.data.practice.UserAnswer
import com.baiyin.zhilian.ui.components.QuestionMarkdown
import kotlinx.coroutines.launch

/** 单题提交结果（就地反馈与内联解析依据） */
private data class SubmitResult(val scoreRate: Double, val perfect: Boolean)

/**
 * 练习会话：一题一卡的卡片流（ADR-0003）。
 * - HorizontalPager 左右滑动切题，peek 露边暗示；滑动纯导航，未提交可滑回修改
 * - 提交后就地高亮 + 卡片内联展开解析（弹层废弃）
 * - 跳过为卡内显式按钮，不记作答；结尾卡收束会话（统计 + 完成）
 * - 首版退出不恢复会话（README），已提交作答保留在库中
 */
@Composable
fun PracticeSessionScreen(
    container: AppContainer,
    questionIds: List<String>,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var questions by remember { mutableStateOf<List<QuestionEntity>>(emptyList()) }
    /** 未提交的当前作答（按题索引） */
    val answers = remember { mutableStateMapOf<Int, UserAnswer>() }
    /** 已提交结果（按题索引）；存在即该卡为只读反馈态 */
    val submitted = remember { mutableStateMapOf<Int, SubmitResult>() }
    /** 点过跳过且未作答的题索引（作答后移除） */
    val skipped = remember { mutableStateMapOf<Int, Boolean>() }
    /** 提交进行中（按题索引）；防双击重复落库（ADR-0004） */
    val submitting = remember { mutableStateMapOf<Int, Boolean>() }
    var confirmExit by remember { mutableStateOf(false) }

    LaunchedEffect(questionIds) {
        questions = if (questionIds.isEmpty()) {
            emptyList()
        } else {
            container.database.questionDao().getByIds(questionIds)
        }
    }

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { questions.size + 1 })

    BackHandler { confirmExit = true }

    if (questions.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.session_exit_title)) },
            text = { Text(stringResource(R.string.session_exit_body)) },
            confirmButton = {
                TextButton(onClick = onExit) { Text(stringResource(R.string.session_exit_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    val pageCount = questions.size + 1

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(vertical = 8.dp),
    ) {
        // 固定进度区（卡片外顶部）
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(
                stringResource(R.string.session_progress, (pagerState.currentPage + 1).coerceAtMost(pageCount), pageCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { (pagerState.currentPage + 1f) / pageCount },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp),
            pageSpacing = 16.dp,
        ) { page ->
            if (page == questions.size) {
                SummaryCard(
                    answered = submitted.size,
                    perfect = submitted.values.count { it.perfect },
                    skippedCount = skipped.size,
                    questionCount = questions.size,
                    firstUnansweredIndex = questions.indices.firstOrNull { it !in submitted },
                    onJumpToUnanswered = { index ->
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    onExit = onExit,
                )
            } else {
                QuestionCard(
                    question = questions[page],
                    pageLabel = stringResource(R.string.session_progress, page + 1, questions.size),
                    userAnswer = answers[page],
                    result = submitted[page],
                    isSkipped = skipped.containsKey(page),
                    isSubmitting = submitting[page] == true,
                    onAnswerChange = { answers[page] = it },
                    onSubmit = {
                        val userAnswer = answers[page]
                        if (userAnswer != null && submitting[page] != true) {
                            scope.launch {
                                submitting[page] = true
                                try {
                                    val summary = container.practiceRepository.submitAnswer(questions[page], userAnswer)
                                    submitted[page] = SubmitResult(summary.rate, summary.perfect)
                                    skipped.remove(page)
                                } finally {
                                    submitting[page] = false
                                }
                            }
                        }
                    },
                    onSkip = { skipped[page] = true },
                )
            }
        }
    }
}

/** 单张题卡：题干 + 作答区 + 操作行（跳过/提交）+ 提交后内联解析 */
@Composable
private fun QuestionCard(
    question: QuestionEntity,
    pageLabel: String,
    userAnswer: UserAnswer?,
    result: SubmitResult?,
    isSkipped: Boolean,
    isSubmitting: Boolean,
    onAnswerChange: (UserAnswer) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val options: List<OptionRow> = remember(question) {
        question.optionsJson?.let {
            runCatching { BatchJson.json.decodeFromString<List<OptionDto>>(it) }.getOrNull()
        }?.map { OptionRow(it.optionId, it.text) } ?: emptyList()
    }
    val correctOptionIds: Set<String> = remember(question) {
        when (question.type) {
            "single_choice" -> runCatching {
                setOf(BatchJson.json.decodeFromString<String>(question.answerJson))
            }.getOrDefault(emptySet())
            "multiple_choice" -> runCatching {
                BatchJson.json.decodeFromString<List<String>>(question.answerJson).toSet()
            }.getOrDefault(emptySet())
            else -> emptySet()
        }
    }
    val blankAcceptable: List<String> = remember(question) {
        if (question.type == "fill_in_blank") {
            runCatching { BatchJson.json.decodeFromString<List<String>>(question.answerJson) }
                .getOrDefault(emptyList())
        } else emptyList()
    }
    val trueFalseAnswer: Boolean = remember(question) {
        if (question.type == "true_false") {
            runCatching { BatchJson.json.decodeFromString<Boolean>(question.answerJson) }.getOrDefault(false)
        } else false
    }
    val revealed = result != null

    Card(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(pageLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            QuestionMarkdown(content = question.stem)

            when (question.type) {
                "single_choice" -> {
                    options.forEach { option ->
                        val selected = (userAnswer as? UserAnswer.Single)?.optionId == option.optionId
                        val isCorrect = option.optionId in correctOptionIds
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = selected, enabled = !revealed) {
                                    onAnswerChange(UserAnswer.Single(option.optionId))
                                },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    revealed && isCorrect -> MaterialTheme.colorScheme.primary
                                    revealed && selected && !isCorrect -> MaterialTheme.colorScheme.error
                                    selected -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                        ) {
                            Row(modifier = Modifier.padding(12.dp)) {
                                Text("${option.optionId}. ", style = MaterialTheme.typography.titleMedium)
                                QuestionMarkdown(content = option.text, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                "multiple_choice" -> {
                    options.forEach { option ->
                        val checked = (userAnswer as? UserAnswer.Multiple)?.optionIds?.contains(option.optionId) == true
                        val isCorrect = option.optionId in correctOptionIds
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(value = checked, enabled = !revealed) {
                                    val current = (userAnswer as? UserAnswer.Multiple)?.optionIds ?: emptySet()
                                    onAnswerChange(
                                        UserAnswer.Multiple(
                                            if (option.optionId in current) current - option.optionId
                                            else current + option.optionId
                                        )
                                    )
                                },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    revealed && isCorrect -> MaterialTheme.colorScheme.primary
                                    revealed && checked && !isCorrect -> MaterialTheme.colorScheme.error
                                    checked -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                        ) {
                            Row(modifier = Modifier.padding(12.dp)) {
                                Text("${option.optionId}. ", style = MaterialTheme.typography.titleMedium)
                                QuestionMarkdown(content = option.text, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                "true_false" -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(
                            stringResource(R.string.tf_true) to true,
                            stringResource(R.string.tf_false) to false,
                        ).forEach { (label, value) ->
                            val selected = (userAnswer as? UserAnswer.TrueFalse)?.value == value
                            val isCorrect = revealed && value == trueFalseAnswer
                            val isSelectedWrong = revealed && selected && value != trueFalseAnswer
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .selectable(selected = selected, enabled = !revealed) {
                                        onAnswerChange(UserAnswer.TrueFalse(value))
                                    },
                                colors = if (selected || isCorrect) {
                                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                                } else {
                                    CardDefaults.cardColors()
                                },
                                border = BorderStroke(
                                    1.dp,
                                    when {
                                        isCorrect -> MaterialTheme.colorScheme.primary
                                        isSelectedWrong -> MaterialTheme.colorScheme.error
                                        selected -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                "fill_in_blank" -> {
                    OutlinedTextField(
                        value = (userAnswer as? UserAnswer.Blank)?.text ?: "",
                        onValueChange = { onAnswerChange(UserAnswer.Blank(it)) },
                        enabled = !revealed,
                        label = { Text(stringResource(R.string.blank_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (revealed) {
                        Text(
                            stringResource(R.string.blank_acceptable, blankAcceptable.joinToString(" / ")),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (isSkipped && !revealed) {
                Text(
                    stringResource(R.string.session_skipped_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 操作行：未提交时 跳过 + 提交；提交后由解析区替换
            if (!revealed) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.session_skip))
                    }
                    Button(
                        onClick = onSubmit,
                        enabled = !isSubmitting && userAnswer != null &&
                            (question.type != "multiple_choice" ||
                                (userAnswer as? UserAnswer.Multiple)?.optionIds?.isNotEmpty() == true),
                        modifier = Modifier.weight(2f),
                    ) {
                        Text(stringResource(R.string.session_submit))
                    }
                }
            }

            // 提交后：反馈横幅 + 内联解析（ADR-0003：弹层废弃）
            AnimatedVisibility(visible = revealed) {
                val r = result
                if (r != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when {
                                r.perfect -> stringResource(R.string.session_perfect)
                                r.scoreRate > 0 -> stringResource(R.string.session_partial, (r.scoreRate * 100).toInt())
                                else -> stringResource(R.string.session_wrong)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = if (r.perfect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        QuestionMarkdown(content = question.explanation)
                        SourceLine(question)
                    }
                }
            }
        }
    }
}

/** 结尾卡：会话小结 + 未答完提醒 + 完成退出（ADR-0003） */
@Composable
private fun SummaryCard(
    answered: Int,
    perfect: Int,
    skippedCount: Int,
    questionCount: Int,
    firstUnansweredIndex: Int?,
    onJumpToUnanswered: (Int) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.session_summary_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.session_summary_body, answered, perfect, skippedCount),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            if (firstUnansweredIndex != null) {
                Text(
                    stringResource(R.string.session_unfinished_hint, questionCount - answered),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(
                    onClick = { onJumpToUnanswered(firstUnansweredIndex) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(stringResource(R.string.session_jump_unanswered))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.session_done))
            }
        }
    }
}

@Composable
private fun SourceLine(question: QuestionEntity) {
    val source = runCatching {
        BatchJson.json.decodeFromString<SourceDto>(question.sourceJson)
    }.getOrNull() ?: return
    val detail = buildString {
        append(stringResource(R.string.source_prefix))
        append(source.title)
        source.url?.let { append(" · ").append(it) }
        source.note?.let { append(" · ").append(it) }
    }
    Text(
        detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private data class OptionRow(val optionId: String, val text: String)
