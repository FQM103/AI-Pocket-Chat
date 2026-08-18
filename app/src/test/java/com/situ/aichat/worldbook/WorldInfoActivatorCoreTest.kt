package com.situ.aichat.worldbook

import com.situ.aichat.data.local.entity.WorldBookTimedStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 激活引擎核心门 T1（WB3·契约 §4.2 第 1–5 步）：三色灯 / 概率 / 时效三件套 / 向量 / outlet / 分组决胜。
 */
class WorldInfoActivatorCoreTest {

    @Test
    fun 蓝灯常驻_无关键词直接激活() {
        val r = activate(wbInput(listOf(wbEntry("e1", constant = true, content = "核心规则")), listOf("随便聊聊")))
        assertEquals("核心规则", r.after)
    }

    @Test
    fun 绿灯_命中激活_未命中不激活() {
        val entries = listOf(
            wbEntry("hit", keys = listOf("青云宗"), content = "门派设定"),
            wbEntry("miss", keys = listOf("彼岸花"), content = "不该出现"),
        )
        val r = activate(wbInput(entries, listOf("聊聊青云宗")))
        assertEquals(setOf("hit"), r.activatedTitles())
        assertEquals("门派设定", r.after)
    }

    @Test
    fun 条目停用_不激活() {
        val r = activate(wbInput(listOf(wbEntry("e1", keys = listOf("青云宗"), enabled = false)), listOf("聊聊青云宗")))
        assertTrue(r.isEmpty)
    }

    @Test
    fun 书停用_整本不激活() {
        val r = activate(
            wbInput(
                listOf(wbEntry("e1", keys = listOf("青云宗"))),
                listOf("聊聊青云宗"),
                books = listOf(wbBook("b1", enabled = false)),
            ),
        )
        assertTrue(r.isEmpty)
    }

    @Test
    fun 概率_零永不过_百恒过() {
        val entries = listOf(
            wbEntry("p0", keys = listOf("青云宗"), probability = 0),
            wbEntry("p100", keys = listOf("青云宗"), probability = 100),
        )
        val r = activate(wbInput(entries, listOf("聊聊青云宗")))
        assertEquals(setOf("p100"), r.activatedTitles())
    }

    @Test
    fun 概率_掷骰边界照概率值() {
        val input = wbInput(listOf(wbEntry("p50", keys = listOf("青云宗"), probability = 50)), listOf("聊聊青云宗"))
        assertEquals(setOf("p50"), activate(input, rngConst(49)).activatedTitles())
        assertTrue(activate(input, rngConst(50)).isEmpty)
    }

    @Test
    fun 概率掷输_本轮递归也不重掷() {
        // 首掷必输、后续掷必赢的随机源——若引擎在递归轮重掷,B 就会被错误激活
        var calls = 0
        val rng = WorldInfoRng { bound -> (if (calls == 0) bound - 1 else 0).also { calls++ } }
        val entries = listOf(
            wbEntry("B", keys = listOf("起点", "中继站"), probability = 50, content = "不该出现"),
            wbEntry("A", keys = listOf("起点"), content = "这事和中继站有关"),
        )
        val r = activate(wbInput(entries, listOf("聊聊起点"), settings = WorldInfoSettings(recursiveScan = true)), rng)
        assertEquals(setOf("A"), r.activatedTitles())
        assertEquals("掷骰应只发生一次", 1, calls)
    }

    @Test
    fun delay门_消息数不足挡住_到数放行() {
        val entries = listOf(wbEntry("e1", keys = listOf("青云宗"), delay = 5))
        assertTrue(activate(wbInput(entries, listOf("聊聊青云宗"), messageCount = 3)).isEmpty)
        assertEquals(setOf("e1"), activate(wbInput(entries, listOf("聊聊青云宗"), messageCount = 5)).activatedTitles())
    }

    @Test
    fun 触发产时效状态_冷却接在保持窗之后() {
        val r = activate(
            wbInput(listOf(wbEntry("e1", keys = listOf("青云宗"), sticky = 3, cooldown = 2)), listOf("聊聊青云宗"), messageCount = 10),
        )
        val sticky = r.newTimedStates.single { it.effectType == "sticky" }
        val cooldown = r.newTimedStates.single { it.effectType == "cooldown" }
        assertEquals(10, sticky.triggeredAtMessageCount)
        assertEquals(3, sticky.durationMessages)
        assertEquals("冷却从保持结束后起算(ST 语义)", 13, cooldown.triggeredAtMessageCount)
        assertEquals(2, cooldown.durationMessages)
    }

