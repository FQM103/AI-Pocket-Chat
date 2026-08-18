package com.situ.aichat.prompt.growth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T1-1（图纸 §7）：词表 [RelationshipLexicon] 资产从 `src/main/assets/growth/relationship_lexicon.tsv`
 * 以 File 直读（JVM 零 Android 依赖）；§3.2 锁定映射逐词全过 + E9/E10/E11/E14/E19 + 全表体检。
 */
class RelationshipLexiconTest {

    // 真词表实例（instance 路径·clean 词表不打 Log）。
    private val lexicon: RelationshipLexicon by lazy {
        val f = File("src/main/assets/growth/relationship_lexicon.tsv")
        assertTrue("词表资产缺失：${f.absolutePath}", f.exists())
        RelationshipLexicon.fromRawText(f.readText())
    }

    // §3.2 锁定词→原型映射（重新打字·PITFALLS §1e）。
    private val locked: List<Pair<String, String>> = listOf(
        "陌生人" to "STRANGER", "点头之交" to "ACQUAINTANCE", "网友" to "NETFRIEND",
        "普通朋友" to "FRIEND", "朋友" to "FRIEND",
        "好朋友" to "CLOSE_FRIEND", "死党" to "CLOSE_FRIEND", "损友" to "CLOSE_FRIEND",
        "挚友" to "BEST_FRIEND", "闺蜜" to "BEST_FRIEND", "知己" to "BEST_FRIEND", "灵魂伴侣" to "BEST_FRIEND", "互相依赖" to "BEST_FRIEND",
        "暗恋中" to "CRUSH", "暗恋" to "CRUSH",
        "暧昧对象" to "AMBIGUOUS", "暧昧" to "AMBIGUOUS", "若即若离" to "AMBIGUOUS", "欢喜冤家" to "AMBIGUOUS",
        "恋人" to "LOVER", "热恋期" to "LOVER", "冷战中" to "LOVER", "复合期" to "LOVER", "相爱相杀" to "LOVER",
        "男朋友" to "LOVER", "女朋友" to "LOVER", "男友" to "LOVER", "女友" to "LOVER", "对象" to "LOVER",
        "男票" to "LOVER", "女票" to "LOVER", "爱人" to "LOVER",
        "老夫老妻" to "SPOUSE", "伴侣" to "SPOUSE", "老公" to "SPOUSE", "老婆" to "SPOUSE",
        "丈夫" to "SPOUSE", "妻子" to "SPOUSE", "未婚夫" to "SPOUSE", "未婚妻" to "SPOUSE",
        "家人" to "FAMILY", "爸爸" to "FAMILY", "妈妈" to "FAMILY", "父亲" to "FAMILY", "母亲" to "FAMILY",
        "哥哥" to "FAMILY", "姐姐" to "FAMILY", "弟弟" to "FAMILY", "妹妹" to "FAMILY", "干爹" to "FAMILY", "干妈" to "FAMILY",
        "青梅竹马" to "CHILDHOOD", "发小" to "CHILDHOOD", "竹马" to "CHILDHOOD",
        "师父" to "MENTORSHIP", "师傅" to "MENTORSHIP", "老师" to "MENTORSHIP", "师徒" to "MENTORSHIP",
        "师生" to "MENTORSHIP", "导师" to "MENTORSHIP", "前辈" to "MENTORSHIP",
        "同事" to "COLLEAGUE", "搭档" to "COLLEAGUE", "战友" to "COLLEAGUE", "上司" to "COLLEAGUE", "下属" to "COLLEAGUE", "老板" to "COLLEAGUE",
        "偶像" to "IDOL", "粉丝" to "IDOL", "爱豆" to "IDOL",
        "主人" to "SERVANT", "仆人" to "SERVANT", "主仆" to "SERVANT", "管家" to "SERVANT",
        "前任" to "EX", "前男友" to "EX", "前女友" to "EX", "前夫" to "EX", "前妻" to "EX", "藕断丝连" to "EX",
        "竞争对手" to "RIVAL", "对手" to "RIVAL", "死对头" to "RIVAL",
        "宿敌" to "NEMESIS", "仇人" to "NEMESIS", "死敌" to "NEMESIS",
        // §3.2 点名英文核心词（R1 复核代办补齐·"close friend" 经归一化去空白命中 closefriend）
        "stranger" to "STRANGER", "friend" to "FRIEND", "close friend" to "CLOSE_FRIEND",
        "best friend" to "BEST_FRIEND", "lover" to "LOVER", "partner" to "SPOUSE",
        "soulmate" to "BEST_FRIEND", "ex" to "EX", "rival" to "RIVAL", "nemesis" to "NEMESIS",
    )

    @Test fun `锁定映射逐词全过`() {
        for ((word, expected) in locked) {
            assertEquals("锁定词「$word」应解析为 $expected", expected, lexicon.resolve(word))
        }
    }

    @Test fun `E10 前任的朋友 平局异族闭嘴`() {
        assertNull(lexicon.resolve("前任的朋友"))
    }

    @Test fun `E11 前男友 最长优先命中 EX`() {
        assertEquals("EX", lexicon.resolve("前男友"))
        assertEquals("LOVER", lexicon.resolve("男友")) // 对照：男友仍是 LOVER
    }

    @Test fun `E14 归一化 大小写与空白`() {
        assertEquals("BEST_FRIEND", lexicon.resolve("Best Friend"))
        assertEquals("BEST_FRIEND", lexicon.resolve("  BESTFRIEND  "))
        assertEquals("LOVER", lexicon.resolve("恋 人"))
        assertEquals("EX", lexicon.resolve(" EX "))
    }

