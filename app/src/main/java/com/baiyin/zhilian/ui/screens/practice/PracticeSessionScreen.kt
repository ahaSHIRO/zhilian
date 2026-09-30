package com.baiyin.zhilian.ui.screens.practice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.practice.PracticeSession
import com.baiyin.zhilian.data.practice.ProcessToken
import com.baiyin.zhilian.data.practice.SessionArgs
import com.baiyin.zhilian.data.practice.SessionFeedback
import com.baiyin.zhilian.data.practice.SessionLoad
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
 * - HorizontalPager 左右滑动切题，**一屏只有本题卡片**（不露相邻卡边缘，见下）；滑动纯导航，未提交可滑回修改
 * - 提交后就地高亮 + 反馈横幅留在卡内；解析走半模态面板，**双击已提交题卡**弹出（ADR-0007）
 * - 选项打乱：按 questionId 种子稳定打乱单选/多选行序（开关随会话参数带入）
 * - 跳过为卡内显式按钮，不记作答；结尾卡收束会话（统计 + 会话得分 + 完成）
 * - 首版退出不恢复会话，**但配置变更不是退出**（CONTEXT.md「练习会话」）
 *
 * 会话的口径（得分、可提交性、反馈分级、未答定位）全在 [PracticeSession]；
 * 会话状态（作答/提交/跳过、载入四态）全在 [PracticeSessionState]；本页只转发事件并渲染。
 *
 * 四态分流：只有 [SessionLoad.Loading] 渲染转圈，其余三态各自给出路——原先「题目列表为空」
 * 一律转圈，参数丢失时会得到永不结束的加载，只能杀进程。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeSessionScreen(
    container: AppContainer,
    args: SessionArgs?,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 状态容器挂在 entry 作用域的 ViewModel 上：配置变更时随 Activity 的 ViewModelStore 存活
    val state: PracticeSessionState = viewModel(factory = viewModelFactory {
        initializer {
            PracticeSessionState(
                loader = container.practiceSessionLoader,
                submitAnswer = container.practiceRepository::submitAnswer,
                args = args,
                processToken = ProcessToken.value,
            )
        }
    })
    val scope = rememberCoroutineScope()

    // 幂等：配置变更后重组会重跑本 effect，已载入的会话不会被重新查询
    LaunchedEffect(state) { state.load() }

    val load = state.load
    // 进程终止重建：回退栈被系统恢复，会话状态却随进程消失。停在这里就是一个「什么都没记住」
    // 的会话页，故按未完成会话直接退回配置页（CONTEXT.md「练习会话」），不显示任何错误态。
    LaunchedEffect(load) { if (load.isStale) onExit() }

    if (load !is SessionLoad.Ready) {
        if (!load.isStale) {
            SessionGate(
                load = load,
                onRetry = { scope.launch { state.load(force = true) } },
                onExit = onExit,
                modifier = modifier,
            )
        }
        return
    }

    val questions = load.questions
    val pageCount = questions.size + 1
    val pagerState = rememberPagerState(pageCount = { pageCount })

    // 解析面板属当前卡片的瞬时 UI 态，不进状态容器：换题/重建后回到无面板是合理行为
    var explanationFor by remember { mutableStateOf<Int?>(null) }

    // 面板 state 提到这里：滑题时要先播收起动画再移除——ADR-0007 说的是「自动收起」，
    // 原先直接置 null 是瞬移消失，与 300ms 的侧滑转场并排看很突兀
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 滑到别的题就收起面板：面板属于当前卡片，不跨题保留（ADR-0007）
    LaunchedEffect(pagerState.currentPage) {
        if (explanationFor != null) {
            sheetState.hide()
            explanationFor = null
        }
    }

    // 返回手势/按键不在此拦截：面板打开时由 ModalBottomSheet 原生预测返回（跟手下移关闭）
    // 优先消费；面板关闭时返回由 NavHost 接管——侧滑跟手退出会话（ADR-0009），不再弹确认框。

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(vertical = ZhilianSpacing.sm),
        // 进度区与卡片之间留气口：原先两者紧贴（实测约 0.3dp），进度条像是压在卡片上
        verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.md),
    ) {
        // 固定进度区（卡片外顶部）：只统计题目——结尾卡不是「第 N 题」，
        // 故分母不含它（原先顶部写「第 1 / 21 题」而卡内写「第 1 / 20 题」，进度条也永远到不了 100%）。
        // 水平内边距与**卡片内文**对齐（同为 lg）：卡片已通栏，若对齐卡片外缘就会贴到屏幕边
        val questionCount = questions.size.coerceAtLeast(1)
        val shownPage = (pagerState.currentPage + 1).coerceAtMost(questionCount)
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = ZhilianSpacing.lg)) {
            Text(
                stringResource(R.string.session_progress, shownPage, questionCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { shownPage.toFloat() / questionCount },
                modifier = Modifier.fillMaxWidth().padding(top = ZhilianSpacing.sm),
            )
            // 部分题已被停用：非阻断提示，避免用户以为题量随机缩水
            if (load.droppedCount > 0) {
                Text(
                    stringResource(R.string.session_dropped, load.droppedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = ZhilianSpacing.xs),
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // 卡片**通栏**、一屏只有本题卡片：屏边距取 0，正文的左右 16dp 由卡片内边距承担，
            // 因而正文仍与全 App 的屏边距对齐；页间距取 lg(16) —— **页间距 > 屏边距**即相邻卡
            // 彻底移出屏幕（露出宽度 = 屏边距 − 页间距）。露出相邻卡原是为暗示「可滑」（ADR-0003），
            // 但本 App 单人自用、进过一次就知道能滑，而露边只会挤掉程序题的代码行宽度。
            // contentPadding 不给 = 0（默认值），卡片直接顶到屏幕左右边。
            pageSpacing = ZhilianSpacing.lg,
        ) { page ->
            if (page == questions.size) {
                // 结尾卡统计与会话得分口径见 CONTEXT.md「会话得分」/ [PracticeSession.summary]
                SummaryCard(
                    summary = PracticeSession.summary(
                        results = state.submitted.values,
                        questionCount = questions.size,
                        skippedCount = state.skipped.size,
                    ),
                    questionCount = questions.size,
                    firstUnansweredIndex = PracticeSession.firstUnansweredIndex(
                        submittedIndices = questions.indices
                            .filter { questions[it].questionId in state.submitted }
                            .toSet(),
                        questionCount = questions.size,
                    ),
                    onJumpToUnanswered = { index ->
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    onExit = onExit,
                )
            } else {
                val question = questions[page]
                val questionId = question.questionId
                QuestionCard(
                    question = question,
                    pageLabel = stringResource(R.string.session_progress, page + 1, questions.size),
                    userAnswer = state.answers[questionId],
                    result = state.submitted[questionId],
                    isSkipped = state.skipped.containsKey(questionId),
                    isSubmitting = state.submitting.containsKey(questionId),
                    shuffleOptions = state.shuffleOptions,
                    onAnswerChange = { state.answer(questionId, it) },
                    onSubmit = {
                        val userAnswer = state.answers[questionId]
                        if (userAnswer != null) {
                            scope.launch { state.submit(question, userAnswer) }
                        }
                    },
                    onSkip = { state.skip(questionId) },
                    onDoubleTap = if (state.submitted.containsKey(questionId)) {
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
        val result = state.submitted[question.questionId]
        // 双击打开默认全屏（skipPartiallyExpanded=true 跳过半屏档）；
        // 短解析也无半屏露白、专注解析。下拉或点遮罩关闭，滑到下一题自动收起。
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

/** 进程终止重建：会话参数由上一个进程写入（CONTEXT.md「练习会话」不恢复未完成会话） */
private val SessionLoad.isStale: Boolean
    get() = this is SessionLoad.Failed && reason == SessionLoad.FailureReason.StaleSession

/**
 * 非 [SessionLoad.Ready] 三态的统一出口：转圈只在 [SessionLoad.Loading]，
 * 其余两态都必须有可点的出路（重试只对可重试的失败开放）。
 */
@Composable
private fun SessionGate(
    load: SessionLoad,
    onRetry: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val retryable = load is SessionLoad.Failed && load.reason == SessionLoad.FailureReason.LoadFailed
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(ZhilianSpacing.screenEdge),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (load) {
            SessionLoad.Loading -> CircularProgressIndicator()

            is SessionLoad.Empty -> Text(
                stringResource(R.string.session_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is SessionLoad.Failed -> Text(
                stringResource(
                    if (retryable) R.string.session_load_failed else R.string.session_load_missing_args,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Ready 由调用方分流，这里到不了（仅为穷尽 when 保留）
            is SessionLoad.Ready -> Unit
        }

        if (load !is SessionLoad.Loading) {
            Row(
                modifier = Modifier.padding(top = ZhilianSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.stackGap),
            ) {
                if (retryable) {
                    Button(onClick = onRetry) { Text(stringResource(R.string.session_retry)) }
                }
                OutlinedButton(onClick = onExit) { Text(stringResource(R.string.back)) }
            }
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

    // 双击回调经 rememberUpdatedState 取最新值，pointerInput 的 key 固定为 Unit：
    // 否则每次重组都会重建手势检测器，两次点按之间一旦重组，第一下就被丢掉（表现为「双击没反应」）
    val doubleTapState = rememberUpdatedState(onDoubleTap)

    ZhilianCard(
        modifier = modifier
            .fillMaxSize()
            .then(
                // 已提交后双击卡片任意位置（含选项）弹出解析；
                // 提交后选项只读、不挂 selectable/toggleable，不消费点击，父级双击覆盖整卡
                if (onDoubleTap != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { doubleTapState.value?.invoke() })
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
                // 结尾卡内边距与题卡一致（同为 lg）：两页同处一个 pager，内边距不同会让翻页时文字左右跳
                .padding(ZhilianSpacing.cardInner),
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
