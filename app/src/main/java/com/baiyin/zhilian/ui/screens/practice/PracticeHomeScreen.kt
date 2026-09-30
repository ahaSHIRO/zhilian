package com.baiyin.zhilian.ui.screens.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.practice.PracticeSelection
import com.baiyin.zhilian.ui.components.Loadable
import com.baiyin.zhilian.ui.components.ZhilianCard
import com.baiyin.zhilian.ui.components.BottomBarTrailingSpacer
import com.baiyin.zhilian.ui.theme.ZhilianSpacing
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 题量滑块参数（定案：10–100、步长 5、默认 20；调整只改这里） */
private const val LIMIT_MIN = 10
private const val LIMIT_MAX = 100
private const val LIMIT_STEP = 5

/**
 * 筛选选择的"应用内保留"：切 tab、进会话返回都不丢，跨进程（杀 App 重开）仍重置。
 * 后者是用户明确要的行为（随手刷/专攻错题/挑专题/顺序推四场景都存在，
 * 跨进程记住选择会"帮你回忆现在不想要的东西"），故只到 Saveable 这一层为止。
 *
 * 整个选择作为一个整体存取：级联收窄会同时改动多层（科目变→分类变→标签变），
 * 拆成多个 Saveable 反而容易存下互相矛盾的中途态。
 */
private const val SAVE_SEP = "\u0001"

/** 存档字段数：字段增减后旧存档会按下标越界，故恢复前先校验长度（回落到默认值） */
private const val SELECTION_FIELDS = 8

private val PracticeSelectionSaver = listSaver<PracticeSelection, String>(
    save = {
        listOf(
            it.subjects.joinToString(SAVE_SEP),
            it.categories.joinToString(SAVE_SEP),
            it.tags.joinToString(SAVE_SEP),
            it.types.joinToString(SAVE_SEP),
            it.onlyWrong.toString(),
            it.onlyFavorite.toString(),
            it.sequential.toString(),
            it.limit.toString(),
        )
    },
    restore = { parts ->
        if (parts.size != SELECTION_FIELDS) {
            null // 长度不符 = 旧版本存档：回默认值，而不是崩在恢复里
        } else {
            fun setAt(index: Int): Set<String> =
                parts[index].split(SAVE_SEP).filter { it.isNotEmpty() }.toSet()
            PracticeSelection(
                subjects = setAt(0),
                categories = setAt(1),
                tags = setAt(2),
                types = setAt(3),
                onlyWrong = parts[4].toBoolean(),
                onlyFavorite = parts[5].toBoolean(),
                sequential = parts[6].toBoolean(),
                limit = parts[7].toIntOrNull() ?: PracticeSelection.DEFAULT_LIMIT,
            )
        }
    },
)

/** 多选 chip 的开关语义：已选则去掉，未选则加入 */
private fun Set<String>.toggled(value: String): Set<String> =
    if (value in this) this - value else this + value

