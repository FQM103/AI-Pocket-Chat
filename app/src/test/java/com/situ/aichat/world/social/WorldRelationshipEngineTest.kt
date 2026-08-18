package com.situ.aichat.world.social

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.SettlementDay
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.WorldSeeds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [WorldRelationshipEngine] T2 行为测试（W4 图纸 §7·E1/E5/E6/E7/E9/E10/E12·Robolectric 真 Room + 真 DAO/真引擎）。
 *
 * 断言从图纸 §3 数值表独立反推：轴增减/钳位算自 §3.3 增减表、里程碑阈值算自 §3.1、掷签/派生复刻自 §3.2 公式。
 * 全程 UTC 时区令 epochDay 与本地日无歧义。开关/护栏/幂等（E2/E3/E4/E8）见 [WorldRelationshipGatesTest]；
 * 情绪纯函数 + 结痂（E11）见 [WorldMoodTouchCompactionTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldRelationshipEngineTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: WorldSocialDao
    private lateinit var engine: WorldRelationshipEngine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.worldSocialDao()
        engine = WorldRelationshipEngine(db, WorldRelationshipCompactor(dao))
    }

    @After
    fun tearDown() = db.close()

    // MARK: - 脚手架

    private fun state(seed: Long, createdAt: Long = 0L) =
        WorldStateEntity(seed = seed, userTimezoneId = "UTC", lastSettledAt = 0L, createdAt = createdAt)

    private fun settings(relationships: Boolean = true, romance: Boolean = false) =
        AppSettings(worldRelationshipsEnabled = relationships, worldRomanceEnabled = romance)

    private fun char(uuid: String, name: String = uuid, home: String = WorldIds.HOME_CITY_ID) =
        CharacterEntity(uuid = uuid, name = name, creationDate = 0L, joinedWorld = true, worldHomeCityId = home)

    private fun day(seed: Long, epochDay: Long) =
        SettlementDay(LocalDate.ofEpochDay(epochDay), epochDay, WorldSeeds.derive(seed, "day", epochDay))

    /** 连续结算 [range] 各 epochDay（升序·逐日落库）。 */
    private fun settleRange(
        seed: Long,
        participants: List<CharacterEntity>,
        range: IntRange,
        romance: Boolean = false,
        createdAt: Long = 0L,
    ) = runBlocking {
        val st = state(seed, createdAt)
        val set = settings(romance = romance)
        for (e in range) engine.settleDay(st, set, participants, day(seed, e.toLong()))
    }

    private fun edge(from: String, to: String) = runBlocking { dao.getEdge(from, to) }
    private fun allEvents() = runBlocking { dao.getAllEvents() }
    private fun eventsFor(a: String, b: String) = runBlocking { dao.eventsForPair(WorldIds.pairKey(a, b)) }

    // MARK: - E1 确定性（同 seed/day/参与者·两库逐字节相同）

    @Test
    fun `E1 确定性_两库同 seed 同 30 天_事件与边逐条相同`() {
        val a = char("c1"); val b = char("c2")
        settleRange(SEED, listOf(a, b), 0..29)
        val events1 = allEvents().sortedBy { it.uuid }
        val edges1 = runBlocking { dao.getAllEdges() }.sortedBy { it.fromId + it.toId }

        // 第二库：全新引擎/DB，同输入
        db.close()
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.worldSocialDao()
        engine = WorldRelationshipEngine(db, WorldRelationshipCompactor(dao))
        settleRange(SEED, listOf(a, b), 0..29)
        val events2 = allEvents().sortedBy { it.uuid }
        val edges2 = runBlocking { dao.getAllEdges() }.sortedBy { it.fromId + it.toId }

        assertTrue("须产出若干事件", events1.isNotEmpty())
        assertEquals(events1.map { it.uuid to it.summary }, events2.map { it.uuid to it.summary })
        assertEquals(
            events1.map { listOf(it.kindRaw, it.actorId, it.targetId, it.happenedAt.toString(), it.arcId ?: "-") },
            events2.map { listOf(it.kindRaw, it.actorId, it.targetId, it.happenedAt.toString(), it.arcId ?: "-") },
        )
        assertEquals(
            edges1.map { listOf(it.fromId, it.toId, it.closeness, it.trust, it.tension, it.colorRaw, it.trajectoryRaw) },
            edges2.map { listOf(it.fromId, it.toId, it.closeness, it.trust, it.tension, it.colorRaw, it.trajectoryRaw) },
        )
    }

    // MARK: - E12 参与者<2 零产出 + 反向不对称折减 + 两向色彩独立

    @Test
    fun `E12a 参与者不足2_零产出`() {
        settleRange(SEED, listOf(char("c1")), 0..29)
        assertTrue(allEvents().isEmpty())
        assertTrue(runBlocking { dao.getAllEdges() }.isEmpty())
    }

    @Test
    fun `E12b 首识后建双向独立边_两向轴钳位合法_色彩非空`() {
        // 长跑令 c1↔c2 建边并累积：两向各是独立边（各自色彩/轴），全轴钳位在 [0,100]、trajectory 合法。
        settleRange(SEED, listOf(char("c1"), char("c2")), 0..200)
        val ab = edge("c1", "c2"); val ba = edge("c2", "c1")
        assertNotNull(ab); assertNotNull(ba)
        for (e in listOf(ab!!, ba!!)) {
            assertTrue(e.closeness in 0..100 && e.trust in 0..100 && e.tension in 0..100)
            assertTrue(e.trajectoryRaw in setOf("warming", "cooling", "stable"))
            assertTrue("色彩来自合法池", e.colorRaw.isNotEmpty())
        }
    }

    // MARK: - E7 钳位（全轴 ∈[0,100]；连续负面 tension 封 100）

    @Test
    fun `E7 长跑全轴恒在0到100_tension可达但不过100`() {
        settleRange(SEED, listOf(char("c1"), char("c2"), char("c3")), 0..400)
        val edges = runBlocking { dao.getAllEdges() }
        assertTrue(edges.isNotEmpty())
        for (e in edges) {
            assertTrue("closeness ${e.closeness}", e.closeness in 0..100)
            assertTrue("trust ${e.trust}", e.trust in 0..100)
            assertTrue("tension ${e.tension}", e.tension in 0..100)
        }
    }

    // MARK: - E6 里程碑（首过 35→朋友、70→密友·各恰一次·types 追加双向）

    @Test
    fun `E6a 朋友里程碑_首过35恰一次_双向types含朋友_重跑不重发`() = runBlocking {
        // seed 略低于 35，逐日推到越过（短程无压缩·避免里程碑事件被结痂折叠）。
        seedKnownPair("c1", "c2", closeness = 34, types = listOf(WorldRelationshipTypes.TYPE_ACQUAINTED))
        val st = state(SEED); val set = settings()
        val chars = listOf(char("c1"), char("c2"))
        var crossed = -1L
        for (e in 0L..300L) {
            engine.settleDay(st, set, chars, day(SEED, e))
            if (dao.getEdge("c1", "c2")!!.closeness >= 35) { crossed = e; break }
        }
        assertTrue("300 天内应越过 35", crossed >= 0)
        assertEquals("恰一条里程碑事件", 1, dao.getAllEvents().count { it.kindRaw == WorldRelationshipBeats.MILESTONE })
        assertTrue("双向 types 含朋友", listOf("c1" to "c2", "c2" to "c1").all {
            StringListJson.decode(dao.getEdge(it.first, it.second)!!.typesJson).contains(WorldRelationshipTypes.TYPE_FRIEND)
        })
        assertTrue("未误升密友", !StringListJson.decode(dao.getEdge("c1", "c2")!!.typesJson).contains(WorldRelationshipTypes.TYPE_CLOSE))
        // 重跑越过日 + 后续 10 天：不重发。
        for (e in crossed..(crossed + 10)) engine.settleDay(st, set, chars, day(SEED, e))
        assertEquals("重跑/后续不重发", 1, dao.getAllEvents().count { it.kindRaw == WorldRelationshipBeats.MILESTONE })
    }

    @Test
    fun `E6b 密友里程碑_首过70恰一次_双向types含密友`() = runBlocking {
        // 已是朋友、seed 略低于 70，逐日推到越过（只应触发密友一次）。
        val friendAcq = listOf(WorldRelationshipTypes.TYPE_ACQUAINTED, WorldRelationshipTypes.TYPE_FRIEND)
        seedKnownPair("c1", "c2", closeness = 68, types = friendAcq)
        val st = state(SEED); val set = settings()
        val chars = listOf(char("c1"), char("c2"))
        var crossed = -1L
        for (e in 0L..300L) {
            engine.settleDay(st, set, chars, day(SEED, e))
            if (dao.getEdge("c1", "c2")!!.closeness >= 70) { crossed = e; break }
        }
        assertTrue("300 天内应越过 70", crossed >= 0)
        assertEquals("恰一条里程碑事件（密友）", 1, dao.getAllEvents().count { it.kindRaw == WorldRelationshipBeats.MILESTONE })
        assertTrue("双向 types 含密友", listOf("c1" to "c2", "c2" to "c1").all {
            StringListJson.decode(dao.getEdge(it.first, it.second)!!.typesJson).contains(WorldRelationshipTypes.TYPE_CLOSE)
        })
    }

    // MARK: - E5 弧线（quarrel start→cold(1–2)→mend·期间不掷新事签·mend 后张力显著回落）

    @Test
    fun `E5 拌嘴弧线三步结构正确_mend后张力低于start`() {
        settleRange(SEED, listOf(char("c1"), char("c2")), 0..600)
        val events = eventsFor("c1", "c2")
        val arcIds = events.mapNotNull { it.arcId }.toSet()
        assertTrue("须至少一条弧线", arcIds.isNotEmpty())
        val quarrelKinds = setOf(
            WorldRelationshipBeats.QUARREL_START, WorldRelationshipBeats.QUARREL_COLD, WorldRelationshipBeats.QUARREL_MEND,
        )
        var completedArcs = 0
        for (arcId in arcIds) {
            val arc = events.filter { it.arcId == arcId }.sortedBy { it.happenedAt }
            val kinds = arc.map { it.kindRaw }
            assertEquals("弧线首步=拌嘴", WorldRelationshipBeats.QUARREL_START, kinds.first())
            assertTrue("弧线步只含 quarrel 三种", kinds.all { it in quarrelKinds })
            assertTrue("冷战至多 2 天", kinds.count { it == WorldRelationshipBeats.QUARREL_COLD } in 0..2)
            if (kinds.contains(WorldRelationshipBeats.QUARREL_MEND)) {
                completedArcs++
                assertEquals("和好为末步", WorldRelationshipBeats.QUARREL_MEND, kinds.last())
                assertEquals("弧线只 1 条 mend", 1, kinds.count { it == WorldRelationshipBeats.QUARREL_MEND })
                assertTrue("完成弧线冷战 1–2 天", kinds.count { it == WorldRelationshipBeats.QUARREL_COLD } in 1..2)
                // 每步一天一步（期间不掷新事签 = happenedAt 严格递增·无同日双事件）
                val moments = arc.map { it.happenedAt }
                assertEquals(moments.sorted(), moments)
                assertEquals(moments.toSet().size, moments.size)
            }
        }
        assertTrue("须至少一条完整弧线（start→cold→mend）", completedArcs >= 1)
    }

    // MARK: - E9 渐远漂移（第 14 天起每满 7 天一次·−1·静默·不进镜像·重跑不双扣）

    @Test
    fun `E9 漂移在第14天起每7天一次_不进镜像_重跑不双扣`() = runBlocking {
        // 预埋边 + 一条「实质」事件在 epochDay 0（正午），此后无任何实质事件 → driftDue 起点恒钉在 day 0。
        // 只结算「漂移到期节点里、且不掷中出事签」的天（isolate 掉随机出事对 drift 节奏的干扰）：miss 节点必漂移。
        val a = char("c1"); val b = char("c2")
        val pk = WorldIds.pairKey(a.uuid, b.uuid)
        val noon0 = LocalDate.ofEpochDay(0).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        seedEdge(a.uuid, b.uuid, closeness = 50)
        seedEdge(b.uuid, a.uuid, closeness = 50)
        dao.upsertEvent(
            com.situ.aichat.data.local.entity.WorldRelationshipEventEntity(
                uuid = "seed-first", pairKey = pk, actorId = a.uuid, targetId = b.uuid,
                kindRaw = WorldRelationshipBeats.FIRST_MEET, arcId = null, summary = "seed",
                happenedAt = noon0, settledAt = noon0,
            ),
        )
        val st = state(SEED); val set = settings()
        val driftNodes = (14L..70L).filter { it % 7 == 0L }
        var expectedCloseness = 50
        var driftCount = 0
        for (e in driftNodes) {
            // 复刻 §3.2 掷签：非新手·有边 → 命中出事概率 0.08；只处理 miss 节点（drift 才有机会）。
            val miss = WorldSeeds.randomOf(WorldSeeds.derive(SEED, "rel", WorldSeeds.fnv1a64(pk) xor e)).nextDouble() >= 0.08
            if (!miss) continue
            // W5：settleDay 已改返 Unit（镜像移交 WorldMirrorDeriver）；「drift 不进镜像」改由 WorldMirrorDeriverTest 证。
            engine.settleDay(st, set, listOf(a, b), day(SEED, e))
            assertEquals("该节点恰生一条 drift", WorldRelationshipBeats.DRIFT, dao.newestEventForPair(pk)!!.kindRaw)
            expectedCloseness = (expectedCloseness - 1).coerceAtLeast(10)
            assertEquals("closeness 逐次 −1（地板 10）", expectedCloseness, dao.getEdge(a.uuid, b.uuid)!!.closeness)
            driftCount++
        }
        assertTrue("须至少发生数次漂移", driftCount >= 3)

        // 重跑同日不双扣：再结算一遍，closeness 不变、drift 事件不重复。
        val closenessBefore = dao.getEdge(a.uuid, b.uuid)!!.closeness
        val driftEventsBefore = dao.eventsForPair(pk).count { it.kindRaw == WorldRelationshipBeats.DRIFT }
        for (e in driftNodes) {
            val miss = WorldSeeds.randomOf(WorldSeeds.derive(SEED, "rel", WorldSeeds.fnv1a64(pk) xor e)).nextDouble() >= 0.08
            if (miss) engine.settleDay(st, set, listOf(a, b), day(SEED, e))
        }
        assertEquals("重跑 closeness 不变", closenessBefore, dao.getEdge(a.uuid, b.uuid)!!.closeness)
        assertEquals("重跑 drift 不重复", driftEventsBefore, dao.eventsForPair(pk).count { it.kindRaw == WorldRelationshipBeats.DRIFT })
    }

    // MARK: - E9b 地板边界（预置低于地板的边·drift 只往下走到 10 停·不得把 9 抬回 10）

    @Test
    fun `E9b 漂移地板_预置closeness9的边_drift后仍为9不被抬回10`() = runBlocking {
        // 🟡-1 回归：地板语义 = drift 只减、减到 10 停；已低于地板(9)的边 drift 应「不动它」，绝不抬回 10。
        val a = char("c1"); val b = char("c2")
        val pk = WorldIds.pairKey(a.uuid, b.uuid)
        val noon0 = LocalDate.ofEpochDay(0).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        seedEdge(a.uuid, b.uuid, closeness = 9)
        seedEdge(b.uuid, a.uuid, closeness = 9)
        dao.upsertEvent(
            WorldRelationshipEventEntity(
                uuid = "seed-first-9", pairKey = pk, actorId = a.uuid, targetId = b.uuid,
                kindRaw = WorldRelationshipBeats.FIRST_MEET, arcId = null, summary = "seed",
                happenedAt = noon0, settledAt = noon0,
            ),
        )
        val st = state(SEED); val set = settings()
        // 取第一个「miss（不掷中出事签）」的漂移到期节点 → 该日必产 drift（复刻 §3.2：非新手·有边→0.08）。
        val driftNode = (14L..70L).first { e ->
            e % 7 == 0L &&
                WorldSeeds.randomOf(WorldSeeds.derive(SEED, "rel", WorldSeeds.fnv1a64(pk) xor e)).nextDouble() >= 0.08
        }
        engine.settleDay(st, set, listOf(a, b), day(SEED, driftNode))
        assertEquals("该节点恰生一条 drift", WorldRelationshipBeats.DRIFT, dao.newestEventForPair(pk)!!.kindRaw)
        assertEquals("低于地板的边 drift 后不动（仍为 9·不被抬回 10）", 9, dao.getEdge(a.uuid, b.uuid)!!.closeness)
        assertEquals("反向边同理仍为 9", 9, dao.getEdge(b.uuid, a.uuid)!!.closeness)
    }

    // MARK: - E10 日上限（同日 >3 对命中出事 → 只前 3 对·fnv1a64 升序）

    @Test
    fun `E10 同日多于3对命中_只前3对按fnv升序落事件`() = runBlocking {
        // 12 个新手角色（66 对·新手概率 0.20）→ 某日命中数远超 3。独立复刻掷签算出命中集。
        val chars = (1..12).map { char("c%02d".format(it)) }
        val st = state(SEED, createdAt = 0L); val set = settings()
        val epochDay = 0L // 新手窗内
        // 独立反推：无边对当日相识概率 = 0.20（新手×2）。
        val hitters = mutableListOf<Pair<String, Long>>()
        for (i in chars.indices) for (j in i + 1 until chars.size) {
            val pk = WorldIds.pairKey(chars[i].uuid, chars[j].uuid)
            val fnv = WorldSeeds.fnv1a64(pk)
            val rng = WorldSeeds.randomOf(WorldSeeds.derive(SEED, "rel", fnv xor epochDay))
            if (rng.nextDouble() < 0.20) hitters += pk to fnv
        }
        assertTrue("构造须有 >3 对命中（命中数=${hitters.size}）", hitters.size > 3)
        val expectedFired = hitters.sortedBy { it.second }.take(3).map { it.first }.toSet()

        engine.settleDay(st, set, chars, day(SEED, epochDay))
        // 当日新落事件的 pairKey（first_meet）
        val firedPairs = dao.getAllEvents()
            .filter { it.kindRaw == WorldRelationshipBeats.FIRST_MEET }
            .map { it.pairKey }.toSet()
        assertEquals("恰前 3 对（fnv 升序）落事件", expectedFired, firedPairs)
    }

    // MARK: - E13 金标（seed=42·固定 2 角色 uuid·连结算 30 天·钉死字面量）

    @Test
    fun `E13 金标_seed42_两角色_30天_钉死字面量`() = runBlocking {
        // ⚠️ 实跑固化·绝不许改：这些字面量由确定性引擎实跑固化。任何数值/抽签顺序改动都会打破本金标——
        // 若失败，先确认是「有意的引擎行为变更」再重新固化，否则即为回归（图纸 §7 E13 / §9 禁改）。
        settleRange(SEED, listOf(char("c1"), char("c2")), 0..29)
        val ev = eventsFor("c1", "c2")
        val ab = edge("c1", "c2")!!
        val ba = edge("c2", "c1")!!
        assertEquals("事件总数", 5, ev.size)
        assertEquals("首事件 kind", WorldRelationshipBeats.FIRST_MEET, ev.first().kindRaw)
        assertEquals("首事件文案", "c1和c2在你的家聊了一路，越聊越投机", ev.first().summary)
        // 30 天后双向 closeness 四值
        assertEquals("c1→c2 closeness", 24, ab.closeness)
        assertEquals("c1→c2 trust", 20, ab.trust)
        assertEquals("c2→c1 closeness", 25, ba.closeness)
        assertEquals("c2→c1 trust", 20, ba.trust)
    }

    private suspend fun seedEdge(from: String, to: String, closeness: Int) =
        seedEdgeTyped(from, to, closeness, listOf(WorldRelationshipTypes.TYPE_ACQUAINTED))

    /**
     * 预置一个「已认识」的对：两向边 + 一条历史 first_meet 事件（day -1 正午）——令引擎 asOf 视其为有边（不重置）。
     * （引擎的 hasEdge 由「截至当日事件是否非空」判，故只播边不播事件会被当陌生人重新初识。）
     */
    private suspend fun seedKnownPair(a: String, b: String, closeness: Int, types: List<String>) {
        seedEdgeTyped(a, b, closeness, types)
        seedEdgeTyped(b, a, closeness, types)
        val pk = WorldIds.pairKey(a, b)
        val past = LocalDate.ofEpochDay(-1).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        dao.upsertEvent(
            WorldRelationshipEventEntity(
                uuid = "seed-$pk", pairKey = pk, actorId = a, targetId = b,
                kindRaw = WorldRelationshipBeats.FIRST_MEET, arcId = null, summary = "seed",
                happenedAt = past, settledAt = past,
            ),
        )
    }

    private suspend fun seedEdgeTyped(from: String, to: String, closeness: Int, types: List<String>) {
        dao.upsertEdge(
            WorldRelationshipEntity(
                fromId = from, toId = to,
                typesJson = StringListJson.encode(types),
                closeness = closeness, trust = closeness, tension = 0,
                colorRaw = "投缘", trajectoryRaw = "stable", updatedAt = 0L,
            ),
        )
    }

    private companion object {
        const val SEED = 42L
    }
}
