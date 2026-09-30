package com.baiyin.zhilian.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.batch.BatchFileDto
import com.baiyin.zhilian.data.batch.BatchJson
import com.baiyin.zhilian.data.batch.ImportOutcome
import com.baiyin.zhilian.ui.components.ZhilianCard
import com.baiyin.zhilian.ui.theme.ZhilianSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 目录扫描结果：文件 + 预解析 DTO（解析中或非法批次为 null，由 [parsing] 区分） */
private data class ScannedBatch(val file: DocumentFile, val dto: BatchFileDto?)

/** 按 batchOrder 倒序（解析失败的沉底），再按文件名倒序 */
private val BATCH_ORDER: Comparator<ScannedBatch> =
    compareByDescending<ScannedBatch> { it.dto?.batchOrder ?: Int.MIN_VALUE }
        .thenByDescending { it.file.name ?: "" }

/**
 * 批次管理：SAF 授权 Syncthing 批次目录 → 自动/手动扫描待处理批次 → 单个或全部导入 →
 * 结果报告；疑似重复逐条人工决策（README 导入韧性）。
 * 目录文件与已处理批次均按 batchOrder 倒序（最新批次在最上）。
 */
@Composable
fun BatchManageScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val treeUri by container.settingsRepository.batchTreeUri
        .collectAsStateWithLifecycle(initialValue = null)
    val processedBatches by container.importService.observeProcessedBatches()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingDuplicates by container.importService.observePendingDuplicates()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var batches by remember { mutableStateOf<List<ScannedBatch>>(emptyList()) }
    var importOutcome by remember { mutableStateOf<ImportOutcome?>(null) }
    var resolving by remember { mutableStateOf<com.baiyin.zhilian.data.db.PendingDuplicateEntity?>(null) }
    var busy by remember { mutableStateOf(false) }
    var importingAll by remember { mutableStateOf(false) }
    var allOutcomes by remember { mutableStateOf<List<Pair<String, ImportOutcome>>?>(null) }

    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 持久化授权：重启后仍可读取批次目录
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            scope.launch { container.settingsRepository.setBatchTreeUri(uri) }
        }
    }

    // 阶段 1：只列目录（SAF listFiles），拿到文件名就返回——不读文件内容
    suspend fun listBatchFiles(uri: Uri): List<DocumentFile> = withContext(Dispatchers.IO) {
        DocumentFile.fromTreeUri(context, uri)
            ?.listFiles()
            ?.filter { it.isFile && it.name?.endsWith(".json") == true }
            ?.sortedByDescending { it.name ?: "" }
            ?: emptyList()
    }

    // 阶段 2：并行读+解析每个文件的 DTO，再按 batchOrder 倒序；SAF openInputStream 各自独立可并行
    suspend fun parseBatches(files: List<DocumentFile>): List<ScannedBatch> = coroutineScope {
        files.map { f ->
            async(Dispatchers.IO) {
                val dto = runCatching {
                    context.contentResolver.openInputStream(f.uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?.let { text -> BatchJson.json.decodeFromString<BatchFileDto>(text) }
                }.getOrNull()
                ScannedBatch(f, dto)
            }
        }.map { it.await() }.sortedWith(BATCH_ORDER)
    }

    // initialized：首次目录扫描是否完成。未完成前只渲染操作行+转圈，
    // 不渲染目录/已处理等任何列表 section——否则 Room 的已处理记录先到、SAF 目录
    // 后到时，目录区会在转场途中「插入顶部」造成几帧跳动（闪烁根因）。
    // parsing：文件名骨架已上屏、DTO 并行解析中（仅影响卡片副标题文案）。
    var initialized by remember { mutableStateOf(false) }
    var parsing by remember { mutableStateOf(false) }

    suspend fun refresh(uri: Uri) {
        val files = listBatchFiles(uri)
        // 文件名骨架先上屏（DTO 全 null，按文件名降序，与 batchOrder 前缀一致不会跳动）；
        // 骨架就绪即解除首帧门控，完整列表一次性出现。
        batches = files.map { ScannedBatch(it, null) }
        initialized = true
        parsing = true
        batches = parseBatches(files)
        parsing = false
    }

    // 每次回到页面（含选完目录返回、Syncthing 同步后切回）自动重扫。
    // 两阶段上屏：listFiles 返回后立刻用文件名渲染骨架（消除「整页空白等加载」），
    // DTO 并行解析完再补顺序号/题数与排序。已初始化后的重扫静默进行（布局不跳）。
    DisposableEffect(lifecycleOwner, treeUri) {
        val uri = treeUri
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && uri != null) {
                scope.launch { refresh(uri) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (uri != null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            scope.launch { refresh(uri) }
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun isImported(sb: ScannedBatch): Boolean {
        val p = sb.dto ?: return false
        return processedBatches.any { it.batchId == p.batchId && it.status == "IMPORTED" }
    }

    // 实底背景：二级页侧滑时页面作为一整张「纸」移动，稀疏卡片之间不再透出下层页面
    // （background 即雾层 base 同色，视觉与全局雾蓝一致；见 ADR-0005/ADR-0009）
    //
    // 二级页无底栏（ADR-0010 后外壳只避让状态栏），故此处自行避让手势条：
    // 不给底部 inset 的话，最后一项会压到小白条下。
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = ZhilianSpacing.screenEdge,
            top = ZhilianSpacing.screenEdge,
            end = ZhilianSpacing.screenEdge,
            bottom = ZhilianSpacing.screenEdge + navBarPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.stackGap),
    ) {
        // 顶部操作行
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                TextButton(onClick = { dirPicker.launch(null) }) {
                    Text(stringResource(if (treeUri == null) R.string.batch_pick_dir else R.string.batch_change_dir))
                }
            }
        }

        if (treeUri == null) {
            item {
                Text(
                    stringResource(R.string.batch_no_dir),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 首次扫描未完成：只给一个转圈，其余 section 一律不渲染（避免内容分批到达导致跳动）
        if (treeUri != null && !initialized) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = ZhilianSpacing.xl * 2),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(ZhilianSpacing.xl * 2))
                }
            }
        }

        if (initialized) {
        // 待决疑似重复
        if (pendingDuplicates.isNotEmpty()) {
            item {
                Text(stringResource(R.string.batch_pending_duplicates), style = MaterialTheme.typography.titleMedium)
            }
            items(pendingDuplicates, key = { "${it.batchId}/${it.questionId}" }) { item ->
                ZhilianCard {
                    Column(modifier = Modifier.padding(ZhilianSpacing.cardInnerCompact)) {
                        Text(
                            stringResource(R.string.batch_duplicate_with, item.existingQuestionId.take(8)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            remember(item.questionJson) { stemPreview(item.questionJson) },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm)) {
                            TextButton(onClick = { resolving = item }) {
                                Text(stringResource(R.string.batch_review_duplicate))
                            }
                        }
                    }
                }
            }
        }

        // 目录中的批次文件
        if (batches.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.batch_dir_files), style = MaterialTheme.typography.titleMedium)
                    FilledTonalButton(
                        enabled = !busy && !importingAll,
                        onClick = {
                            scope.launch {
                                importingAll = true
                                val results = mutableListOf<Pair<String, ImportOutcome>>()
                                batches.forEach { sb ->
                                    if (sb.dto != null && !isImported(sb)) {
                                        val outcome = container.importService.importFromUri(
                                            sb.file.uri, sb.file.name ?: "batch.json",
                                        )
                                        results += (sb.file.name ?: "batch.json") to outcome
                                    }
                                }
                                importingAll = false
                                allOutcomes = results
                            }
                        },
                    ) {
                        Text(stringResource(
                            if (importingAll) R.string.batch_import_all_busy else R.string.batch_import_all,
                        ))
                    }
                }
            }
            items(batches, key = { it.file.uri.toString() }) { sb ->
                val preview = sb.dto
                val processed = isImported(sb)
                ZhilianCard(
                    containerColor = if (processed) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(ZhilianSpacing.cardInnerCompact),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(sb.file.name ?: "?", style = MaterialTheme.typography.bodyMedium)
                            // DTO 解析中显示「加载中」，解析完仍为 null 才是「无法解析」
                            val subText = when {
                                preview != null -> stringResource(
                                    R.string.batch_preview_line,
                                    preview.batchOrder, preview.questions.size, preview.retiredQuestionIds.size,
                                )
                                parsing -> stringResource(R.string.batch_preview_loading)
                                else -> stringResource(R.string.batch_preview_fail)
                            }
                            Text(
                                subText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            enabled = !busy && !importingAll && preview != null && !processed,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    importOutcome = container.importService.importFromUri(
                                        sb.file.uri, sb.file.name ?: "batch.json",
                                    )
                                    busy = false
                                }
                            },
                        ) {
                            Text(stringResource(R.string.batch_import_action))
                        }
                    }
                }
            }
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = ZhilianSpacing.xs)) }

        // 已处理批次（DAO 已按 batch_order DESC 返回）
        item {
            Text(stringResource(R.string.batch_processed_title), style = MaterialTheme.typography.titleMedium)
        }
        if (processedBatches.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.batch_processed_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(processedBatches, key = { it.batchId }) { b ->
            ZhilianCard {
                Column(modifier = Modifier.padding(ZhilianSpacing.cardInnerCompact)) {
                    Text(
                        stringResource(R.string.batch_processed_line, b.batchOrder, statusLabel(b.status)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(
                            R.string.batch_processed_counts,
                            b.importedCount, b.skippedCount, b.failedCount, b.pendingDuplicateCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        } // if (initialized)
    }

    // 单批导入结果报告
    importOutcome?.let { outcome ->
        AlertDialog(
            onDismissRequest = { importOutcome = null },
            confirmButton = {
                TextButton(onClick = { importOutcome = null }) { Text(stringResource(R.string.ok)) }
            },
            title = {
                Text(
                    when (outcome) {
                        is ImportOutcome.Failed -> stringResource(R.string.batch_result_failed)
                        is ImportOutcome.Completed -> stringResource(R.string.batch_result_done)
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm)) {
                    when (outcome) {
                        is ImportOutcome.Failed -> {
                            Text(outcome.reason)
                        }
                        is ImportOutcome.Completed -> {
                            Text(
                                stringResource(
                                    R.string.batch_result_counts,
                                    outcome.importedCount, outcome.skippedCount,
                                    outcome.failedCount, outcome.retiredCount,
                                )
                            )
                            if (outcome.duplicates.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.batch_result_duplicates, outcome.duplicates.size),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            outcome.issues.take(8).forEach { issue ->
                                Text(
                                    "· ${issue.questionId.take(8)}: ${issue.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (outcome.issues.size > 8) {
                                Text(
                                    stringResource(R.string.batch_result_more_issues, outcome.issues.size - 8),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (outcome.warnings.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.batch_result_warnings, outcome.warnings.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                outcome.warnings.forEach { warning ->
                                    Text(
                                        "· ${warning.questionId.take(8)}: ${warning.reason}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    // 全部导入汇总报告
    allOutcomes?.let { outcomes ->
        AlertDialog(
            onDismissRequest = { allOutcomes = null },
            confirmButton = {
                TextButton(onClick = { allOutcomes = null }) { Text(stringResource(R.string.ok)) }
            },
            title = { Text(stringResource(R.string.batch_all_result_title)) },
            text = {
                if (outcomes.isEmpty()) {
                    Text(stringResource(R.string.batch_import_all_none))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm)) {
                        outcomes.forEach { (name, outcome) ->
                            val summary = when (outcome) {
                                is ImportOutcome.Completed ->
                                    stringResource(
                                        R.string.batch_result_counts,
                                        outcome.importedCount, outcome.skippedCount,
                                        outcome.failedCount, outcome.retiredCount,
                                    ) + if (outcome.duplicates.isNotEmpty())
                                        " " + stringResource(R.string.batch_result_duplicates, outcome.duplicates.size)
                                    else ""
                                is ImportOutcome.Failed -> outcome.reason.lineSequence().firstOrNull() ?: "失败"
                            }
                            Text(
                                stringResource(R.string.batch_all_result_line, name, summary),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
        )
    }

    // 疑似重复决策对话框
    resolving?.let { item ->
        DuplicateResolveDialog(
            item = item,
            container = container,
            onResolved = { resolving = null },
        )
    }
}

@Composable
private fun DuplicateResolveDialog(
    item: com.baiyin.zhilian.data.db.PendingDuplicateEntity,
    container: AppContainer,
    onResolved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val candidateStem = remember(item.questionJson) { stemPreview(item.questionJson) }
    val existingStem by androidx.compose.runtime.produceState<String?>(initialValue = null, item.existingQuestionId) {
        value = container.questionBank.get(listOf(item.existingQuestionId))
            .firstOrNull()?.stem?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
    }
    AlertDialog(
        onDismissRequest = onResolved,
        title = { Text(stringResource(R.string.batch_review_duplicate)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm)) {
                Text(
                    stringResource(R.string.batch_duplicate_new),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(candidateStem, maxLines = 4, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.batch_duplicate_existing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(existingStem ?: "…", maxLines = 4, style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    container.importService.resolveDuplicate(item.batchId, item.questionId, import = true)
                    onResolved()
                }
            }) { Text(stringResource(R.string.batch_duplicate_import)) }
        },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    container.importService.resolveDuplicate(item.batchId, item.questionId, import = false)
                    onResolved()
                }
            }) { Text(stringResource(R.string.batch_duplicate_skip)) }
        },
    )
}

/** 从待决项 JSON 提取题干首行预览 */
private fun stemPreview(questionJson: String): String = runCatching {
    BatchJson.json.decodeFromString<QuestionDtoPreview>(questionJson).stem
}.getOrNull()?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "…"

@kotlinx.serialization.Serializable
private data class QuestionDtoPreview(val stem: String)

@Composable
private fun statusLabel(status: String): String = when (status) {
    "IMPORTED" -> stringResource(R.string.batch_status_imported)
    "PARTIAL" -> stringResource(R.string.batch_status_partial)
    else -> stringResource(R.string.batch_status_failed)
}