    @Test fun `E19 超长名分 正常判定不崩`() {
        // 50+ 字长输入（O(n²) 子串枚举·微秒级）：填充词均不在词表，唯一命中「恋人」→ LOVER。
        val long = "很久很久".repeat(10) + "以后我们终于成了恋人"
        assertTrue("长输入应 >40 字", long.length > 40)
        assertEquals("LOVER", lexicon.resolve(long))
    }

    @Test fun `E9 坏行跳过与计数`() {
        val dirty = buildString {
            append("#comment line\n")
            append("恋人\tLOVER\n")           // 合法
            append("坏行没有tab\n")             // 非法：列数≠2
            append("多\t列\tBAD\n")            // 非法：列数≠2
            append("x\tLOVER\n")               // 短词 <2（归一化后 1 字）
            append("测试词\tNO_SUCH_ARCH\n")    // 未知原型
            append("恋人\tSPOUSE\n")           // 重复词条（后者跳过）
            append("闺蜜\tBEST_FRIEND\n")      // 合法
        }
        val p = RelationshipLexicon.parseLexicon(dirty)
        assertEquals(3, p.skippedLines)       // 坏行没有tab + 多列 + 短词 x（<2）
        assertEquals(1, p.duplicateWords)     // 恋人 重复
        assertEquals(1, p.unknownArchetypes)  // NO_SUCH_ARCH
        assertEquals(mapOf("恋人" to "LOVER", "闺蜜" to "BEST_FRIEND"), p.index)
        assertEquals("LOVER", RelationshipLexicon.matchArchetypeId("恋人", p.index))
    }

    @Test fun `E9 空表降级恒 null`() {
        val p = RelationshipLexicon.parseLexicon("")
        assertTrue(p.index.isEmpty())
        assertNull(RelationshipLexicon.matchArchetypeId("恋人", p.index))
        assertNull(RelationshipLexicon.matchArchetypeId("任意名分", emptyMap()))
    }

    @Test fun `min 词长 2 - 归一化后单字返回 null`() {
        assertNull(RelationshipLexicon.matchArchetypeId("恋", lexicon.index))
        assertNull(RelationshipLexicon.matchArchetypeId("", lexicon.index))
    }

    // ── 微图纸「指纹五项加固」⑤：有效词条流摘要 ────────────────────────────────────────

    @Test fun `词条流摘要 - 确定性且注释与空行不影响`() {
        val a = RelationshipLexicon.parseLexicon("恋人\tLOVER\n宿敌\tNEMESIS\n")
        val b = RelationshipLexicon.parseLexicon("# 注释随便改\n\n恋人\tLOVER\n# 又一条注释\n宿敌\tNEMESIS\n\n")
        assertEquals("同有效词条流应同摘要（注释/空行不进指纹）", a.entriesDigestHex, b.entriesDigestHex)
        assertEquals("确定性", a.entriesDigestHex, RelationshipLexicon.parseLexicon("恋人\tLOVER\n宿敌\tNEMESIS\n").entriesDigestHex)
        assertEquals("摘要为 64 位小写 hex", 64, a.entriesDigestHex.length)
    }

    @Test fun `词条流摘要 - 金标向量`() {
        // T5 复核 🔵-2:锁定的流字节格式(归一化词条+'\t'+原型ID+'\n' UTF-8)钉死为独立复算的 SHA-256 字面量
        // (Python 第三方算得,绝不许改——分隔符/换行/编码任何漂移都会在此翻红,防指纹静默漂移)。
        assertEquals(
            "664e712651f938673f06849f06810e68fb8d3fd66d17c30ee0cd104620aafdcb",
            RelationshipLexicon.parseLexicon("恋人\tLOVER\n").entriesDigestHex,
        )
    }

    @Test fun `词条流摘要 - 改词条或顺序则变`() {
        val base = RelationshipLexicon.parseLexicon("恋人\tLOVER\n宿敌\tNEMESIS\n").entriesDigestHex
        val entryChanged = RelationshipLexicon.parseLexicon("恋人\tLOVER\n仇人\tNEMESIS\n").entriesDigestHex
        val orderChanged = RelationshipLexicon.parseLexicon("宿敌\tNEMESIS\n恋人\tLOVER\n").entriesDigestHex
        assertTrue("改词条应变摘要", base != entryChanged)
        assertTrue("换顺序应变摘要（顺序属语义：重复词先者胜）", base != orderChanged)
    }

    @Test fun `全表体检 - 无重复无短词原型有效且≥800`() {
        val f = File("src/main/assets/growth/relationship_lexicon.tsv")
        val p = RelationshipLexicon.parseLexicon(f.readText())
        assertEquals("词表应无非法行", 0, p.skippedLines)
        assertEquals("词表应无重复词条", 0, p.duplicateWords)
        assertEquals("词表应无未知原型", 0, p.unknownArchetypes)
        assertTrue("词表条目应 ≥800，实=${p.index.size}", p.index.size >= 800)
        val validIds = RelationshipArchetype.ALL.map { it.id }.toSet()
        for ((k, v) in p.index) {
            assertTrue("词条「$k」长度<2", k.length >= 2)
            assertTrue("词条「$k」无空白", k.none { it.isWhitespace() })
            assertTrue("词条「$k」映射非法原型 $v", v in validIds)
        }
    }
}
