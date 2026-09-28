package com.baiyin.zhilian.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 疑似重复待决项（README：不同 ID 但内容疑似相同的题目，等待人工选择导入或跳过）。
 * 候选题原文以 JSON 保存，决定导入时再解析入库。
 */
@Entity(tableName = "pending_duplicates", primaryKeys = ["batch_id", "question_id"])
data class PendingDuplicateEntity(
    @ColumnInfo(name = "batch_id") val batchId: String,
    @ColumnInfo(name = "question_id") val questionId: String,
    @ColumnInfo(name = "question_json") val questionJson: String,
    /** 科目：待决项需能脱离批次顶层字段独立恢复成题目，故随项保存 */
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "order_in_batch") val orderInBatch: Int,
    @ColumnInfo(name = "existing_question_id") val existingQuestionId: String,
)
