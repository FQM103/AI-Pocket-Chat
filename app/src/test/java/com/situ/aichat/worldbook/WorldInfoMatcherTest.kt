package com.situ.aichat.worldbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匹配器 T1（WB3·契约 §2.1）：四逻辑真值表 / 大小写与整词 / 正则键与 D3 降级 /
 * include_names / 扫描深度。断言从酒馆规格独立反推。
 */
class WorldInfoMatcherTest {

    private fun matcher(settings: WorldInfoSettings = WorldInfoSettings()) = WorldInfoMatcher(settings)

    private fun matched(
        keys: List<String>,
        buffer: String,
        secondary: List<String> = emptyList(),
        logic: Int = 0,
        selective: Boolean = true,
        caseSensitive: Boolean? = null,
        matchWholeWords: Boolean? = null,
        settings: WorldInfoSettings = WorldInfoSettings(),
    ): Boolean = matcher(settings).matchEntry(
        wbEntry(
            uuid = "e",
            keys = keys,
            secondary = secondary,
            logic = logic,
            selective = selective,
            caseSensitive = caseSensitive,
            matchWholeWords = matchWholeWords,
        ),
        buffer,
    ).matched

    @Test
    fun 主键任一命中即候选_全不中则否() {
        assertTrue(matched(listOf("青云宗", "无关词"), "昨天聊到青云宗的事"))
        assertFalse(matched(listOf("彼岸花", "无关词"), "昨天聊到青云宗的事"))
    }

    @Test
    fun 空主键永不命中() {
        assertFalse(matched(emptyList(), "随便什么内容"))
    }

    @Test
    fun 次键AND_ANY_任一命中即过() {
        assertTrue(matched(listOf("苹果"), "苹果公司发布了新品", secondary = listOf("公司", "手机"), logic = 0))
        assertFalse(matched(listOf("苹果"), "我买了一斤苹果", secondary = listOf("公司", "手机"), logic = 0))
    }

    @Test
    fun 次键NOT_ALL_全命中才拦() {
        assertFalse(matched(listOf("苹果"), "苹果公司出了新手机", secondary = listOf("公司", "手机"), logic = 1))
        assertTrue(matched(listOf("苹果"), "苹果公司股价涨了", secondary = listOf("公司", "手机"), logic = 1))
    }

    @Test
    fun 次键NOT_ANY_沾一个就拦() {
        assertTrue(matched(listOf("苹果"), "我买了一斤苹果", secondary = listOf("公司", "手机"), logic = 2))
        assertFalse(matched(listOf("苹果"), "苹果公司股价涨了", secondary = listOf("公司", "手机"), logic = 2))
    }

    @Test
    fun 次键AND_ALL_缺一个就拦() {
        assertTrue(matched(listOf("苹果"), "苹果公司出了新手机", secondary = listOf("公司", "手机"), logic = 3))
        assertFalse(matched(listOf("苹果"), "苹果公司股价涨了", secondary = listOf("公司", "手机"), logic = 3))
    }

    @Test
    fun selective关闭_次键逻辑失效() {
        assertTrue(matched(listOf("苹果"), "我买了一斤苹果", secondary = listOf("公司"), logic = 0, selective = false))
    }

    @Test
    fun 大小写默认不敏感_条目可覆盖敏感() {
        assertTrue(matched(listOf("Dragon"), "the dragon roars"))
        assertFalse(matched(listOf("Dragon"), "the dragon roars", caseSensitive = true))
        assertTrue(matched(listOf("Dragon"), "the Dragon roars", caseSensitive = true))
    }

    @Test
    fun 整词匹配_英文边界生效() {
        assertFalse(matched(listOf("cat"), "let's concatenate strings", matchWholeWords = true))
        assertTrue(matched(listOf("cat"), "a cat sleeps", matchWholeWords = true))
    }

    @Test
    fun 中文子串直接命中_默认整词关() {
        assertTrue(matched(listOf("灵田"), "后山灵田里的灵草熟了"))
    }

    @Test
    fun 正则键_字符类与flags生效() {
        assertTrue(matched(listOf("/灵[药丹]/"), "他炼出了一炉灵丹"))
        assertFalse(matched(listOf("/灵[药丹]/"), "他在灵田里干活"))
        assertTrue(matched(listOf("/DRAGON/i"), "the dragon roars"))
    }

    @Test
    fun 坏正则_降级普通子串并记诊断() {
        val m = matcher()
        val entry = wbEntry(uuid = "e", keys = listOf("/[未闭合/"))
        assertFalse(m.matchEntry(entry, "随便的内容").matched)
        // 降级后按原始文本当子串仍可命中
        assertTrue(m.matchEntry(entry, "内容里恰好有 /[未闭合/ 这段").matched)
        assertEquals(listOf("/[未闭合/"), m.badRegexKeys)
    }

    @Test(timeout = 10_000)
    fun 正则匹配超预算_限时降级不挂死激活链路() {
        // 批3 3-7：护栏语义——单键匹配超时间预算 → 与编译失败同 D3 降级（记诊断 + 永久转普通子串）。
        // 预算注入 0 = 首次取字符即超时，确定性触发（真实灾难回溯依赖引擎实现，现代 JDK 已记忆化部分经典模式，
        // 不拿引擎性能当测试对象；生产预算 50ms 对正常匹配是天文数字，绝不误伤）。
        val m = WorldInfoMatcher(WorldInfoSettings(), regexMatchBudgetNanos = 0)
        val key = "/灵[药丹]/"
        // 无护栏时本应命中（见上方「正则键_字符类与flags生效」）；超预算 → 降级子串 → 原始键文本不在正文 → 不命中。
        assertFalse(m.matchEntry(wbEntry(uuid = "e", keys = listOf(key)), "他炼出了一炉灵丹").matched)
        assertEquals(listOf(key), m.badRegexKeys)
        // 降级已生效：按原始文本当子串仍可命中，瞬时返回。
        assertTrue(m.matchEntry(wbEntry(uuid = "e", keys = listOf(key)), "正文含 /灵[药丹]/ 字样").matched)
    }

    @Test
    fun includeNames_名字进缓冲参与匹配() {
        val messages = listOf(ScanMessage("在吗", senderName = "小翠"))
        val on = matcher().buildBuffer(messages, 2)
        assertTrue(on.contains("小翠: 在吗"))
        val off = matcher(WorldInfoSettings(includeNames = false)).buildBuffer(messages, 2)
        assertEquals("在吗", off)
    }

    @Test
    fun 扫描深度_只看最近N条() {
        val messages = listOf("提到了秘银", "闲聊", "再见").map { ScanMessage(it) }
        val m = matcher()
        assertFalse("深度2 不该看到最老那条", m.buildBuffer(messages, 2).contains("秘银"))
        assertTrue(m.buildBuffer(messages, 3).contains("秘银"))
    }

    @Test
    fun 深度0_空缓冲永不命中() {
        val m = matcher()
        assertEquals("", m.buildBuffer(listOf(ScanMessage("秘银")), 0))
        assertFalse(m.matchEntry(wbEntry(uuid = "e", keys = listOf("秘银")), "").matched)
    }
}
