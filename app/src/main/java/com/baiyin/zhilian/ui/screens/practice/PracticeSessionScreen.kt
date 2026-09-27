package com.baiyin.zhilian.ui.screens.practice

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.baiyin.zhilian.data.practice.Scoring
import com.baiyin.zhilian.data.practice.UserAnswer
import com.baiyin.zhilian.ui.components.QuestionMarkdown
import kotlinx.coroutines.launch

/**
 * 练习会话：一题一屏；首版退出不恢复（README），已提交作答保留在库中。
 * 状态全部为会话内存态，不使用 ViewModel（会话无存活必要）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeSessionScreen(
    container: AppContainer,
    questionIds: List<String>,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var questions by remember { mutableStateOf<List<QuestionEntity>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var answeredCount by remember { mutableIntStateOf(0) }
    var perfectCount by remember { mutableIntStateOf(0) }
    var skippedCount by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(questionIds) {
        questions = if (questionIds.isEmpty()) {
            emptyList()
        } else {
            container.database.questionDao().getByIds(questionIds)
        }
    }

    val scope = rememberCoroutineScope()

    // 每题状态（切题即重置）
    var answer by remember(index) { mutableStateOf<UserAnswer?>(null) }
    var revealed by remember(index) { mutableStateOf(false) }
    var lastScore by remember(index) { mutableStateOf(0.0) }
    var lastPerfect by remember(index) { mutableStateOf(false) }
    var sheetShown by remember(index) { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }

    val question = questions.getOrNull(index)

    // 退出确认：练习中退出不恢复会话（README），已提交作答保留
    BackHandler(enabled = !finished) { confirmExit = true }

    if (finished) {
        Column(
            modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.session_summary_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(
                    R.string.session_summary_body,
                    answeredCount, perfectCount, skippedCount,
                ),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.session_done))
            }
        }
        return
    }

    if (question == null) {
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

    val options: List<OptionRow> = remember(question) {
        question.optionsJson?.let {
            runCatching { BatchJson.json.decodeFromString<List<OptionDto>>(it) }
                .getOrNull()
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

    fun submit() {
        val userAnswer = answer ?: return
        val (scoreRate, perfect) = Scoring.score(question, userAnswer)
        scope.launch {
            container.practiceRepository.submitAnswer(question, userAnswer, scoreRate, perfect)
            answeredCount++
            if (perfect) perfectCount++
            lastScore = scoreRate
            lastPerfect = perfect
            revealed = true
            sheetShown = true
        }
    }

    fun advance() {
        val next = index + 1
        if (next < questions.size) index = next else finished = true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.session_progress, index + 1, questions.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QuestionMarkdown(content = question.stem)

        when (question.type) {
            "single_choice" -> {
                options.forEach { option ->
                    val selected = (answer as? UserAnswer.Single)?.optionId == option.optionId
                    val isCorrect = option.optionId in correctOptionIds
                    val borderColor = when {
                        revealed && isCorrect -> MaterialTheme.colorScheme.primary
                        revealed && selected && !isCorrect -> MaterialTheme.colorScheme.error
                        selected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected, enabled = !revealed) {
                                answer = UserAnswer.Single(option.optionId)
                            },
                        border = BorderStroke(1.dp, borderColor),
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
                    val checked = (answer as? UserAnswer.Multiple)?.optionIds?.contains(option.optionId) == true
                    val isCorrect = option.optionId in correctOptionIds
                    val borderColor = when {
                        revealed && isCorrect -> MaterialTheme.colorScheme.primary
                        revealed && checked && !isCorrect -> MaterialTheme.colorScheme.error
                        checked -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked, enabled = !revealed) {
                                val current = (answer as? UserAnswer.Multiple)?.optionIds ?: emptySet()
                                answer = UserAnswer.Multiple(
                                    if (option.optionId in current) current - option.optionId
                                    else current + option.optionId
                                )
                            },
                        border = BorderStroke(1.dp, borderColor),
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
                        val selected = (answer as? UserAnswer.TrueFalse)?.value == value
                        val isCorrect = revealed && value == trueFalseAnswer
                        val isSelectedWrong = revealed && selected && value != trueFalseAnswer
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .selectable(selected = selected, enabled = !revealed) {
                                    answer = UserAnswer.TrueFalse(value)
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
                var text by remember(index) { mutableStateOf((answer as? UserAnswer.Blank)?.text ?: "") }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        answer = UserAnswer.Blank(it)
                    },
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

        // 底部操作区：小白条 + 键盘双避让（edge-to-edge 规范）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!revealed) {
                OutlinedButton(onClick = {
                    skippedCount++
                    advance()
                }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.session_skip))
                }
                Button(
                    onClick = { submit() },
                    enabled = answer != null &&
                        (question.type != "multiple_choice" ||
                            (answer as? UserAnswer.Multiple)?.optionIds?.isNotEmpty() == true),
                    modifier = Modifier.weight(2f),
                ) {
                    Text(stringResource(R.string.session_submit))
                }
            } else {
                Text(
                    when {
                        lastPerfect -> stringResource(R.string.session_perfect)
                        lastScore > 0 -> stringResource(R.string.session_partial, (lastScore * 100).toInt())
                        else -> stringResource(R.string.session_wrong)
                    },
                    color = if (lastPerfect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                )
                Button(onClick = { advance() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.session_next))
                }
            }
        }
    }

    // 解析弹层（ADR-0002：就地高亮 + 底部解析弹层）
    if (sheetShown) {
        ModalBottomSheet(onDismissRequest = { sheetShown = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    when {
                        lastPerfect -> stringResource(R.string.session_perfect)
                        lastScore > 0 -> stringResource(R.string.session_partial, (lastScore * 100).toInt())
                        else -> stringResource(R.string.session_wrong)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = if (lastPerfect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                QuestionMarkdown(content = question.explanation)
                SourceLine(question)
                TextButton(
                    onClick = { sheetShown = false },
                    modifier = Modifier.padding(bottom = 24.dp),
                ) { Text(stringResource(R.string.session_close_sheet)) }
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
