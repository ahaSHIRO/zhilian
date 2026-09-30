package com.baiyin.zhilian.data.batch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次目录扫描单测：四次扫描之间的状态契约。
 *
 * 锁的是真实踩过的三条：① 每次 ON_RESUME 都起一个新扫描且互不取消，旧结果可能覆盖新结果；
 * ② 首次进入因「观察者补发 + 显式分支」并发扫两遍；③ SAF 抛异常没有兜底，直接崩。
 * 本测试用 `Dispatchers.Unconfined` 驱动，`start()` 同步跑到第一个挂起点或跑完，故可断言中间态。
 */
class BatchDirectoryScanTest {

    private fun scope() = CoroutineScope(Dispatchers.Unconfined)

    private fun ref(name: String) = BatchFileRef(key = "content://$name", name = name)

    @Test
    fun `首次扫描给出结果`() {
        val scan = BatchDirectoryScan(
            listFiles = { listOf(ref("b1.json")) },
            parse = { null },
            scope = scope(),
        )

        scan.start()

        val state = scan.state.value
        assertTrue("首次扫描应直接落到 Ready：$state", state is ScanState.Ready)
        assertEquals(listOf("b1.json"), (state as ScanState.Ready).batches.map { it.file.name })
    }

    @Test
    fun `重扫期间保持旧结果，不退回骨架`() {
        var round = 0
        val gate = CompletableDeferred<Unit>()
        val scan = BatchDirectoryScan(
            listFiles = { listOf(ref("b1.json")) },
            parse = {
                if (round >= 1) gate.await()
                null
            },
            scope = scope(),
        )

        scan.start()
        assertTrue(scan.state.value is ScanState.Ready)

        // 第二次扫描：解析卡住，此时界面必须仍显示上一次的结果（骨架只属于首次）
        round = 1
        scan.start()
        assertTrue(
            "已就绪后的重扫若退回骨架，副标题会集体闪回「加载中」",
            scan.state.value is ScanState.Ready,
        )

        gate.complete(Unit)
        assertTrue(scan.state.value is ScanState.Ready)
    }

    @Test
    fun `新扫描取消在途的那次，旧结果不会覆盖新结果`() {
        var listCalls = 0
        val gate = CompletableDeferred<Unit>()
        val scan = BatchDirectoryScan(
            listFiles = {
                listCalls++
                if (listCalls == 1) gate.await() // 第一次卡住
                emptyList()
            },
            parse = { null },
            scope = scope(),
        )

        scan.start()
        scan.start()

        assertEquals("第二次扫描必须真的发起", 2, listCalls)
        assertTrue(scan.state.value is ScanState.Ready)

        // 放行第一次扫描：它已被取消，不得再把结果写回来
        gate.complete(Unit)
        val after = scan.state.value
        assertTrue(after is ScanState.Ready)
        assertEquals(emptyList<ScannedBatch>(), (after as ScanState.Ready).batches)
    }

    @Test
    fun `目录不可读判为不可读态而不是崩溃`() {
        val scan = BatchDirectoryScan(
            listFiles = { throw SecurityException("permission denied") },
            parse = { null },
            scope = scope(),
        )

        scan.start()

        val state = scan.state.value
        assertTrue("SAF 授权失效应给出可读态，而不是从协程逃出去崩掉", state is ScanState.Unreadable)
        assertEquals("permission denied", (state as ScanState.Unreadable).reason)
    }

    @Test
    fun `reset 回到未开始态`() {
        val scan = BatchDirectoryScan(
            listFiles = { listOf(ref("b1.json")) },
            parse = { null },
            scope = scope(),
        )
        scan.start()
        assertTrue(scan.state.value is ScanState.Ready)

        scan.reset()

        assertEquals(ScanState.Idle, scan.state.value)
    }
}
