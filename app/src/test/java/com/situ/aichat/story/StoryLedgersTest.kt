package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账本族纯函数 T1（图纸 §7 T1-1·E3/E5/E6）。
 *
 * 断言从提案 §4.2–§4.4 与图纸 §3.3 的算法规格**独立反推**：两段制分流、里程碑永不裁剪、近况 30 行 /
 * 台账 40 行滚动（±1 精度）、按章号回滚幂等、「无」归一、手改账本的容错方向（宁可不裁剪也不误删）。
 */
class StoryLedgersTest {

    private val L = StoryLedgers

    private fun sectionOf(ledger: String, header: String): List<String> =
        ledger.lines().map { it.trim() }
            .dropWhile { it != header }.drop(1)
            .takeWhile { it != L.MILESTONE_HEADER && it != L.RECENT_HEADER }
            .filter { it.isNotEmpty() }

    // ── normalizeMeta ──

    @Test fun normalizeMeta_空与无归null_其余trim() {
        assertNull(L.normalizeMeta(null))
        assertNull(L.normalizeMeta(""))
        assertNull(L.normalizeMeta("   "))
        assertNull("模型写「无」= 本章没有这件事", L.normalizeMeta("无"))
        assertNull("前后空白的「无」同样归 null", L.normalizeMeta("  无  "))
        assertEquals("客厅｜两人相拥", L.normalizeMeta("  客厅｜两人相拥  "))
        assertEquals("「无奈的对视」不是「无」，不许被误伤", "无奈的对视", L.normalizeMeta("无奈的对视"))
    }

    // ── appendIntimacy：分流与章号前缀 ──

    @Test fun 关系史_首次追加_两段各就位且带章号() {
        val ledger = L.appendIntimacy(
            existing = null,
            updates = "[里程碑]第一次接吻；[近况]她开始喊他的小名",
            chapterNumber = 7,
        )!!

        assertEquals(listOf("第7章·第一次接吻"), sectionOf(ledger, L.MILESTONE_HEADER))
        assertEquals(listOf("第7章·她开始喊他的小名"), sectionOf(ledger, L.RECENT_HEADER))
        assertTrue("两段标题各出现一次", ledger.split(L.MILESTONE_HEADER).size == 2)
        assertTrue(ledger.split(L.RECENT_HEADER).size == 2)
        assertTrue("里程碑段在前", ledger.indexOf(L.MILESTONE_HEADER) < ledger.indexOf(L.RECENT_HEADER))
    }

    @Test fun 关系史_半角分号与多余空白同样切分() {
        val ledger = L.appendIntimacy(null, " [近况]牵手 ;  [近况]拥抱 ； [里程碑]告白 ", 2)!!
        assertEquals(listOf("第2章·告白"), sectionOf(ledger, L.MILESTONE_HEADER))
        assertEquals(listOf("第2章·牵手", "第2章·拥抱"), sectionOf(ledger, L.RECENT_HEADER))
    }

    @Test fun 关系史_无前缀条目按近况处理() {
        // E6：模型漏写前缀时按「近况」（会滚动的那侧）处理——错放里程碑会让噪音永久占据不可裁剪段
        val ledger = L.appendIntimacy(null, "她主动挽住他的手臂", 3)!!
        assertTrue(sectionOf(ledger, L.MILESTONE_HEADER).isEmpty())
        assertEquals(listOf("第3章·她主动挽住他的手臂"), sectionOf(ledger, L.RECENT_HEADER))
    }

    @Test fun 关系史_缺失或无或空条目_账本原样不动() {
        val existing = "${L.MILESTONE_HEADER}\n第1章·初遇\n\n${L.RECENT_HEADER}\n第2章·散步"
        assertEquals(existing, L.appendIntimacy(existing, null, 5))
        assertEquals(existing, L.appendIntimacy(existing, "无", 5))
        assertEquals("条目全是空壳（只有前缀）→ 不动账本", existing, L.appendIntimacy(existing, "[里程碑]；[近况]", 5))
        assertNull("空账本 + 无新增 → 仍是 null", L.appendIntimacy(null, "无", 5))
    }

    @Test fun 关系史_手改丢标题行_全部归里程碑侧不误删() {
        // J4：用户在书页里把标题行删了 → 已有行一律当里程碑（永不裁剪侧），宁可不裁剪也绝不误裁剪用户内容
        val handEdited = "第1章·初遇\n第2章·一起看海\n第3章·牵手"
        val ledger = L.appendIntimacy(handEdited, "[近况]一起做饭", 4)!!

        assertEquals(
            listOf("第1章·初遇", "第2章·一起看海", "第3章·牵手"),
            sectionOf(ledger, L.MILESTONE_HEADER),
        )
        assertEquals(listOf("第4章·一起做饭"), sectionOf(ledger, L.RECENT_HEADER))
    }

    // ── 裁剪边界（E5·±1 精度）──

