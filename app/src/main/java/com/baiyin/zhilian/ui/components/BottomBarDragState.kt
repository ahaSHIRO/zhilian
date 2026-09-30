package com.baiyin.zhilian.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 底栏胶囊的拖动状态（ADR-0010 修订）。
 *
 * 职责参考 Backdrop 官方示例的 `DampedDragAnimation`（Apache-2.0），但按本项目
 * 「整条 bar 都可起拖」「松手才切页面」「只接管水平拖动」三点做了裁剪，
 * 并改用**普通 float 状态 + `animate()` 顶层函数**而非 `Animatable`——
 * 拖动回调不是 suspend 上下文，逐帧 `snapTo` 只能靠层层 launch，会引入乱序抖动。
 *
 * 它管四件与手感相关的事：
 * 1. [value]：胶囊位置（浮点 tab 序号）——拖动时逐帧跟手，松手后弹簧吸附到整数；
 * 2. [velocity]：归一化位移速度——拖快时胶囊沿运动方向拉伸（液滴感）；
 * 3. [pressProgress]：按压进度——驱动高光 / 内阴影 / 边缘色散 / 膨胀；
 * 4. [isDragging]：拖动期间禁止外部同步插手，避免与手指抢同一个值。
 */
@Stable
internal class BottomBarDragState(
    private val scope: CoroutineScope,
    tabCount: Int,
    initialIndex: Int,
) {
    private val lastIndex = (tabCount - 1).coerceAtLeast(0)

    private val maxIndex = lastIndex.toFloat()

    /** 胶囊位置（浮点 tab 序号） */
    var value by mutableFloatStateOf(initialIndex.coerceIn(0, lastIndex).toFloat())
        private set

    /** 归一化位移速度，驱动拉伸形变 */
    var velocity by mutableFloatStateOf(0f)
        private set

    /** 按压进度 0..1 */
    var pressProgress by mutableFloatStateOf(0f)
        private set

    /** 是否正被手指拖动（拖动期间外部同步不得插手） */
    var isDragging by mutableStateOf(false)
        private set

    private var settleJob: Job? = null
    private var pressJob: Job? = null
    private var velocityJob: Job? = null

    private val settleSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 600f)
    private val pressSpec: AnimationSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 420f)
    private val velocitySpec: AnimationSpec<Float> = spring(dampingRatio = 0.5f, stiffness = 320f)

    /**
     * 按下：胶囊开始鼓起（标准档不调用，交给 M3 涟漪）。
     *
     * 幂等：父层与子项可能都会上报按下，重复触发不得重启动画（否则鼓起会出现抖动）。
     * @return 本次调用是否真的启动了动画
     */
    fun press(): Boolean {
        if (pressJob?.isActive == true) return false
        animatePress(to = 1f)
        return true
    }

    /** 拖动正式开始：接管位置 */
    fun beginDrag() {
        isDragging = true
    }

    /**
     * 拖动中：位置直接跟手（不插值、不 launch，保证零延迟），
     * 速度按帧间位移做一阶低通——逐帧抖动若不过滤会被放大成形变噪声。
     */
    fun drag(index: Float) {
        val previous = value
        val clamped = index.coerceIn(0f, maxIndex)
        value = clamped
        val instant = (clamped - previous) * VELOCITY_GAIN
        velocity += (instant - velocity) * VELOCITY_SMOOTHING
    }

    /** 松手：吸附到最近一格，回弹结束后回调目标序号（页面切换在此时才发生） */
    fun settle(onSettled: (Int) -> Unit) {
        isDragging = false
        val target = nearestIndex()
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(value, target.toFloat(), animationSpec = settleSpec) { v, _ -> value = v }
            onSettled(target)
        }
        animateVelocity(to = 0f)
        // 这里直接撤按压，不走 releasePress()：后者在拖动期间会拒绝执行
        animatePress(to = 0f)
    }

    /** 手势判给纵向或取消：原地弹回最近一格，不切换页面 */
    fun cancelDrag() {
        isDragging = false
        val target = nearestIndex().toFloat()
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(value, target, animationSpec = settleSpec) { v, _ -> value = v }
        }
        animateVelocity(to = 0f)
        animatePress(to = 0f)
    }

    /**
     * 未成拖动（点击 / 纵向滑动）：只撤按压态。
     *
     * **拖动期间拒绝执行**——这是本状态机的不变量：拖动中胶囊必须一直保持鼓起，
     * 任何「松手/取消」都要经 [settle] 或 [cancelDrag] 收尾。
     * 曾经子项的手势在父层 `consume()` 后仍无条件调到这里，导致拖动刚起步按压就被撤销、
     * `progress` 全程为 0、胶囊不膨胀（见 pitfalls 2.12）。
     *
     * @return 本次调用是否被接受（`false` = 因正在拖动而被拒绝）
     */
    fun releasePress(): Boolean {
        if (isDragging) return false
        animatePress(to = 0f)
        return true
    }

    /** 外部同步（点击 tab、路由变化）：把胶囊动画到新的选中项；拖动中不插手 */
    fun syncTo(index: Int) {
        if (isDragging) return
        val target = index.coerceIn(0, lastIndex).toFloat()
        if (value == target) return
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(value, target, animationSpec = settleSpec) { v, _ -> value = v }
        }
    }

    /** 当前最近的整数序号（用于跨格触感反馈） */
    fun nearestIndex(): Int = value.roundToInt().coerceIn(0, lastIndex)

    private fun animatePress(to: Float) {
        pressJob?.cancel()
        pressJob = scope.launch {
            animate(pressProgress, to, animationSpec = pressSpec) { v, _ -> pressProgress = v }
        }
    }

    private fun animateVelocity(to: Float) {
        velocityJob?.cancel()
        velocityJob = scope.launch {
            animate(velocity, to, animationSpec = velocitySpec) { v, _ -> velocity = v }
        }
    }

    private companion object {
        /** 帧间位移 → 速度的放大系数：拖得快时明显拉长，轻推不至于满格 */
        const val VELOCITY_GAIN = 6f

        /** 速度低通系数：越小越平滑、越迟钝 */
        const val VELOCITY_SMOOTHING = 0.35f
    }
}
