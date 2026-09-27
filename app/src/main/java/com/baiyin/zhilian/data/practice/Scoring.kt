package com.baiyin.zhilian.data.practice

import com.baiyin.zhilian.data.batch.BatchJson
import com.baiyin.zhilian.data.db.AnswerRecordEntity
import com.baiyin.zhilian.data.db.QuestionEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** 用户作答（会话内存态） */
sealed class UserAnswer {
    data class Single(val optionId: String) : UserAnswer()
    data class Multiple(val optionIds: Set<String>) : UserAnswer()
    data class TrueFalse(val value: Boolean) : UserAnswer()
    data class Blank(val text: String) : UserAnswer()
}

/** 练习判定（README 评分规则）。返回 null 表示尚未作答。 */
object Scoring {

    /** 填空匹配：去首尾空白 + 区分大小写精确比对（README） */
    fun isBlankMatch(input: String, acceptable: List<String>): Boolean =
        acceptable.any { it == input.trim() }

    /**
     * 判定得分。多选：max(0, 正确选中数 − 错误选中数) / 正确选项总数，满分才算答对。
     * @return scoreRate ∈ [0,1] 与是否满分
     */
    fun score(question: QuestionEntity, answer: UserAnswer): Pair<Double, Boolean> {
        return when (answer) {
            is UserAnswer.Single -> {
                val correct = answer.optionId == JsonPrimitiveAnswer.single(question.answerJson)
                if (correct) 1.0 to true else 0.0 to false
            }
            is UserAnswer.Multiple -> {
                val correctSet = JsonPrimitiveAnswer.multiple(question.answerJson).toSet()
                val hits = answer.optionIds.count { it in correctSet }
                val misses = answer.optionIds.count { it !in correctSet }
                val rate = if (correctSet.isEmpty()) 0.0 else (hits - misses).coerceAtLeast(0) / correctSet.size.toDouble()
                val perfect = answer.optionIds == correctSet
                rate to perfect
            }
            is UserAnswer.TrueFalse -> {
                val correct = answer.value == JsonPrimitiveAnswer.boolean(question.answerJson)
                if (correct) 1.0 to true else 0.0 to false
            }
            is UserAnswer.Blank -> {
                val acceptable = JsonPrimitiveAnswer.blankAcceptable(question.answerJson)
                val correct = isBlankMatch(answer.text, acceptable)
                if (correct) 1.0 to true else 0.0 to false
            }
        }
    }

    /** (rate, perfect) → AnswerOutcome；跳过不产生记录、不进转移，故不经此 */
    fun outcomeOf(rate: Double, perfect: Boolean): AnswerOutcome = when {
        perfect -> AnswerOutcome.Perfect
        rate <= 0.0 -> AnswerOutcome.Wrong
        else -> AnswerOutcome.Partial // 0 < rate < 1，部分得分仍算未对
    }

    /** 构造作答记录实体（isFirst 由调用方查询后传入） */
    fun toRecord(
        question: QuestionEntity,
        answer: UserAnswer,
        scoreRate: Double,
        isPerfect: Boolean,
        isFirstAttempt: Boolean,
        answeredAt: Long,
    ): AnswerRecordEntity = AnswerRecordEntity(
        questionId = question.questionId,
        type = question.type,
        userAnswerJson = encodeUserAnswer(answer),
        scoreRate = scoreRate,
        isPerfect = isPerfect,
        isFirstAttempt = isFirstAttempt,
        answeredAt = answeredAt,
    )

    fun encodeUserAnswer(answer: UserAnswer): String = when (answer) {
        is UserAnswer.Single -> BatchJson.json.encodeToString(answer.optionId)
        is UserAnswer.Multiple -> BatchJson.json.encodeToString(answer.optionIds.sorted())
        is UserAnswer.TrueFalse -> BatchJson.json.encodeToString(answer.value)
        is UserAnswer.Blank -> BatchJson.json.encodeToString(answer.text)
    }

    /** answerJson 的题型化读取工具 */
    private object JsonPrimitiveAnswer {
        private val json = BatchJson.json

        fun single(answerJson: String): String = json.decodeFromString<String>(answerJson)

        fun multiple(answerJson: String): List<String> = json.decodeFromString<List<String>>(answerJson)

        fun boolean(answerJson: String): Boolean = json.decodeFromString<Boolean>(answerJson)

        fun blankAcceptable(answerJson: String): List<String> = json.decodeFromString<List<String>>(answerJson)
    }
}
