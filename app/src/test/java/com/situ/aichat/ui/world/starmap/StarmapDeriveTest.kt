package com.situ.aichat.ui.world.starmap

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.cast.WorldNativeRoster
import com.situ.aichat.world.social.WorldRelationshipBeats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [StarmapDerive] 纯派生 T2 主证（W10 图纸 §7·E1/E2/E3/E4/E5/E6/E7/E13）——确定性、无调度器。VM 的
 * flow 接线另见 [StarmapViewModelTest]；断言从 §3.2 派生规则独立反推（端点丢弃 / 类型并集 / 引荐声明序 / 近事窗）。
 */
class StarmapDeriveTest {

    private val seed = 42L
    private val label = "你创建的角色"
    private val zone: ZoneId = ZoneOffset.UTC

    private fun char(uuid: String, name: String = uuid, joined: Boolean = true) =
        CharacterEntity(uuid = uuid, name = name, creationDate = 0L, joinedWorld = joined, worldHomeCityId = WorldIds.HOME_CITY_ID)

    private fun edge(from: String, to: String, types: String = """["相识"]""", clo: Int = 30, tension: Int = 0, color: String = "投缘", traj: String = "stable", dormant: Boolean = false) =
        WorldRelationshipEntity(fromId = from, toId = to, typesJson = types, closeness = clo, trust = clo, tension = tension, colorRaw = color, trajectoryRaw = traj, dormant = dormant, updatedAt = 0L)

    private fun graphOf(edges: List<WorldRelationshipEntity>, chars: List<CharacterEntity>, natives: List<WorldNativeStateEntity> = emptyList(), milestones: List<MilestoneEntity> = emptyList()) =
        StarmapDerive.buildGraph(edges, chars, milestones, natives, seed, label)

    // MARK: - E1 空世界

    @Test
    fun `E1 空世界_图全空`() {
        val g = graphOf(emptyList(), listOf(char("c1", joined = false)))
        assertTrue(g.isEmpty)
        assertTrue(g.nodes.isEmpty() && g.edges.isEmpty() && g.pendings.isEmpty())
    }

    // MARK: - E2 有节点无边

    @Test
    fun `E2 有节点无边_节点在_显示边空_人物卡无来往`() {
        val g = graphOf(emptyList(), listOf(char("a"), char("b")))
        assertEquals(2, g.nodes.size)
        assertTrue(g.edges.isEmpty())
        assertTrue("TA 的来往应空", StarmapDerive.nodeCard(g.nodes.first(), g).rows.isEmpty())
    }

    // MARK: - E3 脏端点（对端不在节点集 → 边丢弃）

    @Test
    fun `E3 端点不在节点集_显示边静默丢弃`() {
        // a 加入世界、ghost 未加入 → a↔ghost 两向边端点缺失 → 丢弃；a↔b 正常保留。
        val edges = listOf(
            edge("a", "ghost"), edge("ghost", "a"),
            edge("a", "b"), edge("b", "a"),
        )
        val g = graphOf(edges, listOf(char("a"), char("b")))
        assertEquals("只余 a|b 一条", 1, g.edges.size)
        assertEquals(WorldIds.pairKey("a", "b"), g.edges.first().pairKey)
    }

    // MARK: - E5 类型降级 + 类型并集保序

    @Test
    fun `E5 空typesJson降级相识_并集保序`() {
        val g = graphOf(listOf(edge("a", "b", types = ""), edge("b", "a", types = "")), listOf(char("a"), char("b")))
        assertEquals(listOf("相识"), g.edges.first().types)

        // a→b ["相识","朋友"]，b→a ["邻里"] → 并集保 a 向序 + b 向独有尾部。
        val g2 = graphOf(
            listOf(edge("a", "b", types = """["相识","朋友"]"""), edge("b", "a", types = """["邻里"]""")),
            listOf(char("a"), char("b")),
        )
        assertEquals(listOf("相识", "朋友", "邻里"), g2.edges.first().types)
    }

    // MARK: - E6 def 查无跳过 + 待相识字段 + 引荐声明序

    @Test
    fun `E6 def查无跳过_合法待相识出图_引荐取已招募邻居`() {
        // su_wan 已招募成角色「苏晚」；yan_zhen 已发现未招募 → 待相识（引荐=苏晚·出厂边邻居 ∩ 已招募）。
        val suUuid = "char_suwan"
        val natives = listOf(
            WorldNativeStateEntity(nativeId = "native:su_wan", discovered = true, recruitedCharacterUuid = suUuid),
            WorldNativeStateEntity(nativeId = "native:yan_zhen", discovered = true, narrativeFuel = 0, recruitedCharacterUuid = null),
            WorldNativeStateEntity(nativeId = "native:bogus_slug", discovered = true, recruitedCharacterUuid = null), // 花名册查无 → 跳过
        )
        val g = graphOf(emptyList(), listOf(char(suUuid, name = "苏晚")), natives)
        assertEquals("只 1 位合法待相识", 1, g.pendings.size)
        val p = g.pendings.first()
        assertEquals("native:yan_zhen", p.nativeId)
        assertEquals(WorldNativeRoster.byNativeId("native:yan_zhen")!!.name, p.name)
        assertEquals("眼缘 0 → 打过照面档", "你们还只是打过照面", p.stagePhrase)
        assertEquals("引荐 = 已招募的出厂边邻居苏晚", "苏晚", p.referrerName)
    }

