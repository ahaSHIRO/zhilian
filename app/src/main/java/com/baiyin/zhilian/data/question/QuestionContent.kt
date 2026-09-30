package com.baiyin.zhilian.data.question

import com.baiyin.zhilian.data.batch.BatchJson
import com.baiyin.zhilian.data.batch.OptionDto
import com.baiyin.zhilian.data.batch.SourceDto
import java.text.Normalizer

/**
 * 题目内容的类型化解码（CONTEXT.md「题目」）。纯函数，无 Android 依赖。
 *
 * 判分、练习卡渲染、题库答案预览与批次导入原先各自按题型解码 `answerJson`，
 * 四种题型的分派写了三遍，身份归一化（NFC + trim）同样散在三处。收口于此：
 * 一处分派、四处共用，规则不会再一分支一分支地漂移。
 *
 * 解析一律失败兜底为空（同 [com.baiyin.zhilian.data.practice.QuestionTags] 的取舍：
 * 宁可少命中，不让脏数据带崩界面）。
 */
object QuestionContent {

    /** 身份归一化：NFC + trim（batch-spec-v1.md §Unicode 与大小写）。分类/标签/题干/填空匹配同规则 */
    fun normalizeIdentity(s: String): String =
        Normalizer.normalize(s.trim(), Normalizer.Form.NFC)

    /** 选项列表（选择题）；无选项或解析失败为空 */
    fun options(optionsJson: String?): List<OptionDto> =
        optionsJson?.let {
            runCatching { BatchJson.json.decodeFromString<List<OptionDto>>(it) }.getOrNull()
        } ?: emptyList()

    /** 来源引用；解析失败为 null */
    fun source(sourceJson: String): SourceDto? =
        runCatching { BatchJson.json.decodeFromString<SourceDto>(sourceJson) }.getOrNull()

    /** 单选标准答案；缺失或解析失败为 null */
    fun singleAnswer(answerJson: String): String? =
        runCatching { BatchJson.json.decodeFromString<String>(answerJson) }.getOrNull()

    /** 多选标准答案 */
    fun multipleAnswers(answerJson: String): List<String> =
        runCatching { BatchJson.json.decodeFromString<List<String>>(answerJson) }.getOrDefault(emptyList())

    /** 判断题标准答案；解析失败按 false */
    fun trueFalseAnswer(answerJson: String): Boolean =
        runCatching { BatchJson.json.decodeFromString<Boolean>(answerJson) }.getOrDefault(false)

    /** 填空可接受答案（同多选的数组表示，但语义不同：可接受书写变体） */
    fun blankAcceptables(answerJson: String): List<String> = multipleAnswers(answerJson)

    /**
     * 该题的正确选项 ID 集合：单选 1 个、多选一组，判断与填空无选项故为空。
     * 练习卡揭示正解与题库答案预览共用同一判定。
     */
    fun correctOptionIds(type: String, answerJson: String): Set<String> = when (type) {
        "single_choice" -> setOfNotNull(singleAnswer(answerJson))
        "multiple_choice" -> multipleAnswers(answerJson).toSet()
        else -> emptySet()
    }
}
