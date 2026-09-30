package com.baiyin.zhilian.ui.screens.practice

import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.practice.PracticeSessionLoader
import com.baiyin.zhilian.data.practice.SessionArgs
import com.baiyin.zhilian.data.practice.SessionLoad
import com.baiyin.zhilian.data.practice.SubmitSummary
import com.baiyin.zhilian.data.practice.UserAnswer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话状态容器单测：进程令牌判定、载入幂等、以 questionId 为键的作答与提交闸门。
 *
 * 容器本身不持协程作用域（异步由界面驱动），所以这里用 `runBlocking` 直接问行为，
 * 不必先给 JVM 装一个 Main 调度器。
 */
class PracticeSessionStateTest {

    private companion object {
        const val TOKEN = "process-token"
    }

    private fun question(id: String) = QuestionEntity(
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
        importedAt = 0L,
    )

    private fun loaderOf(vararg questions: QuestionEntity) =
        PracticeSessionLoader { ids -> questions.filter { it.questionId in ids } }

    private fun newState(
        args: SessionArgs? = SessionArgs(listOf("q1", "q2"), shuffleOptions = true, processToken = TOKEN),
        processToken: String = TOKEN,
        loader: PracticeSessionLoader = loaderOf(question("q1"), question("q2")),
        submit: suspend (QuestionEntity, UserAnswer) -> SubmitSummary = { _, _ -> SubmitSummary(1.0, true) },
    ) = PracticeSessionState(
        loader = loader,
        submitAnswer = submit,
        args = args,
        processToken = processToken,
    )

    @Test
    fun `没有会话参数时判定为参数缺失，且不发起载入`() = runBlocking {
        var queried = false
        val state = newState(
            args = null,
            loader = PracticeSessionLoader { queried = true; emptyList() },
        )

        assertEquals(SessionLoad.Failed(SessionLoad.FailureReason.MissingArgs), state.load)
        state.load()
        assertEquals(
            "参数缺失是终态，重跑载入不得把它变回转圈",
            SessionLoad.Failed(SessionLoad.FailureReason.MissingArgs),
            state.load,
        )
        assertFalse("参数缺失不该查询题库", queried)
    }

    @Test
    fun `参数里的题目 ID 为空同样是参数缺失`() {
        val state = newState(args = SessionArgs(emptyList(), shuffleOptions = true, processToken = TOKEN))

        assertEquals(SessionLoad.Failed(SessionLoad.FailureReason.MissingArgs), state.load)
    }

    @Test
    fun `令牌不符判定为上一个进程留下的会话，且不发起载入`() = runBlocking {
        var queried = false
        val state = newState(
            processToken = "另一个进程的令牌",
            loader = PracticeSessionLoader { queried = true; emptyList() },
        )

        assertEquals(SessionLoad.Failed(SessionLoad.FailureReason.StaleSession), state.load)
        state.load()
        assertFalse("作废的会话不该再去读库", queried)
    }

    @Test
    fun `令牌相符则正常载入可练习题`() = runBlocking {
        val state = newState()

        assertEquals(SessionLoad.Loading, state.load)
        state.load()

        val ready = state.load as SessionLoad.Ready
        assertEquals(listOf("q1", "q2"), ready.questions.map { it.questionId })
    }

    @Test
    fun `载入幂等：配置变更后重跑不会把已就绪的会话退回转圈`() = runBlocking {
        var queries = 0
        val state = newState(
            loader = PracticeSessionLoader { ids -> queries++; ids.map { question(it) } },
        )

        state.load()
        assertTrue(state.load is SessionLoad.Ready)

        // 配置变更重建后 LaunchedEffect 会重跑一次
        state.load()

        assertEquals("已就绪的会话不该重新查询", 1, queries)
        assertTrue(state.load is SessionLoad.Ready)
    }

    @Test
    fun `作答以 questionId 为键，不同题互不覆盖`() {
        val state = newState()

        state.answer("q1", UserAnswer.Single("A"))
        state.answer("q2", UserAnswer.Single("B"))

        assertEquals(UserAnswer.Single("A"), state.answers["q1"])
        assertEquals(UserAnswer.Single("B"), state.answers["q2"])
    }

    @Test
    fun `跳过标记在提交后移除`() = runBlocking {
        val state = newState()
        state.load()

        state.skip("q1")
        assertTrue(state.skipped.containsKey("q1"))

        state.submit(question("q1"), UserAnswer.Single("A"))

        assertTrue("提交后该题进入只读反馈态", state.submitted.containsKey("q1"))
        assertFalse("提交过的题不该再算跳过", state.skipped.containsKey("q1"))
    }

    @Test
    fun `提交闸门挡住在途重复提交`() = runBlocking {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val state = newState(submit = { _, _ ->
            calls++
            gate.await()
            SubmitSummary(1.0, true)
        })
        val q1 = question("q1")

        val inFlight = launch { state.submit(q1, UserAnswer.Single("A")) }
        yield()

        assertTrue("在途提交要被标记出来，按钮据此禁用（ADR-0004）", state.submitting.containsKey("q1"))

        // 同一题的第二次提交：闸门在挂起之前同步置位，应被挡下
        state.submit(q1, UserAnswer.Single("A"))

        gate.complete(Unit)
        inFlight.join()

        assertEquals("同一道题只应落库一次", 1, calls)
        assertFalse("提交完成后闸门解除", state.submitting.containsKey("q1"))
    }

    @Test
    fun `选项打乱开关随会话参数带入`() {
        val state = newState(
            args = SessionArgs(listOf("q1"), shuffleOptions = false, processToken = TOKEN),
        )

        assertFalse(state.shuffleOptions)
    }
}
