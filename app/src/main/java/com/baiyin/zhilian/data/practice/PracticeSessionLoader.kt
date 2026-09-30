package com.baiyin.zhilian.data.practice

import com.baiyin.zhilian.data.db.QuestionEntity
import kotlinx.coroutines.CancellationException

/**
 * 会话载入器：题目 ID 列表 → [SessionLoad] 四态中的可载入三态
 * （[SessionLoad.Loading] 由状态容器持有，[SessionLoad.FailureReason.StaleSession]
 * 由状态容器在比对进程令牌后产出，本模块与进程无关）。
 *
 * [loadQuestions] 是唯一的外部依赖，按函数注入：生产接 `QuestionBank::get`，
 * 单测给一段内存实现即可问「规则对不对」，不必插设备。
 */
class PracticeSessionLoader(
    private val loadQuestions: suspend (List<String>) -> List<QuestionEntity>,
) {

    /**
     * 载入 [questionIds] 指向的可练习题。
     *
     * 两条契约：
     * 1. **按请求顺序返回**。题库查询按批次顺序返回，而「顺序/随机」的选择结果编码在
     *    [questionIds] 的顺序里；不重排就会让随机练习退化成顺序
     *    （同型修复见 [QuestionPicker.pick] 的标签分支）。
     * 2. **只取未停用的题**，且只在载入这一刻判定：会话进行中若某题被新批次停用，
     *    不把它从用户眼前抽走，下次进入会话自然就不再出现。
     */
    suspend fun load(questionIds: List<String>): SessionLoad {
        if (questionIds.isEmpty()) {
            return SessionLoad.Failed(SessionLoad.FailureReason.MissingArgs)
        }
        val fetched = try {
            loadQuestions(questionIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 读库失败是可重试态，既不该崩掉也不该变成永久转圈
            return SessionLoad.Failed(SessionLoad.FailureReason.LoadFailed)
        }

        val byId = fetched.filterNot { it.inactive }.associateBy { it.questionId }
        val ordered = questionIds.mapNotNull { byId[it] }
        if (ordered.isEmpty()) {
            return SessionLoad.Empty(SessionLoad.EmptyReason.NoPracticableQuestions)
        }
        return SessionLoad.Ready(
            questions = ordered,
            droppedCount = questionIds.size - ordered.size,
        )
    }
}