    @Test fun 关系史_近况恰30行不掐_31行掐最老一行() {
        val recents = (1..29).joinToString("\n") { "第${it}章·近况$it" }
        val existing = "${L.MILESTONE_HEADER}\n第0章·定情\n\n${L.RECENT_HEADER}\n$recents"

        // 29 + 1 = 恰 30 行：一行不掐
        val exactly30 = L.appendIntimacy(existing, "[近况]第30条", 30)!!
        val lines30 = sectionOf(exactly30, L.RECENT_HEADER)
        assertEquals(30, lines30.size)
        assertEquals("最老一行仍在", "第1章·近况1", lines30.first())

        // 再加一条 = 31 行：只掐最老的那一行
        val overflow = L.appendIntimacy(exactly30, "[近况]第31条", 31)!!
        val lines31 = sectionOf(overflow, L.RECENT_HEADER)
        assertEquals(30, lines31.size)
        assertEquals("掐掉的正是最老一行", "第2章·近况2", lines31.first())
        assertEquals("第31章·第31条", lines31.last())
        assertEquals("里程碑段不受近况裁剪影响", listOf("第0章·定情"), sectionOf(overflow, L.MILESTONE_HEADER))
    }

    @Test fun 关系史_里程碑永不裁剪() {
        var ledger: String? = null
        for (chapter in 1..40) ledger = L.appendIntimacy(ledger, "[里程碑]大事$chapter", chapter)
        assertEquals("里程碑 40 条一条不少", 40, sectionOf(ledger!!, L.MILESTONE_HEADER).size)
    }

    // ── appendScene：台账 ──

    @Test fun 台账_追加带章号_缺失与无不动() {
        assertEquals("第3章·雨夜·车里", L.appendScene(null, "雨夜·车里", 3))
        assertEquals("第3章·雨夜·车里\n第5章·浴室·热气", L.appendScene("第3章·雨夜·车里", "浴室·热气", 5))
        assertNull(L.appendScene(null, "无", 5))
        assertEquals("第3章·雨夜·车里", L.appendScene("第3章·雨夜·车里", null, 5))
        assertEquals("第3章·雨夜·车里", L.appendScene("第3章·雨夜·车里", "  ", 5))
    }

    @Test fun 台账_恰40行不掐_41行掐最老一行() {
        var ledger: String? = null
        for (chapter in 1..40) ledger = L.appendScene(ledger, "场面$chapter", chapter)
        assertEquals(40, ledger!!.lines().size)
        assertEquals("第1章·场面1", ledger.lines().first())

        val overflow = L.appendScene(ledger, "场面41", 41)!!
        assertEquals(40, overflow.lines().size)
        assertEquals("掐掉最老一行", "第2章·场面2", overflow.lines().first())
        assertEquals("第41章·场面41", overflow.lines().last())
    }

    @Test fun 台账_最新一条原样含章号前缀() {
        assertNull(L.latestSceneLine(null))
        assertNull(L.latestSceneLine("   \n  "))
        assertEquals(
            "第9章·天台·夜风",
            L.latestSceneLine("第3章·雨夜·车里\n第9章·天台·夜风\n"),
        )
    }

    // ── rollbackChapter（E3·重写/换版）──

    @Test fun 回滚_只删本章行_标题行保留() {
        val ledger = L.appendIntimacy(
            L.appendIntimacy(null, "[里程碑]初吻；[近况]牵手", 7),
            "[里程碑]同居；[近况]一起做饭",
            8,
        )
        val rolled = L.rollbackChapter(ledger, 8)!!

        assertEquals(listOf("第7章·初吻"), sectionOf(rolled, L.MILESTONE_HEADER))
        assertEquals(listOf("第7章·牵手"), sectionOf(rolled, L.RECENT_HEADER))
        assertTrue("标题行保留", rolled.contains(L.MILESTONE_HEADER) && rolled.contains(L.RECENT_HEADER))
    }

    @Test fun 回滚_幂等_重跑结果相同() {
        val ledger = L.appendScene(L.appendScene(null, "雨夜·车里", 3), "浴室·热气", 5)
        val once = L.rollbackChapter(ledger, 5)
        val twice = L.rollbackChapter(once, 5)
        assertEquals("第3章·雨夜·车里", once)
        assertEquals("重跑（进程死亡后重试）结果一致", once, twice)
    }

    @Test fun 回滚_删空即归null() {
        // 台账（无标题行）删光 → null
        assertNull(L.rollbackChapter(L.appendScene(null, "雨夜·车里", 3), 3))
        // 关系史两侧内容都空 → null（只剩两个光标题不算内容）
        assertNull(L.rollbackChapter(L.appendIntimacy(null, "[里程碑]初吻；[近况]牵手", 7), 7))
        assertNull(L.rollbackChapter(null, 7))
        assertNull(L.rollbackChapter("   ", 7))
    }

    @Test fun 回滚_不误伤章号前缀相近的行() {
        // 「第1章·」不许把「第11章·」也删掉（前缀匹配的经典误伤）
        val ledger = "第1章·初遇\n第11章·重逢\n第21章·告白"
        assertEquals("第11章·重逢\n第21章·告白", L.rollbackChapter(ledger, 1))
        assertEquals("第1章·初遇\n第21章·告白", L.rollbackChapter(ledger, 11))
    }
}
