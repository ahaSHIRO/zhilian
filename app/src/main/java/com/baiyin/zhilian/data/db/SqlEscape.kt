package com.baiyin.zhilian.data.db

/**
 * SQL 字符串常量转义（ADR-0015）：值来自本机题库 DISTINCT，常量级注入面。
 *
 * 单值转义（[sqlEscape]）供等值条件（`subject = '...'`），IN 列表（[sqlEscapeAll]）供集合条件
 * （`category IN ('a','b')`）——两者共用同一份转义规则，消除 `QuestionBank` 与 `PracticeSql.quote`
 * 各写一遍的重复。
 */
internal fun sqlEscape(value: String): String = "'" + value.replace("'", "''") + "'"

/** IN 列表转义：`'a','it''s'` */
internal fun sqlEscapeAll(values: Collection<String>): String =
    values.joinToString(",") { sqlEscape(it) }
