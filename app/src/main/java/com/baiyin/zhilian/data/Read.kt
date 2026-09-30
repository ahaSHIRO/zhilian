package com.baiyin.zhilian.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * 尚未读到的设置值。
 *
 * 设置存在 DataStore 里、读盘是异步的，而 `collectAsState(initialValue = 默认值)` 会让**首帧
 * 必然按默认值渲染**——用户设了深色、系统是浅色时冷启动闪一帧浅色；设了磨砂的用户，
 * 首帧还要多付一帧液态玻璃的渲染；设置屏更会把默认档画成「已选中」的样子。
 *
 * [Pending] 把「还没读到」显式化，各消费点按**最保守**的方式渲染：
 * 主题跟系统、底栏按最便宜的档、档位选择器不预选、批次目录不喊「尚未授权」。
 */
sealed interface Read<out T> {

    /** 还没有读到（冷启动的头几帧） */
    data object Pending : Read<Nothing>

    data class Value<T>(val value: T) : Read<T>
}

/** 给流标出「读到之前」：第一个值之前是 [Read.Pending] */
fun <T> Flow<T>.asRead(): Flow<Read<T>> =
    map<T, Read<T>> { Read.Value(it) }.onStart { emit(Read.Pending) }

/** 已读到的值；[Pending] 时为 null，由调用方决定保守渲染的方式 */
val <T> Read<T>.valueOrNull: T?
    get() = (this as? Read.Value<T>)?.value