    // MARK: - E7 实时（数据变 → 显示边线型语义随之变·T2-4 主证的图侧）

    @Test
    fun `E7 边数据重发_轨迹张力色彩语义随之变`() {
        val v1 = graphOf(listOf(edge("a", "b", clo = 30, tension = 0, color = "投缘", traj = "stable"), edge("b", "a", clo = 30, color = "投缘")), listOf(char("a"), char("b"))).edges.first()
        assertEquals("stable", v1.trajectory); assertEquals(0, v1.tension)

        val v2 = graphOf(listOf(edge("a", "b", clo = 40, tension = 46, color = "别扭", traj = "cooling"), edge("b", "a", clo = 30, color = "较劲")), listOf(char("a"), char("b"))).edges.first()
        assertEquals("cooling", v2.trajectory)
        assertEquals(46, v2.tension)
        assertEquals("别扭", v2.colorForward)
        assertEquals("较劲", v2.colorReverse)
    }

    // MARK: - E4 选中归一（目标消失 → None）

    @Test
    fun `E4 选中归一_目标消失复位None`() {
        val natives = listOf(WorldNativeStateEntity(nativeId = "native:yan_zhen", discovered = true, recruitedCharacterUuid = null))
        val g = graphOf(listOf(edge("a", "b"), edge("b", "a")), listOf(char("a"), char("b")), natives)
        val pk = WorldIds.pairKey("a", "b")
        assertEquals(StarmapSelection.Node("a"), StarmapDerive.normalizeSelection(StarmapSelection.Node("a"), g))
        assertEquals(StarmapSelection.None, StarmapDerive.normalizeSelection(StarmapSelection.Node("gone"), g))
        assertEquals(StarmapSelection.Edge(pk), StarmapDerive.normalizeSelection(StarmapSelection.Edge(pk), g))
        assertEquals(StarmapSelection.None, StarmapDerive.normalizeSelection(StarmapSelection.Edge("x|y"), g))
        assertEquals(StarmapSelection.Pending("native:yan_zhen"), StarmapDerive.normalizeSelection(StarmapSelection.Pending("native:yan_zhen"), g))
        assertEquals(StarmapSelection.None, StarmapDerive.normalizeSelection(StarmapSelection.Pending("native:gone"), g))
        assertEquals(StarmapSelection.You, StarmapDerive.normalizeSelection(StarmapSelection.You, g))
    }

    // MARK: - E13 近事窗（7/8 天·相对日分档·drift/compact 跳过）

    private fun noon(epochDay: Long) = java.time.LocalDate.ofEpochDay(epochDay).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun ev(epochDay: Long, kind: String = WorldRelationshipBeats.OUTING, summary: String = "s") =
        WorldRelationshipEventEntity(uuid = "e$epochDay", pairKey = "a|b", actorId = "a", targetId = "b", kindRaw = kind, summary = summary, happenedAt = noon(epochDay), settledAt = noon(epochDay))

    @Test
    fun `T2-7 近事窗_相对日分档_超8天省略_跳过drift`() {
        val now = noon(100)
        assertEquals(RelativeDay.TODAY, StarmapDerive.starRecentOf(listOf(ev(100)), now, zone)!!.relativeDay)
        assertEquals(RelativeDay.YESTERDAY, StarmapDerive.starRecentOf(listOf(ev(99)), now, zone)!!.relativeDay)
        assertEquals(RelativeDay.BEFORE, StarmapDerive.starRecentOf(listOf(ev(98)), now, zone)!!.relativeDay)
        assertEquals(RelativeDay.RECENT, StarmapDerive.starRecentOf(listOf(ev(95)), now, zone)!!.relativeDay)
        assertEquals("恰 7 天仍显示", RelativeDay.RECENT, StarmapDerive.starRecentOf(listOf(ev(93)), now, zone)!!.relativeDay)
        assertNull("8 天超窗省略", StarmapDerive.starRecentOf(listOf(ev(92)), now, zone))
        assertNull("空事件", StarmapDerive.starRecentOf(emptyList(), now, zone))
        // 尾部最新是 drift/compact → 跳过取更早的实质事件。
        val mixed = listOf(ev(99, WorldRelationshipBeats.OUTING, "真事"), ev(100, WorldRelationshipBeats.DRIFT), ev(100, WorldRelationshipBeats.COMPACT))
        val r = StarmapDerive.starRecentOf(mixed, now, zone)!!
        assertEquals("真事", r.summary)
        assertEquals(RelativeDay.YESTERDAY, r.relativeDay)
    }
}
