package com.baiyin.zhilian.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow

/**
 * 首帧门控状态（C3）：「异步数据还没到」与「数据是空的」是两件事，不能都用空值冒充。
 *
 * 原先四个屏各自用 `collectAsStateWithLifecycle(initialValue = emptyList())` / `initialValue = 0`
 * 起手，于是首帧永远渲染一个**假的终态**——题库页闪「没有符合条件的题目」、统计页闪 0、
 * 练习配置页闪「题库为空」+「符合条件的题目：0 道」，随后内容插入造成跳动
 * （批次管理页曾单独修过同类问题，见 pitfalls 2.13，但只有它一处）。
 *
 * 只取两态：对 Flow 屏而言「空结果」就是 `Data(emptyList())`（屏自己判空即可），
 * 而「失败」不是加一个枚举就完事——要决定重试语义与 Flow 终止后的行为，那是另一件事。
 */
sealed interface Loadable<out T> {

    /** 首个值尚未到达：此时不得渲染任何终态文案（包括空态文案与 0 值） */
    data object FirstLoad : Loadable<Nothing>

    data class Data<T>(val value: T) : Loadable<T>
}

/**
 * 可重订阅的首帧门控持有者：**值一旦到达就不再回落首帧态**。
 *
 * 为什么必须这样：`FirstLoad` 的含义是「这个界面**还从没**拿到过值」，而不是「这次收集刚开始」。
 * 若把首帧态绑在「每次收集开始」上（即 `flow.onStart { emit(FirstLoad) }` + 以 flow 为 key 的
 * `collectAsState`），**每一次重新订阅都会把门控重新关一次**——整页内容消失再出现。
 * 而重新订阅有两个日常触发源：
 * 1. 收集用的 Flow 实例变了（在组合里现造 Flow：每次重组都是新实例）；
 * 2. 生命周期回前台导致收集重启。
 *
 * 第 1 条会**自持成环**：数据到达 → 触发重组 → 组合里又现造一个新 Flow → 重订阅 → 发首帧态 →
 * 门控关上 → 再取数据 → 再重组…… 表现为页面疯狂闪烁。故收集语义收口在这里，
 * 值只写不回退。
 *
 * 单独成类而不是内联在屏里，是为了留出**可单测的缝**：`collectFrom` 是纯协程函数，
 * 在 JVM 单测里连收集两次就能断言「重订阅不回落」。
 */
@Stable
class LoadableState<T> : State<Loadable<T>> {

    private val holder = mutableStateOf<Loadable<T>>(Loadable.FirstLoad)

    override val value: Loadable<T> get() = holder.value

    /** 收集 [source]；每次收到值只把值写进去，绝不回落首帧态 */
    suspend fun collectFrom(source: Flow<T>) {
        source.collect { holder.value = Loadable.Data(it) }
    }
}

/**
 * 把流收集成 [Loadable]，可安全重复收集（重组、回前台都不会让门控重新关上）。
 *
 * **不要**退化成 `flow.asLoadable().collectAsStateWithLifecycle(initialValue = FirstLoad)`——
 * 那正是本文件要防的写法：以 flow 为 key 的收集会在每次重组时重启，而 `onStart` 每次都重发
 * 首帧态，于是界面自己把自己闪起来（详见 [LoadableState]）。
 *
 * 调用点若能在组合外持有稳定的流实例（例如仓里的 `val`），最好 `remember` 住再用，
 * 省掉重订阅时那次多余的库查询；即便不 remember 也只是多查一次，不会再闪。
 */
@Composable
fun <T> Flow<T>.collectAsLoadable(): State<Loadable<T>> {
    val state = remember { LoadableState<T>() }
    LaunchedEffect(this) { state.collectFrom(this@collectAsLoadable) }
    return state
}