    @Test
    fun sticky保持期_不命中也续贴_且跳过概率不重产状态() {
        val entries = listOf(wbEntry("e1", keys = listOf("不会出现的词"), probability = 0, content = "贴着", sticky = 3))
        val r = activate(
            wbInput(
                entries,
                listOf("完全无关的话"),
                messageCount = 10,
                timed = listOf(WorldBookTimedStateEntity("conv1", "e1", "sticky", 8, 3)),
            ),
        )
        assertEquals("保持窗内(10<8+3)须无条件续贴、概率0也不拦", "贴着", r.after)
        assertTrue("续贴不得重复产时效状态", r.newTimedStates.isEmpty())
    }

    @Test
    fun cooldown期内_命中也不激活() {
        val r = activate(
            wbInput(
                listOf(wbEntry("e1", keys = listOf("青云宗"))),
                listOf("聊聊青云宗"),
                messageCount = 10,
                timed = listOf(WorldBookTimedStateEntity("conv1", "e1", "cooldown", 8, 5)),
            ),
        )
        assertTrue("冷却窗内(10<8+5)不可触发", r.isEmpty)
    }

    @Test
    fun 时效过期_进清理清单且不再生效() {
        val stale = WorldBookTimedStateEntity("conv1", "e1", "sticky", 5, 3)
        val r = activate(
            wbInput(listOf(wbEntry("e1", keys = listOf("不会出现的词"))), listOf("无关"), messageCount = 10, timed = listOf(stale)),
        )
        assertEquals(listOf(stale), r.expiredTimedStates)
        assertTrue("过期 sticky 不得再续贴", r.isEmpty)
    }

    @Test
    fun 向量条目_只认向量命中_关键词无效() {
        val entries = listOf(wbEntry("e1", keys = listOf("青云宗"), vectorized = true, content = "向量设定"))
        assertTrue("链接条目不走关键词", activate(wbInput(entries, listOf("聊聊青云宗"))).isEmpty)
        val r = activate(wbInput(entries, listOf("完全无关"), vector = setOf("e1")))
        assertEquals("向量命中即激活", "向量设定", r.after)
    }

    @Test
    fun outlet位置7_不注入且计数() {
        val r = activate(wbInput(listOf(wbEntry("e1", constant = true, position = 7)), listOf("随便")))
        assertTrue(r.isEmpty)
        assertEquals(1, r.diagnostics.outletSkippedCount)
    }

    @Test
    fun 分组_权重抽签_随机源决定胜者() {
        val entries = listOf(
            wbEntry("e1", keys = listOf("青云宗"), groupName = "g", groupWeight = 100),
            wbEntry("e2", keys = listOf("青云宗"), groupName = "g", groupWeight = 300),
        )
        val input = wbInput(entries, listOf("聊聊青云宗"))
        assertEquals(setOf("e1"), activate(input, rngConst(0)).activatedTitles())
        assertEquals(setOf("e2"), activate(input, rngConst(100)).activatedTitles())
    }

    @Test
    fun 分组_override优先胜出() {
        val entries = listOf(
            wbEntry("e1", keys = listOf("青云宗"), groupName = "g", groupWeight = 999),
            wbEntry("e2", keys = listOf("青云宗"), groupName = "g", groupWeight = 1, groupOverride = true),
        )
        assertEquals(setOf("e2"), activate(wbInput(entries, listOf("聊聊青云宗")), rngConst(0)).activatedTitles())
    }

    @Test
    fun 分组_评分开启_命中数多者胜() {
        val entries = listOf(
            wbEntry("单中", keys = listOf("甲"), groupName = "g"),
            wbEntry("双中", keys = listOf("甲", "乙"), groupName = "g"),
        )
        val r = activate(
            wbInput(entries, listOf("甲 和 乙 都在"), settings = WorldInfoSettings(useGroupScoring = true)),
        )
        assertEquals(setOf("双中"), r.activatedTitles())
    }

    @Test
    fun 分组_组内已有激活者_后到者出局() {
        val entries = listOf(
            wbEntry("A", constant = true, groupName = "g", content = "这事和中继站有关"),
            wbEntry("B", keys = listOf("中继站"), groupName = "g", content = "不该出现"),
        )
        val r = activate(wbInput(entries, listOf("随便"), settings = WorldInfoSettings(recursiveScan = true)))
        assertEquals("递归轮点着的 B 与既有激活 A 同组,必须出局", setOf("A"), r.activatedTitles())
    }
}
