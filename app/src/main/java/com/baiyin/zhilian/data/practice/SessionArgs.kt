package com.baiyin.zhilian.data.practice

import java.io.Serializable

/**
 * 一次导航携带的会话参数。
 *
 * 刻意合成**单一载荷**而不拆成多个 key：多 key 分散读取时漏读一个就静默退回默认值，
 * 而「一次导航 = 一份会话参数」本就是一个整体；兜底也只剩一处。
 *
 * 落在上一页 entry 的 `savedStateHandle` 里（官方「向上一页回传结果」模式），
 * 配置变更与进程终止两种重建都会恢复（navigation 2.10.2 源码：entry 自身是
 * SavedStateRegistryOwner，值随 NavController.saveState 进 save-state Bundle）。
 * 因此 [processToken] 也随之恢复——它正是判断「这份参数是不是上一个进程写的」的依据。
 *
 * 必须显式实现 [Serializable]：Kotlin `data class` 默认不实现，而 savedStateHandle
 * 对值的类型有白名单校验。
 */
data class SessionArgs(
    /** 本次会话的题目 ID，**顺序即练习顺序**（顺序或随机在发起页就已定序） */
    val questionIds: List<String>,
    /** 选项是否按题稳定打乱（CONTEXT.md「选项打乱」） */
    val shuffleOptions: Boolean,
    /** 写入这份参数时的进程令牌，见 [ProcessToken] */
    val processToken: String,
) : Serializable
