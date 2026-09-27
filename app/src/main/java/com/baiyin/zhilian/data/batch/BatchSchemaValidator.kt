package com.baiyin.zhilian.data.batch

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.SerializationException

/**
 * 批次 Schema 校验（规格 v1）。Schema 从 assets 读取（构建时由 Copy 任务从 docs/schema 复制）。
 * networknt json-schema-validator 2.x：SchemaRegistry + Schema.validate 返回 List<Error>。
 */
class BatchSchemaValidator(context: Context) {

    private val objectMapper = ObjectMapper()
    private val schema: Schema = SchemaRegistry
        .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(
            context.assets.open("batch-v1.schema.json")
                .bufferedReader().use { it.readText() }
        )

    data class SchemaIssue(val path: String, val message: String)

    /**
     * 校验 JSON 文本是否符合批次 v1。
     * @return null 表示通过；否则返回错误列表（已译为中文表述）。
     */
    fun validate(jsonText: String): List<SchemaIssue>? {
        val node: JsonNode = try {
            objectMapper.readTree(jsonText)
        } catch (e: Exception) {
            return listOf(SchemaIssue("$", "不是合法的 JSON：${e.message ?: "解析失败"}"))
        }
        val errors = schema.validate(node)
        if (errors.isEmpty()) return null
        return errors.map { m ->
            SchemaIssue(
                // 取真实实例路径（如 $.questions[2].answer），不再写死 "$" 丢失字段定位
                path = m.instanceLocation.toString(),
                message = translate(m.message),
            )
        }
    }

    /** 常见违规的中文转述；未匹配的保留原文兜底。 */
    private fun translate(raw: String): String = when {
        raw.contains("const") -> "formatVersion 必须为 1"
        raw.contains("required") -> "缺少必填字段：${raw.substringAfterLast(':').trim()}"
        raw.contains("additionalProperties") -> raw.replace("additionalProperties", "出现未知字段")
        raw.contains("enum") && raw.contains("type") -> "未知题型"
        raw.contains("enum") -> "取值不在允许范围内（如科目/题型枚举）"
        raw.contains("format") && raw.contains("date") -> "日期格式应为 YYYY-MM-DD"
        raw.contains("format") && raw.contains("uri") -> "链接格式不合法"
        raw.contains("minItems") || raw.contains("maxItems") -> "数组长度超出允许范围"
        raw.contains("minLength") || raw.contains("maxLength") -> "文本长度超出允许范围"
        raw.contains("pattern") -> "格式不匹配（ID/选项标识/空白约束）"
        raw.contains("type") -> "字段类型不正确：$raw"
        else -> raw
    }

    companion object {
        /** DTO 解析兜底异常转述（Schema 通过后仍可能因序列化注解失败） */
        fun describeParseError(e: SerializationException): String = "批次结构解析失败：${e.message}"
    }
}
