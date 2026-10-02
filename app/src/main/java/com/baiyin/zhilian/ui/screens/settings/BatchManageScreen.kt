package com.baiyin.zhilian.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.baiyin.zhilian.data.Read
import com.baiyin.zhilian.data.batch.BatchDirectoryScan
import com.baiyin.zhilian.data.batch.BatchFileDto
import com.baiyin.zhilian.data.batch.BatchFileRef
import com.baiyin.zhilian.data.batch.BatchJson
import com.baiyin.zhilian.data.batch.ImportOutcome
import com.baiyin.zhilian.data.batch.ScannedBatch
import com.baiyin.zhilian.data.batch.ScanState
import com.baiyin.zhilian.data.valueOrNull
import com.baiyin.zhilian.ui.components.ZhilianCard
import com.baiyin.zhilian.ui.theme.ZhilianSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 阶段 1：只列目录（SAF listFiles），拿到文件名就返回——不读文件内容 */
private suspend fun listBatchFiles(context: Context, uri: Uri): List<BatchFileRef> =
    withContext(Dispatchers.IO) {
        DocumentFile.fromTreeUri(context, uri)
            ?.listFiles()
            ?.filter { it.isFile && it.name?.endsWith(".json") == true }
            ?.sortedByDescending { it.name ?: "" }
            ?.map { BatchFileRef(it.uri.toString(), it.name ?: "?") }
            ?: emptyList()
    }

/** 阶段 2：读 + 解析单个文件（SAF openInputStream 各自独立，故可并行） */
private suspend fun parseBatch(context: Context, ref: BatchFileRef): BatchFileDto? =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(ref.key))
            ?.bufferedReader()?.use { it.readText() }
            ?.let { text -> BatchJson.json.decodeFromString<BatchFileDto>(text) }
    }

/** 读批次文件文本（SAF）：导入用（ADR-0013：SAF 读取留调用方，Service 只收文本） */
private suspend fun readBatchText(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }

