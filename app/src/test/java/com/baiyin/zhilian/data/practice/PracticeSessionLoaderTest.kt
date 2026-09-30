package com.baiyin.zhilian.data.practice

import com.baiyin.zhilian.data.db.QuestionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话载入器单测：四态映射、**按请求顺序返回**、停用题过滤与 droppedCount。
 *
 * 依赖只有「按 ID 取题」一件，用一段内存实现即可问「规则对不对」，不必插设备。
 * 顺序那几条锁的是真实退化：题库查询按批次序返回，而随机练习的顺序编码在 questionIds 里，
 * 载入时不重排就会把「随机」静默变成「顺序」。
 */
class PracticeSessionLoaderTest {

    private fun question(id: String, inactive: Boolean = false) = QuestionEntity(
        questionId = id,
        type = "single_choice",
        subject = "kotlin",
        category = "协程",
        tagsJson = "[]",
        stem = "题干 $id",
        optionsJson = null,
        answerJson = "\"A\"",
        explanation = "解析",
        sourceJson = "{}",
        batchOrder = 1,
        orderInBatch = 1,
        inactive = inactive,
        importedAt = 0L,
    )

    /** 内存题库：按传入顺序返回命中的题（刻意不排序，交由 loader 承担顺序契约） */
    private fun bankOf(vararg stored: QuestionEntity) =
        PracticeSessionLoader { ids -> stored.filter { it.questionId in ids } }

    @Test
    fun `没有题目 ID 是参数缺失，不是空结果`() = runBlocking {
        val loader = PracticeSessionLoader { error("参数缺失时不应查询题库") }

        assertEquals(
            SessionLoad.Failed(SessionLoad.FailureReason.MissingArgs),
            loader.load(emptyList()),
        )
    }

    @Test
    fun `全部可用时给出可练习题`() = runBlocking {
        val loader = bankOf(question("q1"), question("q2"))

        val load = loader.load(listOf("q1", "q2"))

        assertEquals(listOf("q1", "q2"), (load as SessionLoad.Ready).questions.map { it.questionId })
        assertEquals(0, load.droppedCount)
    }

    @Test
    fun `结果按请求顺序返回，而不是题库的返回顺序`() = runBlocking {
        // 题库按批次序返回 q1、q2；请求是随机打乱后的 q2、q1
        val loader = bankOf(question("q1"), question("q2"))

        val load = loader.load(listOf("q2", "q1")) as SessionLoad.Ready

        assertEquals(
            "随机练习的顺序编码在请求里，载入不重排就退化成顺序",
            listOf("q2", "q1"),
            load.questions.map { it.questionId },
        )
    }

    @Test
    fun `停用题被过滤并计入 droppedCount`() = runBlocking {
        val loader = bankOf(question("q1"), question("q2", inactive = true), question("q3"))

        val load = loader.load(listOf("q1", "q2", "q3")) as SessionLoad.Ready

        assertEquals(listOf("q1", "q3"), load.questions.map { it.questionId })
        assertEquals(1, load.droppedCount)
    }

    @Test
    fun `不在库的题目同样计入 droppedCount`() = runBlocking {
        val loader = bankOf(question("q1"))

        val load = loader.load(listOf("q1", "missing")) as SessionLoad.Ready

        assertEquals(listOf("q1"), load.questions.map { it.questionId })
        assertEquals(1, load.droppedCount)
    }

    @Test
    fun `一道都取不到是可练习题目为空，不是失败`() = runBlocking {
        val loader = bankOf(question("q1", inactive = true))

        assertEquals(
            SessionLoad.Empty(SessionLoad.EmptyReason.NoPracticableQuestions),
            loader.load(listOf("q1")),
        )
    }

    @Test
    fun `读库失败是可重试的失败，不是崩溃也不是空态`() = runBlocking {
        val loader = PracticeSessionLoader { throw IllegalStateException("库坏了") }

        assertEquals(
            SessionLoad.Failed(SessionLoad.FailureReason.LoadFailed),
            loader.load(listOf("q1")),
        )
    }

    @Test
    fun `载入只做一次查询`() = runBlocking {
        var queries = 0
        val loader = PracticeSessionLoader { ids -> queries++; ids.map { question(it) } }

        loader.load(listOf("q1", "q2"))

        assertEquals(1, queries)
    }
}
