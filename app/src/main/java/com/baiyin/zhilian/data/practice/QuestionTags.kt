package com.baiyin.zhilian.data.practice

import com.baiyin.zhilian.data.batch.BatchJson
import kotlinx.serialization.decodeFromString

/**
 * 标签解析与命中判定（纯函数，无数据库依赖，便于 JVM 单测）。
 *
 * 标签以 JSON 数组字符串存在 `questions.tags_json` 一列里，因此"含某标签"
 * 这件事无法用一条可靠的 SQL 条件表达：LIKE 拼串会误命中——JSON 会把标签内的
 * 引号转义成 `\"`，于是搜 `"b"` 会命中 `["a\"b"]`，把一道根本没有 b 标签的题
 * 选进来。故解析后做集合判定，而不是在 SQL 里 LIKE。
 */
object QuestionTags {

    /** 解析标签数组；解析失败视为该行无标签（宁可少命中，不让脏数据带崩练习页） */
    fun decode(tagsJson: String): List<String> = try {
        BatchJson.json.decodeFromString<List<String>>(tagsJson)
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * 命中所选任一标签即可（OR）——与科目/分类/题型的 IN (...) 语义保持一致：
     * 选三个标签表示三者的并集，不是交集。
     */
    fun matches(tagsJson: String, selected: Set<String>): Boolean {
        if (selected.isEmpty()) return true
        return decode(tagsJson).any { it in selected }
    }

    /** 取一批标签 JSON 里的全部不重复标签（练习配置页 chips 用） */
    fun distinct(tagsJsonList: List<String>): List<String> =
        tagsJsonList.flatMap { decode(it) }.distinct().sorted()
}
