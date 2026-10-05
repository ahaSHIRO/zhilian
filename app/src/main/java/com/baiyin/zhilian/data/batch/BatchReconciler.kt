package com.baiyin.zhilian.data.batch

import android.content.Context
import com.baiyin.zhilian.data.Read
import com.baiyin.zhilian.data.SettingsRepository
import com.baiyin.zhilian.data.valueOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 前台自动对账的薄壳（ADR-0017 / Q2）：读目录 → 读文本 → 交给 [BatchReconcileService]，
 * 并把结果摘要落盘（Q8：有变更时批次管理页留一条）。
 *
 * **自动与手动两条触发路径的唯一入口**，两处都走这里——摘要因此只有一份，
 * 且 [Mutex] 保证前台触发与批次页手动触发不会同时跑两遍对账。
 */
class BatchReconciler(
    private val context: Context,
    private val settings: SettingsRepository,
    private val service: BatchReconcileService,
) {
    private val mutex = Mutex()

    /**
     * 立即对一次账，并持久化摘要。
     * @return 未授权目录时返回 null（无事可做）；否则返回本次摘要
     */
    suspend fun reconcileNow(): ReconcileDigest? = mutex.withLock {
        // 注意别用 first()：settings 流经 asRead() 包装，**首个发射是 Read.Pending**
        // （读盘异步），first() 会拿到 Pending → valueOrNull 恒为 null，对账永远空转。
        val read = settings.batchTreeUri.first { it !is Read.Pending }
        val uri = read.valueOrNull ?: return@withLock null
        val digest = service.reconcile(readBatchSources(context, uri))
        settings.setLastReconcile(digest)
        digest
    }
}