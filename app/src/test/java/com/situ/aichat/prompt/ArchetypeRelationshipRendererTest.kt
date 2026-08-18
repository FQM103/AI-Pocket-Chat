package com.situ.aichat.prompt

import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.prompt.growth.Band
import com.situ.aichat.prompt.growth.RelationshipArchetype
import com.situ.aichat.prompt.growth.ScriptFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-3（图纸 §7）：读侧二维渲染 [buildArchetypeRelationshipDescription] / [bandFor] +
 * 台词库 [RelationshipBehaviorScripts] 240 格无空、每条 16–48 字、温暖族 L1 禁语扫描、样例 33 条逐字 pin。
 *
 * 样例 33 条为**重新打字的字面量**（PITFALLS §1e：不引用实现常量，防自证）。
 */
class ArchetypeRelationshipRendererTest {

    private fun byId(id: String) = RelationshipArchetype.byId(id)!!
    private fun qualityOf(vararg v: Int) = RelationshipQuality(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])

    // ── bandFor 边界（±1 精度·D-11 阈值 0.30 / 0.65 / 0.90）─────────────────────────
    @Test fun `bandFor 分档边界`() {
        assertEquals(Band.L1, bandFor(0f))
        assertEquals(Band.L1, bandFor(0.29f))
        assertNull(bandFor(0.30f))          // 静默起点（含 0.30）
        assertNull(bandFor(0.64f))
        assertEquals(Band.L3, bandFor(0.65f))
        assertEquals(Band.L3, bandFor(0.89f))
        assertEquals(Band.L4, bandFor(0.90f))
        assertEquals(Band.L4, bandFor(1f))
    }

    // ── E3/E4 LOVER 播种态 → 8 条 L1、含患得患失、无生疏/事务性 ─────────────────────
    @Test fun `E3E4 LOVER 播种态渲染 8 条 L1 含患得患失无禁语`() {
        val lover = byId("LOVER")
        val q = qualityOf(*lover.floors) // 全维在地板 → t=0 → L1
        val out = buildArchetypeRelationshipDescription(q, lover, "小明")
        val lines = out.split("\n")
        assertEquals("你和小明的互动方式：", lines[0])
        assertEquals(8, lines.size - 1) // 段头 + 8 条
        assertTrue(out.contains("患得患失"))
        assertFalse(out.contains("生疏"))
        assertFalse(out.contains("事务性"))
    }

    // ── 静默档整维缺席 ──────────────────────────────────────────────────────────────
    @Test fun `静默档整维缺席`() {
        val friend = byId("FRIEND") // floors 熟35 信30 亲25 默25 尊35 趣30 张0 依0
        // closeness 抬到静默带：floor25 hi100 → t=(48-25)/75≈0.307 ∈[0.30,0.65)
        val q = qualityOf(35, 30, 48, 25, 35, 30, 0, 0)
        val out = buildArchetypeRelationshipDescription(q, friend, "小明")
        assertFalse("closeness 静默应缺席", out.contains("铺垫期")) // FRIEND closeness L1 独有词
        assertEquals(7, out.split("\n").size - 1) // 8 维少 1 = 7 条
    }

    // ── D-4 读侧：EX 信任 80 按天花板 45 → 满格 L4 ──────────────────────────────────
    @Test fun `EX 信任80 按天花板渲染满格`() {
        val ex = byId("EX") // trust floor15 ceil45
        val q = qualityOf(60, 80, 10, 35, 20, 5, 0, 0)
        val out = buildArchetypeRelationshipDescription(q, ex, "小明")
        assertTrue("EX 信任应渲 L4", out.contains("竟没全散")) // EX trust L4 独有词
    }

    // ── 全静默 → "" ────────────────────────────────────────────────────────────────
    @Test fun `全维静默返回空串`() {
        val friend = byId("FRIEND")
        // 每维抬到静默带（t∈[0.30,0.65)）：floor + 0.4*(100-floor)
        val q = qualityOf(61, 58, 55, 55, 61, 58, 40, 40)
        val out = buildArchetypeRelationshipDescription(q, friend, "小明")
        assertEquals("", out)
    }

    // ── 台词库 240 格无空 + 16–48 字 + 「- 」起句 ────────────────────────────────────
    @Test fun `台词库 240 格全覆盖且格式合规`() {
        var count = 0
        for (fam in ScriptFamily.values()) {
            for (dim in RelationshipQuality.DIMENSION_KEYS) {
                for (band in Band.values()) {
                    val t = RelationshipBehaviorScripts.textFor(fam, dim, band)
                    assertTrue("$fam/$dim/$band 空", t.isNotEmpty())
                    assertTrue("$fam/$dim/$band 长度=${t.length}", t.length in 16..48)
                    assertTrue("$fam/$dim/$band 未以「- 」起句", t.startsWith("- "))
                    count++
                }
            }
        }
        assertEquals(240, count)
    }

    // ── 温暖族 L1 禁语扫描（§4.2#3）──────────────────────────────────────────────────
    @Test fun `温暖族 L1 禁语扫描`() {
        val warm = setOf(ScriptFamily.FRIENDS, ScriptFamily.CONFIDANTS, ScriptFamily.FLUTTER, ScriptFamily.ROMANCE, ScriptFamily.KIN, ScriptFamily.MENTORS)
        val banned = listOf("生疏", "冷淡", "事务性", "沉闷", "缺乏")
        for (fam in warm) {
            for (dim in RelationshipQuality.DIMENSION_KEYS) {
                val l1 = RelationshipBehaviorScripts.textFor(fam, dim, Band.L1)
                for (w in banned) assertFalse("$fam/$dim/L1 含禁语「$w」：$l1", l1.contains(w))
            }
        }
    }

    // ── 样例 33 条逐字 pin（§4.3）：十族亲近三档 + 恋人信任三档 ────────────────────────
    @Test fun `样例 33 条逐字 pin`() {
        fun c(fam: ScriptFamily, band: Band) = RelationshipBehaviorScripts.textFor(fam, "closeness", band)
        // 陌路
        assertEquals("- 你们之间还没有什么情感连接：互动客气而表面，你不会主动过问ta的私事", c(ScriptFamily.STRANGERS, Band.L1))
        assertEquals("- 才认识不久，你们却意外聊得来：你开始愿意多说几句自己的事", c(ScriptFamily.STRANGERS, Band.L3))
        assertEquals("- 你们一见如故：明明是新认识的人，你却有种莫名的亲切感，想多了解ta", c(ScriptFamily.STRANGERS, Band.L4))
        // 浅交
        assertEquals("- 你们的交情还停在寒暄：偶尔搭话，不会深聊", c(ScriptFamily.CASUAL, Band.L1))
        assertEquals("- 你们比一般泛泛之交走得近些：你开始留意ta说过的事", c(ScriptFamily.CASUAL, Band.L3))
        assertEquals("- 以你们的交情来说，你对ta的好感明显超出寻常：你会主动找ta说话，想把关系再拉近一步", c(ScriptFamily.CASUAL, Band.L4))
        // 朋友
        assertEquals("- 你们的朋友关系还在铺垫期：聊得来，但你还不会跟ta交底", c(ScriptFamily.FRIENDS, Band.L1))
        assertEquals("- 你们是能交心的朋友：你会自然地关心ta的生活，有好玩的事第一批想到ta", c(ScriptFamily.FRIENDS, Band.L3))
        assertEquals("- 在朋友里ta对你是特别的那一个：几乎无话不说，这份友情已经在往更深处走", c(ScriptFamily.FRIENDS, Band.L4))
        // 挚友
        assertEquals("- 虽是无话不谈的交情，眼下你们的亲近偏淡：你惦记ta，但说心里话前会先掂量一下", c(ScriptFamily.CONFIDANTS, Band.L1))
        assertEquals("- 你们亲近得像家人：可以撒娇可以任性，ta面前你不用装", c(ScriptFamily.CONFIDANTS, Band.L3))
        assertEquals("- ta是你在这世上最亲近的人之一：任何真实的、脆弱的话你都敢对ta说", c(ScriptFamily.CONFIDANTS, Band.L4))
        // 心动
        assertEquals("- 心动归心动，你们还没真正靠近：你想接近ta，又不敢表现得太明显", c(ScriptFamily.FLUTTER, Band.L1))
        assertEquals("- 你们越走越近，那层窗户纸越来越薄：聊天时的亲昵已经超过普通朋友", c(ScriptFamily.FLUTTER, Band.L3))
        assertEquals("- 你们亲近得几乎不设防，只差一句挑明：每次互动都带着心照不宣的暧昧", c(ScriptFamily.FLUTTER, Band.L4))
        // 恋人
        assertEquals("- 你们刚确定关系不久，亲密还在升温：想靠近又还有点小心翼翼，分寸感没完全放开", c(ScriptFamily.ROMANCE, Band.L1))
        assertEquals("- 你们是亲密自然的恋人：可以撒娇、可以任性、可以把最真实的情绪交给对方", c(ScriptFamily.ROMANCE, Band.L3))
        assertEquals("- ta是你最亲密无间的人：你们之间几乎没有秘密，在ta面前你可以完全做自己", c(ScriptFamily.ROMANCE, Band.L4))
        // 家人
        assertEquals("- 你们是家人，但情感上隔着一点距离：关心藏在心里，嘴上不太说", c(ScriptFamily.KIN, Band.L1))
        assertEquals("- 你们是感情很好的家人：关心得自然而然，唠叨也是爱的一种", c(ScriptFamily.KIN, Band.L3))
        assertEquals("- 你们是彼此最坚实的依靠：这种亲近不用言说，遇到事第一个想到的就是对方", c(ScriptFamily.KIN, Band.L4))
        // 师长
        assertEquals("- 你们的关系敬多于亲：交流围绕正事，私下的话不多", c(ScriptFamily.MENTORS, Band.L1))
        assertEquals("- 敬重之外你们也有了私交：偶尔聊些正事之外的话题", c(ScriptFamily.MENTORS, Band.L3))
        assertEquals("- 你们亦师亦友、情同家人：那层严肃的壳早就化了，ta是你真心亲近的人", c(ScriptFamily.MENTORS, Band.L4))
        // 前任
        assertEquals("- 结束就是结束了：你们保持着礼貌的距离，不再过问彼此的生活", c(ScriptFamily.EXES, Band.L1))
        assertEquals("- 分开了，但你们之间还留着旧日的温度：偶尔的关心里藏着说不清的情绪", c(ScriptFamily.EXES, Band.L3))
        assertEquals("- 明明已是前任，你们的亲近却没断干净：藕断丝连，越界的关心时不时冒出来", c(ScriptFamily.EXES, Band.L4))
        // 宿敌
        assertEquals("- 你们水火不容：没有温情可言，交锋就是你们全部的往来", c(ScriptFamily.FOES, Band.L1))
        assertEquals("- 敌意之下竟长出些别的东西：你开始在意ta的处境，虽然你绝不承认", c(ScriptFamily.FOES, Band.L3))
        assertEquals("- 你们的纠缠早已越过单纯的敌对：这个死敌，竟成了你放不下的牵挂", c(ScriptFamily.FOES, Band.L4))
        // 恋人·信任三档（修正③展示）
        fun tr(band: Band) = RelationshipBehaviorScripts.textFor(ScriptFamily.ROMANCE, "trust", band)
        assertEquals("- 你们是恋人，但你对ta的信任还没跟上感情：你会试探、会多想，有点患得患失", tr(Band.L1))
        assertEquals("- 你信任ta：烦恼和心事愿意主动讲给ta听，ta说错话你也倾向往好处想", tr(Band.L3))
        assertEquals("- ta是你最信任的人：再脆弱、再难堪的事你都敢讲，你笃定ta不会伤害你", tr(Band.L4))
    }
}
