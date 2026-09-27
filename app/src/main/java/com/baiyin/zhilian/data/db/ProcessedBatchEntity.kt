package com.baiyin.zhilian.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 已处理批次（README 导入韧性）：记录批次处理状态防止重复处理；
 * 部分成功仍记录，修正原批次后重试只补失败题。
 * 同一 batchId 可有多条记录（多次处理），以累进结果为准。
 */
@Entity(tableName = "processed_batches")
data class ProcessedBatchEntity(
    @PrimaryKey @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "batch_order") val batchOrder: Int,
    /** 处理状态：IMPORTED（完成）/ PARTIAL（部分成功，可重试）/ FAILED（整体失败，如格式版本不兼容） */
    @ColumnInfo(name = "status") val status: String,
    /** 本次处理成功/跳过/失败的题目数 */
    @ColumnInfo(name = "imported_count") val importedCount: Int,
    @ColumnInfo(name = "skipped_count") val skippedCount: Int,
    @ColumnInfo(name = "failed_count") val failedCount: Int,
    /** 疑似重复待决数 */
    @ColumnInfo(name = "pending_duplicate_count") val pendingDuplicateCount: Int,
    /** 失败与跳过原因摘要（JSON 数组：[{questionId, reason}]），全部成功为 null */
    @ColumnInfo(name = "issues_json") val issuesJson: String?,
    /** 处理时间（epoch millis） */
    @ColumnInfo(name = "processed_at") val processedAt: Long,
    /** 来源文件名（仅报告展示用） */
    @ColumnInfo(name = "file_name") val fileName: String,
)
