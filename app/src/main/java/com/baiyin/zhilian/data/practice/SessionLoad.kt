package com.baiyin.zhilian.data.practice

import com.baiyin.zhilian.data.db.QuestionEntity

/**
 * 练习会话的启动结果。
 *
 * 存在理由：原先「题目列表为空」同时兼任「还在查」「查完没题」「参数丢了」三种含义，
 * 会话页对三者一律渲染转圈——参数丢失时用户看到的是**永不结束的加载**，只能杀进程。
 * 现在四态各自命名，转圈只可能来自 [Loading]，其余三态都必须给出路。
 *
 * 形状刻意与将来给「首帧门控」复用的通用 `Loadable<T>` 对齐（届时机械泛型化即可）；
 * 成因用有限枚举而非自由字符串——文案要过 stringResource、也要能在单测里断言。
 */
sealed interface SessionLoad {

    /** 载入中：全应用唯一的会话转圈来源 */
    data object Loading : SessionLoad

    /**
     * 至少一道可练习题（CONTEXT.md「可练习题目」）。
     * [droppedCount] = 请求里已被停用或已不在库的题数，> 0 时界面给一句非阻断提示。
     */
    data class Ready(val questions: List<QuestionEntity>, val droppedCount: Int) : SessionLoad

    /** 请求有效，但一道可练习题都没有 */
    data class Empty(val reason: EmptyReason) : SessionLoad

    /** 无法开始这次会话：参数问题或读库失败 */
    data class Failed(val reason: FailureReason) : SessionLoad

    enum class EmptyReason {
        /** 请求的题全部不在库或已停用——停用题不再是「可练习题目」 */
        NoPracticableQuestions,
    }

    enum class FailureReason {
        /** 既没有会话参数，参数里也没有题目 ID */
        MissingArgs,

        /** 参数由上一个进程写入：进程终止后回退栈被系统恢复，未完成会话不恢复（CONTEXT.md） */
        StaleSession,

        /** 读库失败：可重试 */
        LoadFailed,
    }
}
