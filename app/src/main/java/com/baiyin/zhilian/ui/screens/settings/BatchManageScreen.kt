package com.baiyin.zhilian.ui.screens.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baiyin.zhilian.AppContainer
import com.baiyin.zhilian.R
import com.baiyin.zhilian.data.batch.BatchFileDto
import com.baiyin.zhilian.data.batch.BatchJson
import com.baiyin.zhilian.data.batch.ImportOutcome
import com.baiyin.zhilian.ui.components.ZhilianCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 批次管理：SAF 授权 Syncthing 批次目录 → 扫描待处理批次 → 确认导入 → 结果报告；
 * 疑似重复逐条人工决策（README 导入韧性）。
 */
@Composable
fun BatchManageScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val treeUri by container.settingsRepository.batchTreeUri
        .collectAsStateWithLifecycle(initialValue = null)
    val processedBatches by container.database.processedBatchDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingDuplicates by container.database.pendingDuplicateDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var dirFiles by remember(treeUri) { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var importOutcome by remember { mutableStateOf<ImportOutcome?>(null) }
    var resolving by remember { mutableStateOf<com.baiyin.zhilian.data.db.PendingDuplicateEntity?>(null) }
    var busy by remember { mutableStateOf(false) }

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

    // 扫描批次目录中的 .json 文件
    LaunchedEffect(treeUri) {
        dirFiles = treeUri?.let { uri ->
            withContext(Dispatchers.IO) {
                val dir = DocumentFile.fromTreeUri(context, uri)
                dir?.listFiles()?.filter { it.isFile && it.name?.endsWith(".json") == true }
                    ?.sortedBy { it.name }
            }
        } ?: emptyList()
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            TextButton(onClick = { dirPicker.launch(null) }) {
                Text(stringResource(if (treeUri == null) R.string.batch_pick_dir else R.string.batch_change_dir))
            }
        }

        if (treeUri == null) {
            Text(
                stringResource(R.string.batch_no_dir),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 待决疑似重复
        if (pendingDuplicates.isNotEmpty()) {
            Text(stringResource(R.string.batch_pending_duplicates), style = MaterialTheme.typography.titleMedium)
            pendingDuplicates.forEach { item ->
                ZhilianCard {
                    Column(modifier = Modifier.padding(12.dp)) {
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { resolving = item }) {
                                Text(stringResource(R.string.batch_review_duplicate))
                            }
                        }
                    }
                }
            }
        }

        // 目录中的批次文件
        if (dirFiles.isNotEmpty()) {
            Text(stringResource(R.string.batch_dir_files), style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(dirFiles, key = { it.uri.toString() }) { file ->
                    var preview by remember(file.uri) { mutableStateOf<BatchFileDto?>(null) }
                    LaunchedEffect(file.uri) {
                        preview = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(file.uri)
                                    ?.bufferedReader()?.use { it.readText() }
                                    ?.let { text -> BatchJson.json.decodeFromString<BatchFileDto>(text) }
                            }.getOrNull()
                        }
                    }
                    val processed = preview?.let { p ->
                        processedBatches.any { it.batchId == p.batchId && it.status == "IMPORTED" }
                    } == true
                    ZhilianCard(
                        containerColor = if (processed) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(file.name ?: "?", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    preview?.let {
                                        stringResource(
                                            R.string.batch_preview_line,
                                            it.batchOrder, it.questions.size, it.retiredQuestionIds.size,
                                        )
                                    } ?: stringResource(R.string.batch_preview_fail),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                enabled = !busy && preview != null && !processed,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        importOutcome = container.importService.importFromUri(
                                            file.uri, file.name ?: "batch.json",
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
        }

        HorizontalDivider()

        // 已处理批次
        Text(stringResource(R.string.batch_processed_title), style = MaterialTheme.typography.titleMedium)
        if (processedBatches.isEmpty()) {
            Text(
                stringResource(R.string.batch_processed_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(processedBatches, key = { it.batchId }) { b ->
                ZhilianCard {
                    Column(modifier = Modifier.padding(12.dp)) {
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

    // 导入结果报告
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    val existingStem by container.database.questionDao().let { dao ->
        androidx.compose.runtime.produceState<String?>(initialValue = null, item.existingQuestionId) {
            value = dao.getById(item.existingQuestionId)?.stem?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
        }
    }
    AlertDialog(
        onDismissRequest = onResolved,
        title = { Text(stringResource(R.string.batch_review_duplicate)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
