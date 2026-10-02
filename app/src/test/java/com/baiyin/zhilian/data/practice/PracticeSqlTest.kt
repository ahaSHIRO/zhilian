package com.baiyin.zhilian.data.practice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选题条件 SQL 的单测：条件怎么拼、排序怎么给——纯函数，故不必插设备就能问。
 *
 * 锁的是「同一条件出现在多处、其中一处漏了排序」这类漂移：标签路径原先取的投影没有 ORDER BY，
 * 于是「顺序练习」在选了标签之后退化成「按导入先后刷」。
 */
class PracticeSqlTest {

    @Test
    fun `空条件只要求未停用`() {
        assertEquals("inactive = 0", PracticeSql.where(PracticeFilter()))
    }

    @Test
    fun `维度内取并集、维度间取交集`() {
        val sql = PracticeSql.where(
            PracticeFilter(
                subjects = setOf("kotlin", "java"),
                categories = setOf("协程"),
                onlyWrong = true,
                onlyFavorite = true,
            )
        )

        assertEquals(
            "inactive = 0 AND subject IN ('kotlin','java') AND category IN ('协程') " +
                "AND $SQL_WRONG AND favorite = 1",
            sql,
        )
    }

    @Test
    fun `值里的单引号被转义`() {
        val sql = PracticeSql.where(PracticeFilter(categories = setOf("it's")))

        assertTrue("未转义的引号会截断字符串字面量：$sql", sql.contains("'it''s'"))
    }

    @Test
    fun `顺序与随机给出不同排序`() {
        assertEquals("batch_order, order_in_batch", PracticeSql.orderBy(sequential = true))
        assertEquals("RANDOM()", PracticeSql.orderBy(sequential = false))
    }

    @Test
    fun `错题条件的完整 WHERE 单一入口`() {
        assertEquals("inactive = 0 AND $SQL_WRONG", PracticeSql.wrongClause())
    }
}
