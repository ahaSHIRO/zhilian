package com.baiyin.zhilian.ui.screens.settings

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.Read
import com.baiyin.zhilian.data.batch.BatchDirectoryScan
import com.baiyin.zhilian.data.batch.BatchFingerprint
import com.baiyin.zhilian.data.batch.BatchJson
import com.baiyin.zhilian.data.batch.ImportOutcome
import com.baiyin.zhilian.data.batch.ScannedBatch
import com.baiyin.zhilian.data.batch.ScanState
import com.baiyin.zhilian.data.batch.listBatchFiles
import com.baiyin.zhilian.data.batch.parseBatch
import com.baiyin.zhilian.data.batch.readBatchText
import com.baiyin.zhilian.data.db.PendingDuplicateEntity
import com.baiyin.zhilian.data.db.ProcessedBatchEntity
import com.baiyin.zhilian.data.valueOrNull
import com.baiyin.zhilian.ui.components.ZhilianCard
import com.baiyin.zhilian.ui.theme.ZhilianSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 批次管理：SAF 授权 Syncthing 批次目录 → 目录级**题库对账**（ADR-0017）→ 结果摘要；
 * 疑似重复逐条人工决策；已处理批次可**撤销导入**。保留单个「导入」作为新批次的兜底。
 *
 * 对账在 App 每次回前台时自动跑（[com.baiyin.zhilian.data.batch.BatchReconciler]）；
 * 本页的「立即对账」与它共用同一入口，摘要只一份。逐文件状态由「当前文件指纹 vs
 * 已处理记录指纹」判定：一致=已对账、不一致=待对账、被拒=待处理。
 *
 * 扫描的规则与竞态全在 [BatchDirectoryScan]；SAF 读盘在 [listBatchFiles] / [parseBatch] /
 * [readBatchText]。首帧不渲染 Room 数据 section，避免「快数据先占位、慢数据后插入」的跳动
 * （pitfalls 2.13）。
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
    val lastReconcileRead by container.settingsRepository.lastReconcile
        .collectAsStateWithLifecycle(initialValue = Read.Pending)
    val lastReconcile = lastReconcileRead.valueOrNull

    var importOutcome by remember { mutableStateOf<ImportOutcome?>(null) }
    var resolving by remember { mutableStateOf<PendingDuplicateEntity?>(null) }
    var undoTarget by remember { mutableStateOf<ProcessedBatchEntity?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reconciling by remember { mutableStateOf(false) }

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

    /** 该文件当前对账状态：已对账 / 待对账 / 待处理 / 解析失败 / 未对账 */
    fun fileState(sb: ScannedBatch): FileState {
        val dto = sb.dto ?: return FileState.ParseFailed
        lastReconcile?.blocked?.firstOrNull { it.fileName == sb.file.name }?.let {
            return FileState.Blocked(it.reason)
        }
        val record = processedBatches.firstOrNull { it.batchId == dto.batchId }
            ?: return FileState.New
        return if (record.contentHash == BatchFingerprint.of(dto)) FileState.Reconciled
        else FileState.Stale
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

        // 对账摘要（Q8）：有变更或待处理才出现，平时不弹
        lastReconcile?.let { digest ->
            if (digest.hasChanges) {
                item {
                    ZhilianCard {
                        Column(modifier = Modifier.padding(ZhilianSpacing.cardInnerCompact)) {
                            Text(
                                stringResource(R.string.batch_reconcile_summary_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(
                                    R.string.batch_reconcile_summary_line,
                                    digest.changedBatches, digest.inserted, digest.updated,
                                    digest.retired, digest.pendingDuplicates,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (digest.blocked.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.batch_reconcile_blocked_title, digest.blocked.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                digest.blocked.forEach { b ->
                                    Text(
                                        stringResource(R.string.batch_reconcile_blocked_line, b.fileName, b.reason),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm)) {
                                TextButton(onClick = {
                                    scope.launch { container.settingsRepository.clearLastReconcile() }
                                }) { Text(stringResource(R.string.batch_reconcile_dismiss)) }
                            }
                        }
                    }
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
                        enabled = !busy && !reconciling && batches.any { it.dto != null },
                        onClick = {
                            scope.launch {
                                reconciling = true
                                container.reconciler.reconcileNow()
                                reconciling = false
                            }
                        },
                    ) {
                        Text(stringResource(
                            if (reconciling) R.string.batch_reconcile_busy else R.string.batch_reconcile_action,
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
                val state = fileState(sb)
                ZhilianCard(
                    containerColor = if (state is FileState.Reconciled) MaterialTheme.colorScheme.surface
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
                                ) + " · " + stateLabel(state)
                            } else {
                                stringResource(R.string.batch_preview_fail)
                            }
                            Text(
                                subText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state is FileState.Blocked) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 兜底导入只对新批次开放：已对账/待对账由自动对账处理，避免「导入却不更新」的误导
                        if (state is FileState.New) {
                            Button(
                                enabled = !busy && !reconciling,
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
            }

            // 孤儿批次（ADR-0018）：已处理但文件已不在目录。仅在扫描完成且有孤儿时出现，
            // 平时不占篇幅；撤销对这些批次才真正生效——文件已离场，不会被下次自动对账重导抵消。
            // 文件还在目录的已处理批次不在此处、也不挂撤销（手机端不越权推翻电脑端权威）。
            // 双重匹配（batchId 或 fileName 任一命中即非孤儿）：避免「文件在目录但解析失败」
            // （dto.batchId 为 null）被误判为孤儿。
            val orphanBatches = processedBatches.filter { pb ->
                batches.none { it.dto?.batchId == pb.batchId || it.file.name == pb.fileName }
            }
            if (scanState is ScanState.Ready && orphanBatches.isNotEmpty()) {
                item { HorizontalDivider(modifier = Modifier.padding(vertical = ZhilianSpacing.xs)) }
                item {
                    Text(stringResource(R.string.batch_orphan_title), style = MaterialTheme.typography.titleMedium)
                }
                items(orphanBatches, key = { it.batchId }) { b ->
                    ZhilianCard {
                        Column(modifier = Modifier.padding(ZhilianSpacing.cardInnerCompact)) {
                            Text(b.fileName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(
                                    R.string.batch_orphan_line,
                                    remember(b.processedAt) {
                                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                            .format(Date(b.processedAt))
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(ZhilianSpacing.sm)) {
                                TextButton(onClick = { undoTarget = b }) {
                                    Text(
                                        stringResource(R.string.batch_undo_action),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
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

    // 撤销批次导入（Q6）：删该批题目 + 作答记录 + 待决项 + 已处理记录，不可恢复
    undoTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { undoTarget = null },
            title = { Text(stringResource(R.string.batch_undo_confirm_title)) },
            text = {
                Text(stringResource(R.string.batch_undo_confirm_message, target.batchOrder))
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.reconcileService.undoBatch(target.batchId)
                        undoTarget = null
                    }
                }) {
                    Text(stringResource(R.string.batch_undo_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { undoTarget = null }) { Text(stringResource(R.string.batch_undo_cancel)) }
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

/** 目录中单个批次的对账状态 */
private sealed interface FileState {
    /** 解析失败（不是合法批次） */
    data object ParseFailed : FileState

    /** 被拒：Schema/解析失败、批内 ID 重复、顺序号冲突（不应用） */
    data class Blocked(val reason: String) : FileState

    /** 尚无已处理记录：从未对过账 */
    data object New : FileState

    /** 指纹与记录一致：已对账 */
    data object Reconciled : FileState

    /** 指纹与记录不一致：内容变了，待对账 */
    data object Stale : FileState
}

@Composable
private fun stateLabel(state: FileState): String = when (state) {
    FileState.ParseFailed -> stringResource(R.string.batch_preview_fail)
    is FileState.Blocked -> stringResource(R.string.batch_status_blocked)
    FileState.New -> stringResource(R.string.batch_status_new)
    FileState.Reconciled -> stringResource(R.string.batch_status_reconciled)
    FileState.Stale -> stringResource(R.string.batch_status_stale)
}

@Composable
private fun DuplicateResolveDialog(
    item: PendingDuplicateEntity,
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