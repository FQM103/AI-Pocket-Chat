package com.situ.aichat.ui.world.starmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * [StarmapLayout] 确定性排布 T1（W10 图纸 §7·T1-1/2/3·E11/E12）。断言从 §3.4 五步公式独立反推——
 * 半径 330−190·clo/100、分扇 360−18C、外圈 −90+360(i+.5)/M、碰撞 64dp/±1.5°/24 轮——非照搬实现。
 */
class StarmapLayoutTest {

    private fun node(uuid: String, clo: Int) =
        StarNode(characterUuid = uuid, name = uuid, avatarPath = null, closeness = clo, milestoneTitle = null, subtitle = "")

    private fun edge(a: String, b: String) =
        StarEdge(
            pairKey = "$a|$b", aUuid = a, bUuid = b, aName = a, bName = b, aAvatarPath = null, bAvatarPath = null,
            types = listOf("相识"), trajectory = "stable", tension = 0,
            closenessForward = 0, colorForward = "", closenessReverse = 0, colorReverse = "", origin = "",
        )

    private fun pending(id: String) =
        PendingStar(nativeId = id, name = id, occupation = "", cityId = "", cityName = "", stagePhrase = "", oneLiner = "", referrerName = null)

    private fun expected(angleDeg: Double, r: Double): StarPoint {
        val rad = angleDeg * Math.PI / 180.0
        return StarPoint((cos(rad) * r).toFloat(), (sin(rad) * r).toFloat())
    }

    private fun assertPoint(exp: StarPoint, act: StarPoint, tol: Float = 0.5f, msg: String = "") {
        assertTrue("$msg x 期望 ${exp.x} 实得 ${act.x}", kotlin.math.abs(exp.x - act.x) <= tol)
        assertTrue("$msg y 期望 ${exp.y} 实得 ${act.y}", kotlin.math.abs(exp.y - act.y) <= tol)
    }

    // MARK: - T1-1 确定性（同输入两次 compute 输出逐字段相等）

    @Test
    fun `T1-1 确定性_同输入两次compute逐字段相等`() {
        val nodes = listOf(node("n3", 40), node("n1", 82), node("n2", 61))
        val edges = listOf(edge("n1", "n2"))
        val pendings = listOf(pending("p2"), pending("p1"))
        val g1 = StarmapLayout.compute(nodes, edges, pendings)
        val g2 = StarmapLayout.compute(nodes, edges, pendings)
        assertEquals(g1, g2)
    }

    // MARK: - T1-2 碰撞（8 节点同簇近亲密度·任两心距 ≥64dp·E11）

    @Test
    fun `T1-2 碰撞_8节点同簇近亲密度_任两心距不小于64`() {
        val closes = intArrayOf(58, 57, 56, 55, 54, 53, 52, 51)
        val nodes = closes.mapIndexed { i, c -> node("u%d".format(i), c) }
        // 链式连边 → 单连通分量（同簇）。
        val edges = (0 until nodes.size - 1).map { edge(nodes[it].characterUuid, nodes[it + 1].characterUuid) }
        val g = StarmapLayout.compute(nodes, edges, emptyList())
        assertEquals(8, g.nodes.size)
        for (i in g.nodes.indices) for (k in i + 1 until g.nodes.size) {
            val d = hypot((g.nodes[i].pos.x - g.nodes[k].pos.x).toDouble(), (g.nodes[i].pos.y - g.nodes[k].pos.y).toDouble())
            assertTrue("节点 $i,$k 心距 $d 应 ≥64", d >= 64.0)
        }
    }

    // MARK: - T1-3 半径映射锁值

    @Test
    fun `T1-3a 半径映射锁值_0_35_70_100`() {
        assertEquals(330f, StarmapLayout.radiusOf(0))
        assertEquals(264f, StarmapLayout.radiusOf(35))
        assertEquals(197f, StarmapLayout.radiusOf(70))
        assertEquals(140f, StarmapLayout.radiusOf(100))
    }

    // MARK: - T1-3 分扇（两簇 3+2·起角/扇宽/簇内角按 §3.4 手算）

    @Test
    fun `T1-3b 分扇_两簇3加2_节点落位按公式`() {
        // 簇 A（3 节点·closeness 60/50/40）链连；簇 B（2 节点·30/20）连。簇序 A(3) 先、B(2) 后。
        val a1 = node("a1", 60); val a2 = node("a2", 50); val a3 = node("a3", 40)
        val b1 = node("b1", 30); val b2 = node("b2", 20)
        val nodes = listOf(a3, b2, a1, b1, a2) // 乱序喂入·验算法自排
        val edges = listOf(edge("a1", "a2"), edge("a2", "a3"), edge("b1", "b2"))
        val g = StarmapLayout.compute(nodes, edges, emptyList())
        val pos = g.nodes.associateBy { it.characterUuid }
        // available=360-18*2=324；A 宽=324*3/5=194.4 起 -90；B 宽=324*2/5=129.6 起 -90+194.4+18=122.4
        assertPoint(expected(-57.6, 216.0), pos.getValue("a1").pos, msg = "a1")   // -90+194.4*0.5/3 · r=330-114
        assertPoint(expected(7.2, 235.0), pos.getValue("a2").pos, msg = "a2")     // -90+194.4*1.5/3 · r=330-95
        assertPoint(expected(72.0, 254.0), pos.getValue("a3").pos, msg = "a3")    // -90+194.4*2.5/3 · r=330-76
        assertPoint(expected(154.8, 273.0), pos.getValue("b1").pos, msg = "b1")   // 122.4+129.6*0.5/2 · r=330-57
        assertPoint(expected(219.6, 292.0), pos.getValue("b2").pos, msg = "b2")   // 122.4+129.6*1.5/2 · r=330-38
    }

    // MARK: - T1-3 外圈（待相识 M=3·角 -30/90/210·半径错层 436/456/436）

    @Test
    fun `T1-3c 待相识外圈_M3_角与错层半径`() {
        val g = StarmapLayout.compute(listOf(node("n1", 50)), emptyList(), listOf(pending("p3"), pending("p1"), pending("p2")))
        val pos = g.pendings.associateBy { it.nativeId }
        assertPoint(expected(-30.0, 436.0), pos.getValue("p1").pos, msg = "p1")   // i=0 偶 436
        assertPoint(expected(90.0, 456.0), pos.getValue("p2").pos, msg = "p2")    // i=1 奇 456
        assertPoint(expected(210.0, 436.0), pos.getValue("p3").pos, msg = "p3")   // i=2 偶 436
    }

    // MARK: - E12 大数据量（24 角色 + 20 待相识·排布终止不死循环·计数正确）

    @Test
    fun `E12 大数据量_24角色20待相识_终止且计数正确`() {
        val nodes = (0 until 24).map { node("c%02d".format(it), 90 - it % 40) } // 多数高亲密（近中心·逼碰撞）
        val edges = (0 until 23).map { edge(nodes[it].characterUuid, nodes[it + 1].characterUuid) }
        val pendings = (0 until 20).map { pending("p%02d".format(it)) }
        val g = StarmapLayout.compute(nodes, edges, pendings)
        assertEquals(24, g.nodes.size)
        assertEquals(20, g.pendings.size)
        assertEquals(23, g.edges.size)
        // 边端点已回填到节点坐标（非原点）。
        val nodePos = g.nodes.associate { it.characterUuid to it.pos }
        for (e in g.edges) {
            assertEquals(nodePos.getValue(e.aUuid), e.aPos)
            assertEquals(nodePos.getValue(e.bUuid), e.bPos)
        }
    }
}