/**
 * 练习配置页：筛选条件卡（科目 + 分类 + 标签 + 题型 + 范围 + 匹配数尾行）
 * + 会话设置卡（顺序/随机 + 选项打乱开关 + 题量 + 开始练习），两卡语义分组。
 * 条件组合按 README 必须全部满足；题量不足时以现有题开练，不重复补足。
 *
 * 选择态与级联规则见 [PracticeSelection]：分类挂在科目下、标签挂在分类下，
 * 上层变化后下层收窄——规则在纯模块里，本页只负责把事件转发过去并渲染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeHomeScreen(
    container: AppContainer,
    onStartPractice: (List<String>, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selection by rememberSaveable(stateSaver = PracticeSelectionSaver) {
        mutableStateOf(PracticeSelection())
    }
    // 选项打乱与题量草稿是 UI 瞬时态，不属于「练什么」的筛选条件
    var shuffleOptions by rememberSaveable { mutableStateOf(true) }
    var showLimitSheet by rememberSaveable { mutableStateOf(false) }
    var limitDraft by rememberSaveable { mutableStateOf(PracticeSelection.DEFAULT_LIMIT) }

    // 科目与题型是首屏门控（C3）：取回前不渲染任何 chips、计数与终态文案——
    // 原先首帧必然出现「题库为空」+「符合条件的题目：0 道」，随后内容分四批长出来。
    // 分类/标签/计数随选择变化重算，属于「已就绪后的更新」，不参与门控。
    var catalog by remember { mutableStateOf<Loadable<Pair<List<String>, List<String>>>>(Loadable.FirstLoad) }

    // 可选值列表与计数都是 DB 派生值，回来时由 LaunchedEffect 重算，不值得存
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var matchedCount by remember { mutableStateOf(0) }
    var wrongCount by remember { mutableStateOf(0) }
    var favoriteCount by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // 科目与题型列表只随题库变化，取一次即可
    LaunchedEffect(Unit) {
        catalog = Loadable.Data(
            container.questionPicker.subjects() to container.questionPicker.types(),
        )
    }

    val catalogData = (catalog as? Loadable.Data)?.value

    /** 首帧门控：未就绪时整张筛选卡只渲染静态框架（标题与分组名） */
    val ready = catalogData != null
    val subjects = catalogData?.first.orEmpty()
    val types = catalogData?.second.orEmpty()

    // 分类跟随所选科目：单选科目时显示该科目的分类；未选或多选时显示全部（多选场景下并集更实用）。
    // 科目变化后收窄已有选择，避免出现选中了但看不到的 chip。
    LaunchedEffect(selection.subjects) {
        val available = container.questionPicker.categories(selection.onlySubject)
        categories = available
        selection = selection.withCategories(available)
    }

    // 标签跟随已选分类收窄：未选分类时不给出标签，改用一行提示说明它在哪儿出现。
    LaunchedEffect(selection.subjects, selection.categories) {
        val fetched = if (selection.categories.isEmpty()) {
            emptyList()
        } else {
            container.questionPicker.tags(selection.onlySubject, selection.categories)
        }
        tags = fetched
        // 分类变窄后，已选标签若不在新列表里则清掉，避免留下看不见也关不掉的暗筛选
        selection = selection.withTags(fetched)
    }

    // 实时预览：总数走计数接口（不受题量上限截断）；错题/收藏额外给出只打开该范围的预判数，
    // 让两个范围开关在勾选前就能判断这次值不值得刷。顺序/随机与计数无关故不参与。
    LaunchedEffect(selection) {
        val filter = selection.toFilter()
        matchedCount = container.questionPicker.countMatching(filter)
        wrongCount = container.questionPicker.wrongCount(filter)
        favoriteCount = container.questionPicker.favoriteCount(filter)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ZhilianSpacing.screenEdge),
        verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.lg),
    ) {
        // ---- 筛选条件卡：决定这次练什么（科目/分类/标签/题型/范围）----
        ZhilianCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(ZhilianSpacing.cardInner),
                verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.lg),
            ) {
                Text(stringResource(R.string.practice_card_filters), style = MaterialTheme.typography.titleMedium)

                // ---- 科目 ----
                if (ready && subjects.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.practice_filter_subject))
                    Row(horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm)) {
                        subjects.forEach { subject ->
                            FilterChip(
                                selected = subject in selection.subjects,
                                onClick = {
                                    selection = selection.copy(subjects = selection.subjects.toggled(subject))
                                },
                                label = { Text(subjectLabel(subject)) },
                            )
                        }
                    }
                }

                // ---- 分类（跟随科目）----
                SectionLabel(stringResource(R.string.practice_filter_category))
                if (!ready) {
                    // 首帧占位：此处若渲染空态文案，会被随后到达的 chips 顶掉（pitfalls 2.13）
                } else if (categories.isEmpty()) {
                    Text(
                        stringResource(R.string.practice_empty_bank),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 单层分类 chips，多选
                    categories.chunked(3).forEach { rowCategories ->
                        Row(horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm)) {
                            rowCategories.forEach { category ->
                                FilterChip(
                                    selected = category in selection.categories,
                                    onClick = {
                                        selection = selection.copy(
                                            categories = selection.categories.toggled(category),
                                        )
                                    },
                                    label = { Text(category) },
                                )
                            }
                        }
                    }
                }

                // ---- 标签（跟随已选分类；比分类更细，承担挑专题刷的需求）----
                if (!ready) {
                    // 首帧占位：不渲染提示文案，避免与随后到达的标签 chips 交替闪现
                } else if (selection.categories.isEmpty()) {
                    Text(
                        stringResource(R.string.practice_tag_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (tags.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.practice_filter_tag))
                    // 用 FlowRow 而非每行固定个数：标签是长短不一的英文词，固定分栏会把长词压成竖排
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm)) {
                        tags.forEach { tag ->
                            FilterChip(
                                selected = tag in selection.tags,
                                onClick = {
                                    selection = selection.copy(tags = selection.tags.toggled(tag))
                                },
                                label = { Text(tag) },
                            )
                        }
                    }
                }

                // ---- 题型 ----
                if (ready && types.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.practice_filter_type))
                    types.chunked(4).forEach { rowTypes ->
                        Row(horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm)) {
                            rowTypes.forEach { type ->
                                FilterChip(
                                    selected = type in selection.types,
                                    onClick = {
                                        selection = selection.copy(types = selection.types.toggled(type))
                                    },
                                    label = { Text(typeLabel(type)) },
                                )
                            }
                        }
                    }
                }

                // ---- 范围（两个开关取交集：同时打开 = 既答错过又被收藏）----
                SectionLabel(stringResource(R.string.practice_filter_scope))
                if (ready) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm)) {
                        FilterChip(
                            selected = selection.onlyWrong,
                            onClick = { selection = selection.copy(onlyWrong = !selection.onlyWrong) },
                            label = { Text(stringResource(R.string.practice_scope_wrong_count, wrongCount)) },
                        )
                        FilterChip(
                            selected = selection.onlyFavorite,
                            onClick = { selection = selection.copy(onlyFavorite = !selection.onlyFavorite) },
                            label = { Text(stringResource(R.string.practice_scope_favorite_count, favoriteCount)) },
                        )
                    }
                }

                // 卡尾：筛选结果预览（计数接口不受题量上限截断）；首帧不给假计数
                if (ready) {
                    Text(
                        stringResource(R.string.practice_matched_count, matchedCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- 会话设置卡：决定怎么练（顺序/题量）----
        ZhilianCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(ZhilianSpacing.cardInner),
                verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.lg),
            ) {
                Text(stringResource(R.string.practice_card_session), style = MaterialTheme.typography.titleMedium)

                // ---- 顺序 ----
                SectionLabel(stringResource(R.string.practice_filter_order))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selection.sequential,
                        onClick = { selection = selection.copy(sequential = true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.practice_order_sequential)) }
                    SegmentedButton(
                        selected = !selection.sequential,
                        onClick = { selection = selection.copy(sequential = false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.practice_order_random)) }
                }

                // ---- 选项打乱：单选/多选的选项行序按题稳定打乱，防背位置 ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.practice_shuffle_options),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = shuffleOptions,
                        onCheckedChange = { shuffleOptions = it },
                    )
                }

                // ---- 题量（入口行；滑块在半模态面板里，固定 10/20/50 档位数不够用）----
                SectionLabel(stringResource(R.string.practice_filter_limit))
                OutlinedButton(
                    onClick = {
                        limitDraft = selection.limit
                        showLimitSheet = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.practice_limit_value, selection.limit))
                }

                Button(
                    onClick = {
                        scope.launch {
                            val questions = container.questionPicker.pick(selection.toFilter())
                            if (questions.isNotEmpty()) {
                                onStartPractice(questions.map { it.questionId }, shuffleOptions)
                            }
                        }
                    },
                    enabled = ready && matchedCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.practice_start))
                }
            }
        }

        // 底栏是浮层、内容穿到它背后（ADR-0010）：尾部为底栏预留空间，最后一张卡不被遮
        BottomBarTrailingSpacer()
    }

    // 题量半模态面板：草稿值随滑块走，确定才写回；与解析面板同一交互语言（ADR-0007）
    if (showLimitSheet) {
        // 面板里的匹配数与卡片尾行是同一个表达式的同一个值，故直接复用 matchedCount：
        // 原先前者由 `LaunchedEffect(limitDraft)` 另查一次库——key 是滑块草稿、查询参数却与它无关，
        // 拖一次滑块要打几十次结果恒定的查询，而且算出来的值永远等于后者
        ModalBottomSheet(onDismissRequest = { showLimitSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZhilianSpacing.xl)
                    .padding(bottom = ZhilianSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.practice_limit_value, limitDraft),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Slider(
                    value = limitDraft.toFloat(),
                    onValueChange = { limitDraft = it.roundToInt() },
                    valueRange = LIMIT_MIN.toFloat()..LIMIT_MAX.toFloat(),
                    steps = (LIMIT_MAX - LIMIT_MIN) / LIMIT_STEP - 1,
                    modifier = Modifier.fillMaxWidth().padding(top = ZhilianSpacing.sm),
                )
                Text(
                    stringResource(R.string.practice_matched_count, matchedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = ZhilianSpacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm, Alignment.End),
                ) {
                    TextButton(onClick = { limitDraft = PracticeSelection.DEFAULT_LIMIT }) {
                        Text(stringResource(R.string.practice_limit_reset))
                    }
                    Button(onClick = {
                        selection = selection.copy(limit = limitDraft)
                        showLimitSheet = false
                    }) {
                        Text(stringResource(R.string.practice_limit_apply))
                    }
                }
            }
        }
    }
}

/** 卡内分组小标题： subordinate 于卡题（titleMedium），弱化为 labelLarge + 次级色 */
@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 科目代码 → 展示名（Schema 里是小写代码） */
private fun subjectLabel(code: String): String = when (code) {
    "kotlin" -> "Kotlin"
    "java" -> "Java"
    "arkts" -> "ArkTS"
    "interview" -> "面试"
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
