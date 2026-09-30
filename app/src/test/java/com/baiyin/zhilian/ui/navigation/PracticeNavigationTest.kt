package com.baiyin.zhilian.ui.navigation

import com.baiyin.zhilian.data.practice.ProcessToken
import com.baiyin.zhilian.data.practice.SessionArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航意图模块单测：四个意图的「来源页守卫」。
 *
 * 锁的是两处真实事故：选题在途时连点「开始练习」压入两层会话页（第二层读不到参数）、
 * 退出动画期间再点「完成 / 返回」把发起页一起弹掉。
 *
 * 假宿主刻意**模仿 NavController 的关键行为**：`navigate` 会同步改变栈顶（300ms 只是转场动画）。
 * 守卫正依赖这一点——若将来 Navigation 改成异步更新回退栈，这些断言会先红。
 */
class PracticeNavigationTest {

    /** 内存导航宿主 */
    private class FakeHost(startRoute: String) {
        var route: String? = startRoute

        /** pop 之后回到哪（真实环境由回退栈内容决定） */
        var popTarget: String? = TopLevelDestination.PRACTICE.route

        val navigated = mutableListOf<String>()
        var pops = 0
        val writtenArgs = mutableListOf<SessionArgs>()
        var cleared = 0

        val navigation = PracticeNavigation(
            currentRoute = { route },
            navigateTo = { r ->
                navigated += r
                route = r
            },
            popBack = {
                pops++
                route = popTarget
            },
            writeSessionArgs = { writtenArgs += it },
            clearSessionArgs = { cleared++ },
        )
    }

    @Test
    fun `连点两次开始练习只导航一次、参数只写一次`() {
        val host = FakeHost(TopLevelDestination.PRACTICE.route)

        host.navigation.startPractice(listOf("q1", "q2"), shuffleOptions = true)
        host.navigation.startPractice(listOf("q1", "q2"), shuffleOptions = true)

        assertEquals(listOf(ROUTE_PRACTICE_SESSION), host.navigated)
        assertEquals("压两层会话页会留下一个读不到参数的僵尸页", 1, host.writtenArgs.size)
    }

    @Test
    fun `不在练习配置页时开始练习被丢弃`() {
        val host = FakeHost(TopLevelDestination.BANK.route)

        host.navigation.startPractice(listOf("q1"), shuffleOptions = true)

        assertTrue(host.navigated.isEmpty())
        assertTrue(host.writtenArgs.isEmpty())
    }

    @Test
    fun `写进会话参数的是当前进程令牌与本次选择`() {
        val host = FakeHost(TopLevelDestination.PRACTICE.route)

        host.navigation.startPractice(listOf("q1"), shuffleOptions = false)

        val args = host.writtenArgs.single()
        assertEquals(ProcessToken.value, args.processToken)
        assertEquals(listOf("q1"), args.questionIds)
        assertFalse(args.shuffleOptions)
    }

    @Test
    fun `不在会话页时退出不弹栈也不清参数`() {
        val host = FakeHost(TopLevelDestination.PRACTICE.route)

        host.navigation.exitSession()

        assertEquals(0, host.pops)
        assertEquals(0, host.cleared)
    }

    @Test
    fun `连点两次完成只弹一层`() {
        val host = FakeHost(ROUTE_PRACTICE_SESSION)

        host.navigation.exitSession()
        host.navigation.exitSession()

        assertEquals("第二次点击会把练习首页一起弹掉", 1, host.pops)
        assertEquals(1, host.cleared)
    }

    @Test
    fun `退出时先清会话参数再弹栈`() {
        val order = mutableListOf<String>()
        val navigation = PracticeNavigation(
            currentRoute = { ROUTE_PRACTICE_SESSION },
            navigateTo = { },
            popBack = { order += "pop" },
            writeSessionArgs = { },
            clearSessionArgs = { order += "clear" },
        )

        navigation.exitSession()

        assertEquals(listOf("clear", "pop"), order)
    }

    @Test
    fun `批次页两个意图同样只在来源页生效`() {
        val settings = FakeHost(TopLevelDestination.SETTINGS.route)
        settings.navigation.openBatches()
        assertEquals(listOf(ROUTE_BATCHES), settings.navigated)

        val bank = FakeHost(TopLevelDestination.BANK.route)
        bank.navigation.openBatches()
        bank.navigation.closeBatches()
        assertTrue("非来源页不得打开批次管理", bank.navigated.isEmpty())
        assertEquals("非来源页不得弹出批次管理", 0, bank.pops)

        val batches = FakeHost(ROUTE_BATCHES)
        batches.navigation.closeBatches()
        assertEquals(1, batches.pops)
    }
}
