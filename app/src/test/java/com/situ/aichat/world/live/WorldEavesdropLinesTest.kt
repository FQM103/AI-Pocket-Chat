package com.situ.aichat.world.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldEavesdropLines] T1-1（纯函数·图纸 §7·E11）：解析金标（合法 3/4 句、缺【动静】、纯乱文、>4 钳 4、名字不匹配→弃稿）
 * + 模板池种子确定性。断言从图纸 §3/§9 独立反推。缺【动静】判弃稿 = 「无摘要无法只留一句入世界事件」（§11 记）。
 */
class WorldEavesdropLinesTest {

    private val a = "小K"
    private val b = "阿哲"

    @Test
    fun `合法3句_带动静`() {
        val out = WorldEavesdropLines.parse("小K：你可算来了\n阿哲：路上堵了会儿\n小K：先坐下歇歇\n【动静】两人约了周末爬山", a, b)
        assertEquals(3, out!!.lines.size)
        assertEquals("小K", out.lines[0].speaker)
        assertEquals("你可算来了", out.lines[0].text)
        assertEquals("两人约了周末爬山", out.summary)
    }

    @Test
    fun `合法4句`() {
        val out = WorldEavesdropLines.parse("小K：一\n阿哲：二\n小K：三\n阿哲：四\n【动静】唠了四句", a, b)
        assertEquals(4, out!!.lines.size)
        assertEquals("唠了四句", out.summary)
    }

    @Test
    fun `缺动静_判弃稿`() {
        assertNull(WorldEavesdropLines.parse("小K：你来啦\n阿哲：可不是", a, b))
    }

    @Test
    fun `纯乱文_判弃稿`() {
        assertNull(WorldEavesdropLines.parse("哈哈哈哈\n随便一段没有格式的话\n【动静】没对话", a, b))
    }

    @Test
    fun `超4句_钳4`() {
        val out = WorldEavesdropLines.parse("小K：一\n阿哲：二\n小K：三\n阿哲：四\n小K：五\n【动静】超了", a, b)
        assertEquals(">4 句只取前 4", 4, out!!.lines.size)
        assertEquals("超了", out.summary)
    }

    @Test
    fun `说话人名不匹配_判弃稿`() {
        assertNull(WorldEavesdropLines.parse("小K：你来啦\n路人甲：我是谁\n【动静】混进外人", a, b))
    }

    @Test
    fun `ascii冒号也可解析`() {
        val out = WorldEavesdropLines.parse("小K:hi\n阿哲:yo\n【动静】英文冒号", a, b)
        assertEquals(2, out!!.lines.size)
    }

    @Test
    fun `模板池_同种子日对恒同`() {
        val one = WorldEavesdropLines.templateLines(a, b, "pk", 100L, 42L)
        val two = WorldEavesdropLines.templateLines(a, b, "pk", 100L, 42L)
        assertEquals(one, two)
        assertEquals("首句给 A", a, one[0].speaker)
        assertEquals("次句给 B", b, one[1].speaker)
    }

    @Test
    fun `模板池_跨日变化`() {
        val groups = (100L..130L).map { WorldEavesdropLines.templateLines(a, b, "pk", it, 42L)[0].text }.toSet()
        assertTrue("跨日应至少出现两组", groups.size > 1)
    }

    @Test
    fun `模板池_跨对变化`() {
        val groups = (0..30).map { WorldEavesdropLines.templateLines(a, b, "pair$it", 100L, 42L)[0].text }.toSet()
        assertTrue("跨对应至少出现两组", groups.size > 1)
    }
}
