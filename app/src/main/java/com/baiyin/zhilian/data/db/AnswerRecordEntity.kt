package com.baiyin.zhilian.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 作答记录（CONTEXT.md）：提交答案后形成，跳过不产生记录。
 * 首版只追加，清除全部练习记录时整体删除。
 */
@Entity(
    tableName = "answer_records",
    indices = [Index(value = ["question_id"]), Index(value = ["answered_at"])],
)
data class AnswerRecordEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "question_id") val questionId: String,
    /** 题型冗余（统计按题型聚合用），取值同 QuestionEntity.type */
    @ColumnInfo(name = "type") val type: String,
    /** 用户作答 JSON：单选=字符串、多选=数组、判断=布尔、填空=字符串 */
    @ColumnInfo(name = "user_answer_json") val userAnswerJson: String,
    /** 得分率 0.0–1.0：多选按 max(0, 对-错)/正确总数，其余全对 1.0 或 0.0 */
    @ColumnInfo(name = "score_rate") val scoreRate: Double,
    /** 是否满分（多选部分分=false） */
    @ColumnInfo(name = "is_perfect") val isPerfect: Boolean,
    /** 是否该题首次作答（写入前查询判定） */
    @ColumnInfo(name = "is_first_attempt") val isFirstAttempt: Boolean,
    /** 作答时间（epoch millis） */
    @ColumnInfo(name = "answered_at") val answeredAt: Long,
)
