package com.baiyin.zhilian.ui.screens.practice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.baiyin.zhilian.data.db.QuestionEntity
import com.baiyin.zhilian.data.practice.PracticeSessionLoader
import com.baiyin.zhilian.data.practice.SessionArgs
import com.baiyin.zhilian.data.practice.SessionLoad
import com.baiyin.zhilian.data.practice.SubmitSummary
import com.baiyin.zhilian.data.practice.UserAnswer

/**
 * 练习会话的状态容器，挂在 **NavBackStackEntry 作用域**的 ViewModel 上。
 *
 * 作用域的选取就是「两种重建」的分界线（CONTEXT.md「练习会话」）：
 * - **配置变更**（系统深浅色、字体与显示大小、语言）：entry 的 ViewModelStore 挂在 Activity 的
 *   ViewModelStore 之下，随它一起保留 → 本容器存活，作答与已提交反馈都不丢；
 * - **进程终止**：ViewModelStore 随进程消失 → 容器重建、状态自然为空，靠 [args] 里的进程令牌
 *   比对判定「未完成会话」，由界面退回配置页。
 *
 * 状态一律**以 questionId 为键**（ADR-0003）：会话内题目顺序虽稳定，但用页索引作键会让
 * 任何一次重排把作答错位到别的题上。
 *
 * 异步作业（载入、落库）由调用方的作用域驱动，本类只做纯状态机——因此它没有协程依赖，
 * 单测用 `runBlocking` 直接问「提交后是不是只读」，不必先装一个 Main 调度器。
 */
internal class PracticeSessionState(
    private val loader: PracticeSessionLoader,
    private val submitAnswer: suspend (QuestionEntity, UserAnswer) -> SubmitSummary,
    private val args: SessionArgs?,
    processToken: String,
) : ViewModel() {

    private val requestedIds: List<String> = args?.questionIds.orEmpty()

    /** 参数在位且由本进程写入，才谈得上载入 */
    private val resumable: Boolean =
        args != null && requestedIds.isNotEmpty() && args.processToken == processToken

    /** 选项打乱开关：会话参数的一部分，不属于载入结果 */
    val shuffleOptions: Boolean = args?.shuffleOptions ?: true

    /** 会话启动结果；转圈只可能来自 [SessionLoad.Loading] */
    var load: SessionLoad by mutableStateOf(
        when {
            args == null || requestedIds.isEmpty() ->
                SessionLoad.Failed(SessionLoad.FailureReason.MissingArgs)

            args.processToken != processToken ->
                SessionLoad.Failed(SessionLoad.FailureReason.StaleSession)

            else -> SessionLoad.Loading
        },
    )
        private set

    private val answersById = mutableStateMapOf<String, UserAnswer>()
    private val submittedById = mutableStateMapOf<String, SubmitSummary>()
    private val skippedById = mutableStateMapOf<String, Boolean>()
    private val submittingById = mutableStateMapOf<String, Boolean>()

    /** 未提交的作答（按 questionId），已提交的题不再使用 */
    val answers: Map<String, UserAnswer> get() = answersById

    /** 已提交结果（按 questionId）：存在即该卡为只读反馈态 */
    val submitted: Map<String, SubmitSummary> get() = submittedById

    /** 点过跳过且未作答的题；提交后移除 */
    val skipped: Map<String, Boolean> get() = skippedById

    /** 在途提交的题（**键存在即在途**，与 [skipped] 同构）：防双击重复落库（ADR-0004） */
    val submitting: Map<String, Boolean> get() = submittingById

    /**
     * 载入可练习题，**幂等**：只在 [SessionLoad.Loading] 态或 [force] 时真正查询。
     * 幂等性保证配置变更后重组重跑 effect 不会把会话退回转圈。
     */
    suspend fun load(force: Boolean = false) {
        if (!resumable) return
        if (!force && load !is SessionLoad.Loading) return
        load = SessionLoad.Loading
        load = loader.load(requestedIds)
    }

    fun answer(questionId: String, value: UserAnswer) {
        answersById[questionId] = value
    }

    fun skip(questionId: String) {
        skippedById[questionId] = true
    }

    /**
     * 提交作答并落库。
     *
     * 闸门**在挂起之前同步置位**：两次点击若都赶到 `submitAnswer` 之前，只会有一次落库；
     * 原先闸门写在 `scope.launch` 之内，靠协程派发先于第二次点击上抛来兜底（ADR-0004 的缝隙）。
     */
    suspend fun submit(question: QuestionEntity, answer: UserAnswer) {
        val id = question.questionId
        if (submittingById.containsKey(id)) return
        submittingById[id] = true
        try {
            submittedById[id] = submitAnswer(question, answer)
            skippedById.remove(id)
        } finally {
            // 移除键而非置 false：在途与否是「键在不在」的问题，留着 false 的残键会让
            // containsKey 永远为真（单测抓到的）
            submittingById.remove(id)
        }
    }
}
