package com.baiyin.zhilian.ui.navigation

import com.baiyin.zhilian.data.practice.ProcessToken
import com.baiyin.zhilian.data.practice.SessionArgs

/** 练习会话路由（不在底部导航显示） */
internal const val ROUTE_PRACTICE_SESSION = "practice_session"

/** 批次管理路由（不在底部导航显示） */
internal const val ROUTE_BATCHES = "batches"

/** 会话参数在发起页 savedStateHandle 中的键（载荷见 [SessionArgs]） */
internal const val KEY_SESSION_ARGS = "sessionArgs"

/**
 * 导航意图模块：把「**一次用户意图 → 至多一次导航**」这条规则收在一处。
 *
 * 存在理由：进入与退出原先各自裸调 `navigate` / `popBackStack`，两处都出过重复导航——
 * 「开始练习」在选题挂起期间连点两次会压入两层会话页（第二层读不到参数），
 * 「完成 / 返回」在 300ms 侧滑退出动画期间再点一次会把发起页一起弹掉（C2 / C9，形状相同、方向相反）。
 *
 * 规则只有一条，四个意图共用：**每个意图声明自己的来源页，栈顶不是来源页就丢弃该意图**。
 * - 对退出类意图是天然的：pop 之后栈顶立刻变成发起页，第二次点击自动作废；
 * - 对进入类意图同样成立：`navigate` 会**同步**更新回退栈（300ms 只是转场动画），
 *   于是第二次调用读到的 `currentRoute()` 已是目标页 → 被丢弃。
 *
 * 守卫刻意**不用计时器、不用布尔标志**——标志要重置，而「什么时候重置」本身就是新的 bug 源；
 * 也不依赖「转场期间下层页面不可点」这种未经验证的平台行为。
 *
 * 系统返回键不在此拦截：用户按的是同一个按钮、意图是「结束这次练习」而不是「退出 App」；
 * 连按返回退出属常见手感，且重开页面级 BackHandler 会与 ADR-0009 冲突。
 *
 * 依赖全部按函数注入（`currentRoute` / `navigateTo` / `popBack` / 参数读写），
 * 单测给一段内存实现即可问「连点两次到底导航几次」，不必起设备。
 */
internal class PracticeNavigation(
    private val currentRoute: () -> String?,
    private val navigateTo: (String) -> Unit,
    private val popBack: () -> Unit,
    private val writeSessionArgs: (SessionArgs) -> Unit,
    private val clearSessionArgs: () -> Unit,
) {

    /**
     * 开始练习：写入会话参数（含进程令牌）并进入会话页。
     * 只在练习配置页生效——选题在途或转场中的重复点击在此被丢弃。
     */
    fun startPractice(questionIds: List<String>, shuffleOptions: Boolean) {
        if (currentRoute() != TopLevelDestination.PRACTICE.route) return
        writeSessionArgs(SessionArgs(questionIds, shuffleOptions, ProcessToken.value))
        navigateTo(ROUTE_PRACTICE_SESSION)
    }

    /**
     * 退出会话：先清掉发起页残留的会话参数，再弹出会话页。
     * 只在会话页生效——转场动画期间的第二次点击在此被丢弃。
     *
     * 先清后 pop：pop 之后会话条目已销毁、previous 关系也变了，再回头定位发起页的 handle 要绕；
     * 若守卫判定不该退出（已在别的页），只清参数不动栈也无害——下次开始练习本就会重写参数。
     */
    fun exitSession() {
        if (currentRoute() != ROUTE_PRACTICE_SESSION) return
        clearSessionArgs()
        popBack()
    }

    /** 打开批次管理：只在设置页生效 */
    fun openBatches() {
        if (currentRoute() != TopLevelDestination.SETTINGS.route) return
        navigateTo(ROUTE_BATCHES)
    }

    /** 关闭批次管理：只在批次管理页生效 */
    fun closeBatches() {
        if (currentRoute() != ROUTE_BATCHES) return
        popBack()
    }
}
