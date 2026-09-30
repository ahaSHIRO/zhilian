package com.baiyin.zhilian.ui.screens.practice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.practice.PracticeSession
import com.baiyin.zhilian.data.practice.SessionFeedback
import com.baiyin.zhilian.data.practice.SessionSummary
import com.baiyin.zhilian.data.practice.SubmitSummary
import com.baiyin.zhilian.data.practice.UserAnswer
import com.baiyin.zhilian.data.question.QuestionContent
import com.baiyin.zhilian.ui.components.QuestionMarkdown
import com.baiyin.zhilian.ui.components.ZhilianCard
import com.baiyin.zhilian.ui.components.ZhilianOptionRow
import com.baiyin.zhilian.ui.theme.ZhilianSpacing
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * 练习会话：一题一卡的卡片流（ADR-0003）。
 * - HorizontalPager 左右滑动切题，peek 露边暗示；滑动纯导航，未提交可滑回修改
 * - 提交后就地高亮 + 反馈横幅留在卡内；解析走半模态面板，**双击已提交题卡**弹出（ADR-0007）
 * - 选项打乱：按 questionId 种子稳定打乱单选/多选行序（开关在配置页，默认开）
 * - 跳过为卡内显式按钮，不记作答；结尾卡收束会话（统计 + 会话得分 + 完成）
 * - 首版退出不恢复会话（README），已提交作答保留在库中
 *
 * 会话的口径（得分、可提交性、反馈分级、未答定位）全在 [PracticeSession]；
 * 本页只持有 Compose 瞬时状态并转发事件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeSessionScreen(
    container: AppContainer,
    questionIds: List<String>,
    shuffleOptions: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var questions by remember { mutableStateOf<List<QuestionEntity>>(emptyList()) }
    /** 未提交的当前作答（按题索引） */
    val answers = remember { mutableStateMapOf<Int, UserAnswer>() }
    /** 已提交结果（按题索引）；存在即该卡为只读反馈态 */
    val submitted = remember { mutableStateMapOf<Int, SubmitSummary>() }
    /** 点过跳过且未作答的题索引（作答后移除） */
    val skipped = remember { mutableStateMapOf<Int, Boolean>() }
    /** 提交进行中（按题索引）；防双击重复落库（ADR-0004） */
    val submitting = remember { mutableStateMapOf<Int, Boolean>() }
    /** 当前升起半模态解析面板的题索引；null = 面板关闭（ADR-0007） */
    var explanationFor by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(questionIds) {
        questions = container.questionBank.get(questionIds)
    }

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { questions.size + 1 })

    // 滑到别的题就收起面板：面板属于当前卡片，不跨题保留（ADR-0007）
    LaunchedEffect(pagerState.currentPage) {
        explanationFor = null
    }

    // 返回手势/按键不在此拦截：面板打开时由 ModalBottomSheet 原生预测返回（跟手下移关闭）
    // 优先消费；面板关闭时返回由 NavHost 接管——侧滑跟手退出会话（ADR-0009），不再弹确认框。

    if (questions.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(ZhilianSpacing.screenEdge),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val pageCount = questions.size + 1

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(vertical = ZhilianSpacing.sm),
    ) {
        // 固定进度区（卡片外顶部）
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ZhilianSpacing.xl)) {
            Text(
                stringResource(R.string.session_progress, (pagerState.currentPage + 1).coerceAtMost(pageCount), pageCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { (pagerState.currentPage + 1f) / pageCount },
                modifier = Modifier.fillMaxWidth().padding(top = ZhilianSpacing.sm),
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = ZhilianSpacing.xl),
            pageSpacing = ZhilianSpacing.lg,
        ) { page ->
            if (page == questions.size) {
                // 结尾卡统计与会话得分口径见 CONTEXT.md「会话得分」/ [PracticeSession.summary]
                SummaryCard(
                    summary = PracticeSession.summary(submitted.values, questions.size, skipped.size),
                    questionCount = questions.size,
                    firstUnansweredIndex = PracticeSession.firstUnansweredIndex(
                        submittedIndices = submitted.keys.toSet(),
                        questionCount = questions.size,
                    ),
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
                    shuffleOptions = shuffleOptions,
                    onAnswerChange = { answers[page] = it },
                    onSubmit = {
                        val userAnswer = answers[page]
                        if (userAnswer != null && submitting[page] != true) {
                            scope.launch {
                                submitting[page] = true
                                try {
                                    val summary = container.practiceRepository.submitAnswer(questions[page], userAnswer)
                                    submitted[page] = summary
                                    skipped.remove(page)
                                } finally {
                                    submitting[page] = false
                                }
                            }
                        }
                    },
                    onSkip = { skipped[page] = true },
                    onDoubleTap = if (submitted.containsKey(page)) {
                        { explanationFor = page }
                    } else {
                        null
                    },
                )
            }
        }
    }

    // 半模态解析面板（ADR-0007）：约六成屏高，可滚动；下拉或点遮罩关闭
    val sheetPage = explanationFor
    if (sheetPage != null && sheetPage in questions.indices) {
        val question = questions[sheetPage]
        val result = submitted[sheetPage]
        // 双击打开默认全屏（skipPartiallyExpanded=true 跳过半屏档）；
        // 短解析也无半屏露白、专注解析。下拉或点遮罩关闭，滑到下一题自动收起。
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        LaunchedEffect(sheetPage) {
            sheetState.expand()
        }

        ModalBottomSheet(
            onDismissRequest = { explanationFor = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            ExplanationSheet(
                question = question,
                result = result,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 提交结论文案（题卡横幅与解析面板共用同一分级，避免两处各写一套 when） */
@Composable
private fun feedbackText(result: SubmitSummary): String =
    when (PracticeSession.feedbackOf(result)) {
        SessionFeedback.Perfect -> stringResource(R.string.session_perfect)
        SessionFeedback.Partial -> stringResource(R.string.session_partial, (result.rate * 100).toInt())
        SessionFeedback.Wrong -> stringResource(R.string.session_wrong)
    }

/**
 * 半模态解析面板内容：反馈结论 + 教材式解析 + 来源（ADR-0007）。
 * 解析在此可垂直滚动，卡片本身不再被解析撑长。
 */
@Composable
private fun ExplanationSheet(
    question: QuestionEntity,
    result: SubmitSummary?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ZhilianSpacing.cardInner)
            .padding(bottom = ZhilianSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.stackGap),
    ) {
        if (result != null) {
            Text(
                feedbackText(result),
                style = MaterialTheme.typography.titleLarge,
                color = if (result.perfect) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        QuestionMarkdown(content = question.explanation)
        SourceLine(question)
    }
}

/** 单张题卡：题干 + 作答区 + 操作行（跳过/提交）+ 提交后反馈横幅（解析走半模态面板，ADR-0007） */
@Composable
private fun QuestionCard(
    question: QuestionEntity,
    pageLabel: String,
    userAnswer: UserAnswer?,
    result: SubmitSummary?,
    isSkipped: Boolean,
    isSubmitting: Boolean,
    shuffleOptions: Boolean,
    onAnswerChange: (UserAnswer) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    onDoubleTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // 选项打乱：以 questionId 为种子的稳定打乱——同一题在任何会话中顺序一致，
    // 滑回上题不闪变；字母（optionId）跟内容走，判分与解析引用不受影响
    val options: List<OptionRow> = remember(question, shuffleOptions) {
        QuestionContent.options(question.optionsJson)
            .map { OptionRow(it.optionId, it.text) }
            .stablyShuffled(shuffleOptions, question.questionId.hashCode())
    }
    // 答案解码统一走 QuestionContent：四种题型的分派只此一份（判分/预览/会话共用）
    val correctOptionIds: Set<String> = remember(question) {
        QuestionContent.correctOptionIds(question.type, question.answerJson)
    }
    val blankAcceptable: List<String> = remember(question) {
        if (question.type == "fill_in_blank") {
            QuestionContent.blankAcceptables(question.answerJson)
        } else {
            emptyList()
        }
    }
    val trueFalseAnswer: Boolean = remember(question) {
        question.type == "true_false" && QuestionContent.trueFalseAnswer(question.answerJson)
    }
    val revealed = result != null
    val canSubmit = PracticeSession.canSubmit(question.type, userAnswer)

    ZhilianCard(
        modifier = modifier
            .fillMaxSize()
            .then(
                // 已提交后双击卡片任意位置（含选项）弹出解析；
                // 提交后选项只读、不挂 selectable/toggleable，不消费点击，父级双击覆盖整卡
                if (onDoubleTap != null) {
                    Modifier.pointerInput(onDoubleTap) {
                        detectTapGestures(onDoubleTap = { onDoubleTap() })
                    }
                } else {
                    Modifier
                }
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ZhilianSpacing.cardInner)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
            verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.stackGap),
        ) {
            Text(pageLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            QuestionMarkdown(content = question.stem)

            when (question.type) {
                "single_choice" -> {
                    options.forEach { option ->
                        val selected = (userAnswer as? UserAnswer.Single)?.optionId == option.optionId
                        val isCorrect = option.optionId in correctOptionIds
                        ZhilianOptionRow(
                            selected = selected,
                            revealed = revealed,
                            isCorrect = isCorrect,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (!revealed) Modifier.selectable(selected = selected) {
                                    onAnswerChange(UserAnswer.Single(option.optionId))
                                } else Modifier),
                        ) {
                            Text("${option.optionId}. ", style = MaterialTheme.typography.titleMedium)
                            QuestionMarkdown(content = option.text, modifier = Modifier.weight(1f))
                        }
                    }
                }
                "multiple_choice" -> {
                    options.forEach { option ->
                        val checked = (userAnswer as? UserAnswer.Multiple)?.optionIds?.contains(option.optionId) == true
                        val isCorrect = option.optionId in correctOptionIds
                        ZhilianOptionRow(
                            selected = checked,
                            revealed = revealed,
                            isCorrect = isCorrect,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (!revealed) Modifier.toggleable(value = checked) {
                                    val current = (userAnswer as? UserAnswer.Multiple)?.optionIds ?: emptySet()
                                    onAnswerChange(
                                        UserAnswer.Multiple(
                                            if (option.optionId in current) current - option.optionId
                                            else current + option.optionId
                                        )
                                    )
                                } else Modifier),
                        ) {
                            Text("${option.optionId}. ", style = MaterialTheme.typography.titleMedium)
                            QuestionMarkdown(content = option.text, modifier = Modifier.weight(1f))
                        }
                    }
                }
                "true_false" -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.stackGap)) {
                        listOf(
                            stringResource(R.string.tf_true) to true,
                            stringResource(R.string.tf_false) to false,
                        ).forEach { (label, value) ->
                            val selected = (userAnswer as? UserAnswer.TrueFalse)?.value == value
                            val isCorrect = revealed && value == trueFalseAnswer
                            ZhilianOptionRow(
                                selected = selected,
                                revealed = revealed,
                                isCorrect = isCorrect,
                                modifier = Modifier
                                    .weight(1f)
                                    .then(if (!revealed) Modifier.selectable(selected = selected) {
                                        onAnswerChange(UserAnswer.TrueFalse(value))
                                    } else Modifier),
                                containerColor = if (selected || isCorrect) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(ZhilianSpacing.xs).fillMaxWidth(),
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

            // 操作行：未提交时 跳过 + 提交；提交后由反馈横幅替换
            if (!revealed) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = ZhilianSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.stackGap),
                ) {
                    OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.session_skip))
                    }
                    Button(
                        onClick = onSubmit,
                        enabled = canSubmit && !isSubmitting,
                        modifier = Modifier.weight(2f),
                    ) {
                        Text(stringResource(R.string.session_submit))
                    }
                }
            }

            // 提交后：反馈横幅留在卡内（ADR-0007），解析与来源移到半模态面板，
            // 避免教材式解析把卡片撑到整页；面板入口为双击卡片（无按钮）
            AnimatedVisibility(visible = revealed) {
                val r = result
                if (r != null) {
                    Text(
                        feedbackText(r),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (r.perfect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 结尾卡：会话小结 + 得分 + 未答完提醒 + 完成退出（ADR-0003） */
@Composable
private fun SummaryCard(
    summary: SessionSummary,
    questionCount: Int,
    firstUnansweredIndex: Int?,
    onJumpToUnanswered: (Int) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ZhilianCard(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(ZhilianSpacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.session_summary_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(
                    R.string.session_summary_body,
                    summary.answered,
                    summary.perfect,
                    summary.skipped,
                ),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = ZhilianSpacing.lg),
            )
            Text(
                stringResource(R.string.session_summary_score, summary.score),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (firstUnansweredIndex != null) {
                Text(
                    stringResource(R.string.session_unfinished_hint, questionCount - summary.answered),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(
                    onClick = { onJumpToUnanswered(firstUnansweredIndex) },
                    modifier = Modifier.fillMaxWidth().padding(top = ZhilianSpacing.md),
                ) {
                    Text(stringResource(R.string.session_jump_unanswered))
                }
                Spacer(modifier = Modifier.height(ZhilianSpacing.sm))
            }
            Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.session_done))
            }
        }
    }
}

@Composable
private fun SourceLine(question: QuestionEntity) {
    val source = QuestionContent.source(question.sourceJson) ?: return
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

/**
 * 选项行稳定打乱：开启时以 seed（questionId 哈希）重排行序，关闭时原样返回。
 * 稳定含义：同一 seed 永远得到同一排列——滑回上题、重进会话、日后重刷同题都不闪变。
 * 只重排不增删，元素字母（optionId）跟内容走，判分与解析引用不受影响。
 * internal 以便单元测试覆盖（双射性/稳定性/开关行为）。
 */
internal fun <T> List<T>.stablyShuffled(enabled: Boolean, seed: Int): List<T> =
    if (enabled) shuffled(Random(seed)) else this
