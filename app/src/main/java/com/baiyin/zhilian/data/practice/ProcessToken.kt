package com.baiyin.zhilian.data.practice

import java.util.UUID

/**
 * 进程令牌：每个进程启动时生成一次，进程内恒定。
 *
 * 用途是**区分两种重建**：会话参数里带着写入时的令牌，重建时比对——
 * 相同说明参数出自本进程（配置变更，会话必须继续）；不同说明参数出自上一个进程
 * （进程终止，未完成会话不恢复，见 CONTEXT.md「练习会话」）。
 *
 * 必须是**进程级单例**（Kotlin `object` 按类加载器唯一）。若挂在 AppContainer 上会随
 * Activity 重建换值——「进程令牌」退化成「Activity 令牌」，配置变更就会被误判成进程终止，
 * 用户切一下系统深色模式会话就被作废。
 *
 * 测试不读这里：`PracticeSessionState` 的令牌是构造参数，单测直接传固定值。
 */
internal object ProcessToken {
    val value: String = UUID.randomUUID().toString()
}
