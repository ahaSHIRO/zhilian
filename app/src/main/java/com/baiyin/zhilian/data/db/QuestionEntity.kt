package com.baiyin.zhilian.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 题目（本机题库）。只追加：导入后不原位修改内容字段；停用置 inactive。
 * 身份与匹配规范见 docs/schema/batch-spec-v1.md：分类/标签 NFC + trim 精确匹配。
 */
@Entity(
    tableName = "questions",
    indices = [
        Index(value = ["category"]),
        Index(value = ["batch_order", "order_in_batch"]),
    ],
)
data class QuestionEntity(
    @PrimaryKey @ColumnInfo(name = "question_id") val questionId: String,
    /** 题型：single_choice / multiple_choice / true_false / fill_in_blank */
    @ColumnInfo(name = "type") val type: String,
    /** 所属科目代码（kotlin / java，取自批次顶层 subject 字段） */
    @ColumnInfo(name = "subject") val subject: String,
    /** 单层分类名，科目内 NFC+trim 精确匹配 */
    @ColumnInfo(name = "category") val category: String,
    /** 标签，JSON 数组字符串 */
    @ColumnInfo(name = "tags_json") val tagsJson: String,
    /** 题干 Markdown */
    @ColumnInfo(name = "stem") val stem: String,
    /** 选项 JSON 数组（选择题），判断/填空为 null */
    @ColumnInfo(name = "options_json") val optionsJson: String?,
    /** 标准答案 JSON：单选=字符串、多选=数组、判断=布尔、填空=可接受答案数组 */
    @ColumnInfo(name = "answer_json") val answerJson: String,
    /** 解析 Markdown */
    @ColumnInfo(name = "explanation") val explanation: String,
    /** 来源引用 JSON：title/url/note/accessedDate */
    @ColumnInfo(name = "source_json") val sourceJson: String,
    /** 来源批次顺序号，顺序练习排序第一键 */
    @ColumnInfo(name = "batch_order") val batchOrder: Int,
    /** 批次文件内顺序，排序第二键 */
    @ColumnInfo(name = "order_in_batch") val orderInBatch: Int,
    /** 停用后不出现在新练习；历史与统计保留（首版不可逆） */
    @ColumnInfo(name = "inactive") val inactive: Boolean = false,
    /** 跨练习累计连续全对次数：错误归零、跳过不变、部分得分不增加 */
    @ColumnInfo(name = "consecutive_perfect") val consecutivePerfect: Int = 0,
    /** 历史上是否有未消解的第一次错误作答（错题推导依据之一） */
    @ColumnInfo(name = "has_ever_wrong") val hasEverWrong: Boolean = false,
    /** 收藏 */
    @ColumnInfo(name = "favorite") val favorite: Boolean = false,
    /** 导入时间（epoch millis） */
    @ColumnInfo(name = "imported_at") val importedAt: Long,
) {
    /** 错题定义（CONTEXT.md）：存在错误作答且未被连续两次全对消解 */
    val isWrong: Boolean get() = hasEverWrong && consecutivePerfect < WRONG_THRESHOLD

    companion object {
        /** 错题消解阈值（CONTEXT.md「连续两次全对消解」）：SQL 与 Kotlin 两份表达共享此值 */
        const val WRONG_THRESHOLD = 2
    }
}
