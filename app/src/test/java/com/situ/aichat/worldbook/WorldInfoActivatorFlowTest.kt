package com.situ.aichat.worldbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 激活引擎流程 T1（WB3·契约 §4.2 第 6–8 步）：递归连锁与层级 / 扩窗 / 预算裁剪 / 分桶映射与顺序。
 */
class WorldInfoActivatorFlowTest {

    private val recursive = WorldInfoSettings(recursiveScan = true)

    private fun chain() = listOf(
        wbEntry("A", keys = listOf("起点"), content = "这事和中继站有关"),
        wbEntry("B", keys = listOf("中继站"), content = "中继站背后是终焉塔"),
        wbEntry("C", keys = listOf("终焉塔"), content = "塔顶封印着魔王"),
    )

    @Test
    fun 递归链_三级连锁全点亮() {
        val r = activate(wbInput(chain(), listOf("我们聊聊起点"), settings = recursive))
        assertEquals(setOf("A", "B", "C"), r.activatedTitles())
        assertTrue("至少主扫一轮+递归两轮", r.diagnostics.sweepCount >= 3)
    }

    @Test
    fun 递归关闭_只有主扫命中() {
        val r = activate(wbInput(chain(), listOf("我们聊聊起点")))
        assertEquals(setOf("A"), r.activatedTitles())
    }

    @Test
    fun preventRecursion_内容不去点火() {
        val entries = listOf(
            wbEntry("A", keys = listOf("起点"), content = "这事和中继站有关", preventRecursion = true),
            wbEntry("B", keys = listOf("中继站"), content = "不该出现"),
        )
        val r = activate(wbInput(entries, listOf("我们聊聊起点"), settings = recursive))
        assertEquals(setOf("A"), r.activatedTitles())
    }

    @Test
    fun excludeRecursion_递归点不着_主扫可点() {
        val entries = listOf(
            wbEntry("A", keys = listOf("起点"), content = "这事和中继站有关"),
            wbEntry("B1", keys = listOf("中继站"), excludeRecursion = true, content = "递归点不着我"),
            wbEntry("B2", keys = listOf("起点"), excludeRecursion = true, content = "主扫点得着我"),
        )
        val r = activate(wbInput(entries, listOf("我们聊聊起点"), settings = recursive))
        assertEquals(setOf("A", "B2"), r.activatedTitles())
    }

    @Test
    fun delayUntilRecursion_只在递归轮参战() {
        val entries = listOf(wbEntry("D", keys = listOf("起点"), delayUntilRecursion = 1))
        assertEquals(setOf("D"), activate(wbInput(entries, listOf("聊聊起点"), settings = recursive)).activatedTitles())
        assertTrue("递归关着就永远轮不到它", activate(wbInput(entries, listOf("聊聊起点"))).isEmpty)
    }

    @Test
    fun delayUntilRecursion_层级干涸后依次解锁() {
        val entries = listOf(
            wbEntry("A", keys = listOf("起点")),
            wbEntry("C2", keys = listOf("起点"), delayUntilRecursion = 2),
        )
        val r = activate(wbInput(entries, listOf("聊聊起点"), settings = recursive))
        assertEquals("一层干涸后应解锁第二层", setOf("A", "C2"), r.activatedTitles())
    }

    @Test
    fun maxRecursionSteps_链条按步数止步() {
        val r = activate(
            wbInput(chain(), listOf("我们聊聊起点"), settings = recursive.copy(maxRecursionSteps = 1)),
        )
        assertEquals("只许递归一步:C 不该点亮", setOf("A", "B"), r.activatedTitles())
    }

    @Test
    fun minActivations_扩窗捞到更早消息() {
        val messages = listOf("藏着秘银的矿洞", "闲聊一", "闲聊二", "闲聊三")
        val entries = listOf(wbEntry("e1", keys = listOf("秘银")))
        assertTrue("默认深度2看不到最老那条", activate(wbInput(entries, messages)).isEmpty)
        val r = activate(wbInput(entries, messages, settings = WorldInfoSettings(minActivations = 1)))
        assertEquals(setOf("e1"), r.activatedTitles())
        assertTrue(r.diagnostics.sweepCount >= 2)
    }

    @Test
    fun minActivations_受maxDepth上限止步() {
        val messages = listOf("藏着秘银的矿洞", "闲聊一", "闲聊二", "闲聊三")
        val entries = listOf(wbEntry("e1", keys = listOf("秘银")))
        val r = activate(
            wbInput(entries, messages, settings = WorldInfoSettings(minActivations = 1, minActivationsMaxDepth = 3)),
        )
        assertTrue("上限3回溯不到第4条", r.isEmpty)
    }

