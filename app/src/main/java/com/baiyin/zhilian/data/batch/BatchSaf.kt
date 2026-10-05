package com.baiyin.zhilian.data.batch

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF 读盘的薄壳（ADR-0013：设备相关读取留调用方/壳，Service 只收文本）。
 *
 * 批次管理页与前台自动对账（[BatchReconciler]）共用这几段，避免两处各写一份
 * 列目录/读文件逻辑而漂移。
 */

/** 阶段 1：只列目录（SAF listFiles），拿到文件名就返回——不读文件内容 */
internal suspend fun listBatchFiles(context: Context, uri: Uri): List<BatchFileRef> =
    withContext(Dispatchers.IO) {
        DocumentFile.fromTreeUri(context, uri)
            ?.listFiles()
            ?.filter { it.isFile && it.name?.endsWith(".json") == true }
            ?.sortedByDescending { it.name ?: "" }
            ?.map { BatchFileRef(it.uri.toString(), it.name ?: "?") }
            ?: emptyList()
    }

/** 阶段 2：读 + 解析单个文件（SAF openInputStream 各自独立，故可并行） */
internal suspend fun parseBatch(context: Context, ref: BatchFileRef): BatchFileDto? =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(ref.key))
            ?.bufferedReader()?.use { it.readText() }
            ?.let { text -> BatchJson.json.decodeFromString<BatchFileDto>(text) }
    }

/** 读批次文件文本（SAF）：导入/对账用 */
internal suspend fun readBatchText(context: Context, uri: Uri): String? =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }

/** 目录级：列出全部批次文件并读文本（对账用；Schema 校验与解析交给 Service） */
internal suspend fun readBatchSources(context: Context, uri: Uri): List<BatchSource> =
    listBatchFiles(context, uri).map { ref ->
        BatchSource(ref.name, readBatchText(context, Uri.parse(ref.key)))
    }