/**
 * 批次管理：SAF 授权 Syncthing 批次目录 → 自动/手动扫描待处理批次 → 单个或全部导入 →
 * 结果报告；疑似重复逐条人工决策（README 导入韧性）。
 * 目录文件与已处理批次均按 batchOrder 倒序（最新批次在最上）。
 *
 * 扫描的规则与竞态全在 [BatchDirectoryScan]：本页只把 SAF 读盘与导入动作接上去，
 * 并按 [ScanState] 渲染——首帧不渲染任何列表 section（含 Room 的已处理批次），
 * 避免「快数据先占位、慢数据后插入」的跳动（pitfalls 2.13）。
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
    val treeUriRead by container.settingsRepository.batchTreeUri
        .collectAsStateWithLifecycle(initialValue = Read.Pending)
    val treeUri = treeUriRead.valueOrNull
    val processedBatches by container.importService.observeProcessedBatches()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingDuplicates by container.importService.observePendingDuplicates()
        .collectAsStateWithLifecycle(initialValue = emptyList())

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

    // 换目录即换数据源：重建扫描器（从 Idle 重新走一次首扫，含骨架），旧扫描取消
    val scan = remember(treeUri) {
        treeUri?.let { uri ->
            BatchDirectoryScan(
                listFiles = { listBatchFiles(context, uri) },
                parse = { ref -> parseBatch(context, ref) },
                scope = scope,
            )
        }
    }
    DisposableEffect(scan) {
        onDispose { scan?.reset() }
    }

    val scanState: ScanState = if (scan != null) {
        val current by scan.state.collectAsStateWithLifecycle(initialValue = ScanState.Idle)
        // 回到页面即重扫（ADR-0008）。注册观察者时 Lifecycle 会把新观察者推到当前状态并派发
        // ON_RESUME——换目录后重建的扫描器正是靠这条启动，故**不再**另写「若已 RESUMED 则再扫
        // 一次」的分支：那会与补发的事件叠加，让首次进入并发扫描两遍。
        DisposableEffect(lifecycleOwner, scan) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) scan.start()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        current
    } else {
        ScanState.Idle
    }

    val skeleton = (scanState as? ScanState.FirstScan)?.files.orEmpty()
    val batches = (scanState as? ScanState.Ready)?.batches.orEmpty()

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
        // 顶部操作行（静态框架，任何状态下都在）
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                TextButton(onClick = { dirPicker.launch(null) }) {
                    Text(stringResource(if (treeUri == null) R.string.batch_pick_dir else R.string.batch_change_dir))
                }
            }
        }

        // 设置读到、且确实没授权过，才说「尚未授权」——Pending 期间什么都不说
        if (treeUriRead is Read.Value && treeUri == null) {
            item {
                Text(
                    stringResource(R.string.batch_no_dir),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 目录不可读（授权被撤 / 目录被删）：与「尚未授权」分开——这个要用户重新选择
        if (scanState is ScanState.Unreadable) {
            item {
                Text(
                    stringResource(R.string.batch_dir_unreadable, scanState.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // 首帧门控：扫描产出之前不渲染任何 section——Room 的已处理记录到得快，
        // 若让它先占位，扫描结果一到就会把整块内容顶下去（pitfalls 2.13）
        val scanned = scanState is ScanState.FirstScan || scanState is ScanState.Ready

        if (scanned) {
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

            // 目录中的批次文件：表头常驻，首次扫描期间先上文件名骨架
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.batch_dir_files), style = MaterialTheme.typography.titleMedium)
                    FilledTonalButton(
                        enabled = !busy && !importingAll && batches.any { it.dto != null && !isImported(it) },
                        onClick = {
                            scope.launch {
                                importingAll = true
                                val results = mutableListOf<Pair<String, ImportOutcome>>()
                                batches.forEach { sb ->
                                    if (sb.dto != null && !isImported(sb)) {
                                        val text = readBatchText(context, Uri.parse(sb.file.key))
                                        val outcome = if (text != null) {
                                            container.importService.importFromText(text, sb.file.name)
                                        } else {
                                            ImportOutcome.Failed(null, sb.file.name, "无法读取文件")
                                        }
                                        results += sb.file.name to outcome
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

            // 骨架：文件名已就绪、DTO 仍在并行解析；此时不给可用的导入按钮
            items(skeleton, key = { it.key }) { ref ->
                ZhilianCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(ZhilianSpacing.cardInnerCompact),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ref.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.batch_preview_loading),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(enabled = false, onClick = {}) {
                            Text(stringResource(R.string.batch_import_action))
                        }
                    }
                }
            }

            items(batches, key = { it.file.key }) { sb ->
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
                            Text(sb.file.name, style = MaterialTheme.typography.bodyMedium)
                            // 解析完仍为 null 才是「无法解析」
                            val subText = if (preview != null) {
                                stringResource(
                                    R.string.batch_preview_line,
                                    preview.batchOrder, preview.questions.size, preview.retiredQuestionIds.size,
                                )
                            } else {
                                stringResource(R.string.batch_preview_fail)
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
                                    val text = readBatchText(context, Uri.parse(sb.file.key))
                                    importOutcome = if (text != null) {
                                        container.importService.importFromText(text, sb.file.name)
                                    } else {
                                        ImportOutcome.Failed(null, sb.file.name, "无法读取文件")
                                    }
                                    busy = false
                                }
                            },
                        ) {
                            Text(stringResource(R.string.batch_import_action))
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
        }
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
                                    ) + if (outcome.duplicates.isNotEmpty()) {
                                        " " + stringResource(R.string.batch_result_duplicates, outcome.duplicates.size)
                                    } else {
                                        ""
                                    }
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
    val existingStem by produceState<String?>(initialValue = null, item.existingQuestionId) {
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
                // 首帧门控：题干未取回前不渲染占位符号（原先会先闪一个「…」）
                existingStem?.let { stem ->
                    Text(stem, maxLines = 4, style = MaterialTheme.typography.bodyMedium)
                }
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