    @Test
    fun 预算_高order优先保_低order裁掉进诊断() {
        val entries = listOf(
            wbEntry("低", keys = listOf("触发"), order = 100, content = "a".repeat(10)),
            wbEntry("高", keys = listOf("触发"), order = 300, content = "b".repeat(10)),
            wbEntry("中", keys = listOf("触发"), order = 200, content = "c".repeat(10)),
        )
        val r = activate(wbInput(entries, listOf("触发"), settings = WorldInfoSettings(budgetChars = 25)))
        assertEquals(setOf("高", "中"), r.activatedTitles())
        assertEquals(setOf("低"), r.droppedTitles())
    }

    @Test
    fun 预算_常驻优先保_ignoreBudget永不裁() {
        val entries = listOf(
            wbEntry("普通高序", keys = listOf("触发"), order = 999, content = "a".repeat(10)),
            wbEntry("常驻", constant = true, order = 1, content = "b".repeat(10)),
        )
        val r = activate(wbInput(entries, listOf("触发"), settings = WorldInfoSettings(budgetChars = 12)))
        assertEquals("常驻先占预算", setOf("常驻"), r.activatedTitles())
        assertEquals(setOf("普通高序"), r.droppedTitles())

        val r2 = activate(
            wbInput(listOf(wbEntry("硬插", constant = false, keys = listOf("触发"), ignoreBudget = true, content = "c".repeat(10))), listOf("触发"), settings = WorldInfoSettings(budgetChars = 0)),
        )
        assertEquals("ignoreBudget 预算为0也保", setOf("硬插"), r2.activatedTitles())
    }

    @Test
    fun 分桶_位置映射全表() {
        val entries = listOf(
            wbEntry("p0", constant = true, position = 0, content = "c0"),
            wbEntry("p1", constant = true, position = 1, order = 1, content = "c1"),
            wbEntry("p2", constant = true, position = 2, order = 1, content = "c2"),
            wbEntry("p3", constant = true, position = 3, order = 2, content = "c3"),
            wbEntry("p4", constant = true, position = 4, depth = 6, role = 1, content = "c4"),
            wbEntry("p5", constant = true, position = 5, order = 2, content = "c5"),
            wbEntry("p6", constant = true, position = 6, order = 3, content = "c6"),
        )
        val r = activate(wbInput(entries, listOf("随便")))
        assertEquals("c0", r.before)
        assertEquals("c1\nc5\nc6", r.after)
        assertEquals("c2\nc3", r.suffix)
        assertEquals(listOf(AtDepthInjection(depth = 6, role = 1, content = "c4")), r.atDepth)
    }

    @Test
    fun 桶内order升序_大order靠后更强() {
        val entries = listOf(
            wbEntry("强", constant = true, order = 200, content = "强设定"),
            wbEntry("弱", constant = true, order = 10, content = "弱设定"),
        )
        assertEquals("弱设定\n强设定", activate(wbInput(entries, listOf("随便"))).after)
    }

    @Test
    fun atDepth_同深同角色合并_不同深度降序分条() {
        val entries = listOf(
            wbEntry("甲", constant = true, position = 4, depth = 3, role = 2, order = 1, content = "甲文"),
            wbEntry("乙", constant = true, position = 4, depth = 3, role = 2, order = 2, content = "乙文"),
            wbEntry("丙", constant = true, position = 4, depth = 5, role = 0, content = "丙文"),
        )
        val r = activate(wbInput(entries, listOf("随便")))
        assertEquals(
            listOf(
                AtDepthInjection(depth = 5, role = 0, content = "丙文"),
                AtDepthInjection(depth = 3, role = 2, content = "甲文\n乙文"),
            ),
            r.atDepth,
        )
    }

    @Test
    fun 插入策略_平手时角色书条目更靠后() {
        val books = listOf(wbBook("b1"), wbBook("b2", global = true))
        val entries = listOf(
            wbEntry("角", book = "b1", constant = true, content = "角色设定"),
            wbEntry("全", book = "b2", constant = true, content = "全局设定"),
        )
        val charFirst = activate(
            wbInput(entries, listOf("随便"), books = books, settings = WorldInfoSettings(insertionStrategy = WorldInfoInsertionStrategy.CHARACTER_FIRST)),
        )
        assertEquals("全局设定\n角色设定", charFirst.after)
        val globalFirst = activate(
            wbInput(entries, listOf("随便"), books = books, settings = WorldInfoSettings(insertionStrategy = WorldInfoInsertionStrategy.GLOBAL_FIRST)),
        )
        assertEquals("角色设定\n全局设定", globalFirst.after)
    }
}
