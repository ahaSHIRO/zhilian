package com.baiyin.zhilian.ui.screens.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.practice.PracticeFilter
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 题量滑块参数（定案：10–100、步长 5、默认 20；调整只改这里） */
private const val LIMIT_MIN = 10
private const val LIMIT_MAX = 100
private const val LIMIT_STEP = 5
private const val LIMIT_DEFAULT = 20

/**
 * 筛选选择的"应用内保留"：切 tab、进会话返回都不丢，跨进程（杀 App 重开）仍重置。
 * 后者是用户明确要的行为（随手刷/专攻错题/挑专题/顺序推四场景都存在，
 * 跨进程记住选择会"帮你回忆现在不想要的东西"），故只到 Saveable 这一层为止。
 */
private val StringSetSaver = Saver<Set<String>, List<String>>(
    save = { it.toList() },
    restore = { it.toSet() },
)

/**
 * 练习配置页：科目 + 分类 + 标签 + 题型 + 范围（全部/错题/收藏）+ 顺序/随机 + 数量。
 * 条件组合按 README 必须全部满足；题量不足时以现有题开练，不重复补足。
 *
 * 分类与标签都挂在科目下：选中科目后，chips 只显示该科目下的取值，
 * 避免 Java 与 Kotlin 的同名分类混在一起（CONTEXT.md 的科目/分类身份规范）。
 * 科目与分类均为空时表示不限，各维度可任意组合；同一维度内多选取并集。
 *
 * 标签再跟随已选分类收窄：标签基数远高于分类（18 道题已有 20+ 个），
 * 全量平铺会把配置页撑成两屏，故先选分类再按标签细筛。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeHomeScreen(
    container: AppContainer,
    onStartPractice: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 用户手选的筛选状态走 Saveable（应用内保留，见 StringSetSaver 注释）；
    // 列表与计数是 DB 派生值，回来时 LaunchedEffect 会重算，不值得存
    var subjects by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedSubjects by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf<Set<String>>(emptySet()) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedCategories by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf<Set<String>>(emptySet()) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTags by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf<Set<String>>(emptySet()) }
    var types by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTypes by rememberSaveable(stateSaver = StringSetSaver) { mutableStateOf<Set<String>>(emptySet()) }
    var onlyWrong by rememberSaveable { mutableStateOf(false) }
    var onlyFavorite by rememberSaveable { mutableStateOf(false) }
    var sequential by rememberSaveable { mutableStateOf(true) }
    var limit by rememberSaveable { mutableStateOf(LIMIT_DEFAULT) }
    // 题量面板：草稿值在滑块里调，确定才写回 limit；面板开着时实时预览草稿值的匹配数
    var showLimitSheet by rememberSaveable { mutableStateOf(false) }
    var limitDraft by rememberSaveable { mutableStateOf(LIMIT_DEFAULT) }
    var draftMatchedCount by remember { mutableStateOf(0) }
    var matchedCount by remember { mutableStateOf(0) }
    var wrongCount by remember { mutableStateOf(0) }
    var favoriteCount by remember { mutableStateOf(0) }
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
        // 科目变化后，已选分类若不在新列表里则清掉，避免出现选中了但看不到的 chip
        selectedCategories = selectedCategories.intersect(categories.toSet())
    }

    // 标签跟随已选分类收窄：全库标签基数远高于分类，不收窄会把配置页撑成两屏。
    // 未选分类时不给出标签，改用一行提示说明它在哪儿出现。
    LaunchedEffect(selectedSubjects, selectedCategories) {
        tags = if (selectedCategories.isEmpty()) emptyList()
        else container.practiceRepository.distinctTags(
            selectedSubjects.singleOrNull(), selectedCategories
        )
        // 分类变窄后，已选标签若不在新列表里则清掉，避免留下看不见也关不掉的暗筛选
        selectedTags = selectedTags.intersect(tags.toSet())
    }

    fun currentFilter() = PracticeFilter(
        subjects = selectedSubjects,
        categories = selectedCategories,
        types = selectedTypes,
        tags = selectedTags,
        onlyWrong = onlyWrong,
        onlyFavorite = onlyFavorite,
        sequential = sequential,
        limit = limit,
    )

    // 实时预览：总数走计数接口（不受题量上限截断）；错题/收藏额外给出只打开该范围的预判数，
    // 让两个范围开关在勾选前就能判断这次值不值得刷。顺序/随机与计数无关故不列入 key。
    LaunchedEffect(selectedSubjects, selectedCategories, selectedTags, selectedTypes, onlyWrong, onlyFavorite, limit) {
        val filter = currentFilter()
        matchedCount = container.practiceRepository.countMatching(filter)
        wrongCount = container.practiceRepository.wrongCount(filter)
        favoriteCount = container.practiceRepository.favoriteCount(filter)
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

        // ---- 标签（跟随已选分类；比分类更细，承担挑专题刷的需求）----
        if (selectedCategories.isEmpty()) {
            Text(
                stringResource(R.string.practice_tag_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (tags.isNotEmpty()) {
            Text(stringResource(R.string.practice_filter_tag), style = MaterialTheme.typography.titleMedium)
            // 用 FlowRow 而非每行固定个数：标签是长短不一的英文词，固定分栏会把长词压成竖排
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    FilterChip(
                        selected = tag in selectedTags,
                        onClick = {
                            selectedTags = if (tag in selectedTags) {
                                selectedTags - tag
                            } else {
                                selectedTags + tag
                            }
                        },
                        label = { Text(tag) },
                    )
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

        // ---- 范围（两个开关取交集：同时打开 = 既答错过又被收藏）----
        Text(stringResource(R.string.practice_filter_scope), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = onlyWrong,
                onClick = { onlyWrong = !onlyWrong },
                label = { Text(stringResource(R.string.practice_scope_wrong_count, wrongCount)) },
            )
            FilterChip(
                selected = onlyFavorite,
                onClick = { onlyFavorite = !onlyFavorite },
                label = { Text(stringResource(R.string.practice_scope_favorite_count, favoriteCount)) },
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

        // ---- 题量（入口行；滑块在半模态面板里，固定 10/20/50 档位数不够用）----
        Text(stringResource(R.string.practice_filter_limit), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = {
                limitDraft = limit
                showLimitSheet = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.practice_limit_value, limit))
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

    // 题量半模态面板：草稿值随滑块走，确定才写回；与解析面板同一交互语言（ADR-0007）
    if (showLimitSheet) {
        LaunchedEffect(limitDraft) {
            draftMatchedCount = container.practiceRepository.countMatching(currentFilter())
        }
        ModalBottomSheet(onDismissRequest = { showLimitSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
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
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.practice_matched_count, draftMatchedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = { limitDraft = LIMIT_DEFAULT }) {
                        Text(stringResource(R.string.practice_limit_reset))
                    }
                    Button(onClick = {
                        limit = limitDraft
                        showLimitSheet = false
                    }) {
                        Text(stringResource(R.string.practice_limit_apply))
                    }
                }
            }
        }
    }
}

/** 科目代码 → 展示名（Schema 里是小写代码） */
private fun subjectLabel(code: String): String = when (code) {
    "kotlin" -> "Kotlin"
    "java" -> "Java"
    "arkts" -> "ArkTS"
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
