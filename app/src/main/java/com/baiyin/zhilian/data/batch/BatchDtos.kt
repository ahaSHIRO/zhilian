package com.baiyin.zhilian.data.batch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** 批次 JSON 模型，字段与 docs/schema/batch-v1.schema.json 一一对应。 */
@Serializable
data class BatchFileDto(
    val formatVersion: Int,
    val batchId: String,
    val batchOrder: Int,
    val createdAt: String,
    val subject: String,
    val questions: List<QuestionDto> = emptyList(),
    val retiredQuestionIds: List<String> = emptyList(),
)

@Serializable
data class QuestionDto(
    val questionId: String,
    val type: String,
    val category: String,
    val tags: List<String> = emptyList(),
    val stem: String,
    val options: List<OptionDto>? = null,
    /** 原始答案节点：单选=字符串、多选=字符串数组、判断=布尔（按 type 解释） */
    val answer: JsonElement? = null,
    val acceptableAnswers: List<String>? = null,
    val explanation: String,
    val source: SourceDto,
)

@Serializable
data class OptionDto(
    val optionId: String,
    val text: String,
)

@Serializable
data class SourceDto(
    val title: String,
    val url: String? = null,
    val note: String? = null,
    val accessedDate: String,
)

/** 批次 JSON 解析器：结构合法性由 Schema 校验保证，这里只做映射。 */
object BatchJson {
    val json: Json = Json {
        ignoreUnknownKeys = true // 未知键以 Schema additionalProperties=false 为准
        encodeDefaults = true
    }
}
