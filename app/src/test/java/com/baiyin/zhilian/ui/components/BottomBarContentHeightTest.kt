package com.baiyin.zhilian.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 底栏高度契约纯算术测试。
 *
 * 锁「底部留白 = 底栏高度 + 上下留白 ×2 + 手势条」这一公式：它是底栏渲染链与
 * 四个 tab 屏底部留白之间的镜像约定，算错的表现是列表最后一项被底栏遮住一条，
 * 静默且只有滚到底才可见。
 */
class BottomBarContentHeightTest {

    @Test
    fun `bar height plus doubled vertical padding plus navigation bar`() {
        assertEquals(128.dp, bottomBarContentHeight(barHeight = 64.dp, verticalPadding = 8.dp, navigationBarBottom = 48.dp))
    }

    @Test
    fun `gesture-less device contributes zero`() {
        // 全面屏手势条高度为 0 的设备：只含底栏本体与上下留白
        assertEquals(80.dp, bottomBarContentHeight(barHeight = 64.dp, verticalPadding = 8.dp, navigationBarBottom = 0.dp))
    }

    @Test
    fun `vertical padding counts twice`() {
        // 镜像绑定的另一端：渲染链 padding(vertical) 上下各一份，公式必须 ×2
        val base = bottomBarContentHeight(barHeight = 64.dp, verticalPadding = 8.dp, navigationBarBottom = 48.dp)
        val bumped = bottomBarContentHeight(barHeight = 64.dp, verticalPadding = 9.dp, navigationBarBottom = 48.dp)
        assertEquals(base + 2.dp, bumped)
    }
}
