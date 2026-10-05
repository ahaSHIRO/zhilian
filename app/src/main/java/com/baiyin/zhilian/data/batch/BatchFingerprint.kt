package com.baiyin.zhilian.data.batch

import java.security.MessageDigest

/**
 * 批次内容指纹（CONTEXT.md）：批次中**影响本机题库**的内容的稳定摘要。
 *
 * 参与计算：`batchOrder`、`subject`、逐题全部内容字段、`retiredQuestionIds`。
 * 刻意排除 `createdAt` 与 `formatVersion`——它们变化不代表题库内容变化，纳入会让
 * 「只是重新导出一次」被误判为「内容变了」，每次前台都白跑一遍对账。
 *
 * 纯函数（只依赖 JDK），可在 JVM 单测里直接钉死稳定性与敏感性。
 */
object BatchFingerprint {

    fun of(batch: BatchFileDto): String {
        val sb = StringBuilder()
        sb.append(batch.batchOrder).append(SEP_FIELD).append(batch.subject).append(SEP_FIELD)
        batch.questions.forEach { q ->
            sb.append(q.questionId).append(SEP_FIELD)
                .append(q.type).append(SEP_FIELD)
                .append(q.category).append(SEP_FIELD)
                .append(q.tags.joinToString(SEP_LIST)).append(SEP_FIELD)
                .append(q.stem).append(SEP_FIELD)
                .append(q.options?.joinToString(SEP_LIST) { "${it.optionId}=${it.text}" } ?: "").append(SEP_FIELD)
                .append(q.answer?.toString() ?: "").append(SEP_FIELD)
                .append(q.acceptableAnswers?.joinToString(SEP_LIST) ?: "").append(SEP_FIELD)
                .append(q.explanation).append(SEP_FIELD)
                .append(q.source.title).append(SEP_FIELD)
                .append(q.source.url ?: "").append(SEP_FIELD)
                .append(q.source.note ?: "").append(SEP_FIELD)
                .append(q.source.accessedDate).append(SEP_RECORD)
        }
        sb.append(batch.retiredQuestionIds.joinToString(SEP_LIST))
        return sha256(sb.toString())
    }

    // 分隔符用不可打印控制字符，避免与正文里任何可见字符撞车
    private const val SEP_FIELD = "\u0001"
    private const val SEP_LIST = "\u0002"
    private const val SEP_RECORD = "\u0003"

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}