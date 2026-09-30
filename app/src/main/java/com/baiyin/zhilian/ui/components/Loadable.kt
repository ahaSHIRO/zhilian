package com.baiyin.zhilian.ui.components

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

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

/** 给流标出首帧：第一个值之前是 [Loadable.FirstLoad] */
fun <T> Flow<T>.asLoadable(): Flow<Loadable<T>> =
    map<T, Loadable<T>> { Loadable.Data(it) }
        .onStart { emit(Loadable.FirstLoad) }
