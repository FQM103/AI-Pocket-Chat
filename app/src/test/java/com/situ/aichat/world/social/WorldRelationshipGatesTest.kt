package com.situ.aichat.world.social

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.SettlementDay
import com.situ.aichat.world.SettlementWindow
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.WorldSeeds
import com.situ.aichat.world.link.WorldMirrorDeriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/**
 * W4 开关 / 护栏 / 幂等 T2（图纸 §7·E2/E3/E4/E8·断言从 §3.1/§3.4/§8 护栏独立反推）。
 *
 * E3 走 [WorldRelationshipContributor]（MockK 假 SettingsRepository）证「开关关 = 各过各的」；其余走引擎。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldRelationshipGatesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var dao: WorldSocialDao
    private lateinit var engine: WorldRelationshipEngine
    private lateinit var dsScope: CoroutineScope

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.worldSocialDao()
        engine = WorldRelationshipEngine(db, WorldRelationshipCompactor(dao))
        dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        db.close()
        dsScope.cancel()
    }

    /** 真 [SettingsRepository]（临时文件 DataStore）·关系开关按 [relationships] 预置。 */
    private fun settingsRepo(relationships: Boolean): SettingsRepository = runBlocking {
        val ds = PreferenceDataStoreFactory.create(
            scope = dsScope,
            produceFile = { File(tmp.newFolder(), "settings.preferences_pb") },
        )
        val repo = SettingsRepository(ds)
        repo.setWorldRelationshipsEnabled(relationships)
        repo
    }

    private fun state(seed: Long = SEED, createdAt: Long = 0L) =
        WorldStateEntity(seed = seed, userTimezoneId = "UTC", lastSettledAt = 0L, createdAt = createdAt)

    private fun settings(relationships: Boolean = true, romance: Boolean = false) =
        AppSettings(worldRelationshipsEnabled = relationships, worldRomanceEnabled = romance)

    private fun char(uuid: String) =
        CharacterEntity(uuid = uuid, name = uuid, creationDate = 0L, joinedWorld = true, worldHomeCityId = WorldIds.HOME_CITY_ID)

    private fun day(seed: Long, epochDay: Long) =
        SettlementDay(LocalDate.ofEpochDay(epochDay), epochDay, WorldSeeds.derive(seed, "day", epochDay))

    private fun settleRange(participants: List<CharacterEntity>, range: IntRange, romance: Boolean = false) = runBlocking {
        val st = state(); val set = settings(romance = romance)
        for (e in range) engine.settleDay(st, set, participants, day(SEED, e.toLong()))
    }

    // MARK: - E2 幂等（同日重复 settle·进程死重跑·事件不重复·轴不双扣·里程碑不重发）

    @Test
    fun `E2 重跑同窗口_事件与轴逐条不变`() = runBlocking {
        val chars = listOf(char("c1"), char("c2"), char("c3"))
        settleRange(chars, 0..80)
        val eventsBefore = dao.getAllEvents().sortedBy { it.uuid }.map { it.uuid to it.summary }
        val edgesBefore = dao.getAllEdges().sortedBy { it.fromId + it.toId }
            .map { listOf(it.fromId, it.toId, it.closeness, it.trust, it.tension, it.typesJson) }

        settleRange(chars, 0..80) // 重跑同一窗口（模拟进程死后重开）

        val eventsAfter = dao.getAllEvents().sortedBy { it.uuid }.map { it.uuid to it.summary }
        val edgesAfter = dao.getAllEdges().sortedBy { it.fromId + it.toId }
            .map { listOf(it.fromId, it.toId, it.closeness, it.trust, it.tension, it.typesJson) }

        assertTrue(eventsBefore.isNotEmpty())
        assertEquals("事件不重复", eventsBefore, eventsAfter)
        assertEquals("轴/类型不双扣", edgesBefore, edgesAfter)
    }

    // MARK: - E3 开关关（worldRelationshipsEnabled=false → 零事件/零边/零镜像）

    @Test
    fun `E3 关系开关关_贡献者零产出`() = runBlocking {
        db.characterDao().upsert(char("c1"))
        db.characterDao().upsert(char("c2"))
        val contributor = WorldRelationshipContributor(settingsRepo(relationships = false), db.characterDao(), engine, WorldMirrorDeriver(dao, db.characterDao()))

        val window = SettlementWindow(
            days = (0L..30L).map { day(SEED, it) }, truncatedDays = 0, absenceMs = 0L, firstRun = false,
        )
        val mirrors = contributor.settle(state(), window)

        assertTrue("零镜像", mirrors.isEmpty())
        assertTrue("零事件", dao.getAllEvents().isEmpty())
        assertTrue("零边", dao.getAllEdges().isEmpty())
    }

    @Test
    fun `E3b 关系开关开_贡献者有产出（对照）`() = runBlocking {
        db.characterDao().upsert(char("c1"))
        db.characterDao().upsert(char("c2"))
        val contributor = WorldRelationshipContributor(settingsRepo(relationships = true), db.characterDao(), engine, WorldMirrorDeriver(dao, db.characterDao()))

        val window = SettlementWindow(
            days = (0L..60L).map { day(SEED, it) }, truncatedDays = 0, absenceMs = 0L, firstRun = false,
        )
        contributor.settle(state(), window)
        assertTrue("开关开须有事件", dao.getAllEvents().isNotEmpty())
    }

    // MARK: - E4 恋爱门关（任何种子任何日：色彩绝无 心动/暗恋·类型绝无 恋人·跑 200 天扫描·T1-5）

    @Test
    fun `E4 恋爱关_200天扫描色彩绝无心动暗恋_类型绝无恋人`() = runBlocking {
        settleRange(listOf(char("c1"), char("c2"), char("c3")), 0..200, romance = false)
        val banned = WorldRelationshipTypes.ROMANCE_COLORS.toSet() // 心动·暗恋
        for (e in dao.getAllEdges()) {
            val types = StringListJson.decode(e.typesJson)
            assertTrue("边色彩 ${e.colorRaw} 不得是恋爱色", e.colorRaw !in banned)
            assertTrue("类型不得含心动", types.none { it in banned })
            assertTrue("类型不得含恋人（恋爱门关·决策 39）", types.none { it == WorldRelationshipTypes.TYPE_ROMANCE })
        }
    }

    // MARK: - E8 休眠边（整对当日跳过：不出事/不漂移/不压缩）

    @Test
    fun `E8 休眠边_整对长跑零新事件`() = runBlocking {
        // c1↔c2 预埋边、其中一向 dormant=true；长跑后该对无任何新事件、边不变。
        val a = char("c1"); val b = char("c2")
        dao.upsertEdge(edgeOf(a.uuid, b.uuid, dormant = true))
        dao.upsertEdge(edgeOf(b.uuid, a.uuid, dormant = false))
        val abBefore = dao.getEdge(a.uuid, b.uuid)
        settleRange(listOf(a, b), 0..200)
        assertTrue("休眠对零事件", dao.getAllEvents().isEmpty())
        assertEquals("休眠边不变", abBefore, dao.getEdge(a.uuid, b.uuid))
    }

    // MARK: - 恋爱里程碑（决策 39·门开命中 / 幂等 / 事后关门保留·T1-6/7）

    @Test
    fun `T1-6 恋爱门开命中_双向恋人_里程碑恰1条_幂等不双发`() = runBlocking {
        val a = char("c1"); val b = char("c2")
        val pk = WorldIds.pairKey(a.uuid, b.uuid)
        // 双向边 closeness=75（≥ROMANCE_CLOSENESS）、types 已含 相识/朋友/密友（隔离朋友/密友里程碑）。
        val types = listOf(WorldRelationshipTypes.TYPE_ACQUAINTED, WorldRelationshipTypes.TYPE_FRIEND, WorldRelationshipTypes.TYPE_CLOSE)
        seedTypedEdge(a.uuid, b.uuid, 75, types)
        seedTypedEdge(b.uuid, a.uuid, 75, types)
        val ledger = WorldRelationshipLedger(dao)
        val beat = WorldRelationshipBeats.AXES.getValue(WorldRelationshipBeats.GOSSIP)
        val daySeed = WorldSeeds.derive(SEED, "day", 0L)
        // 主向色彩=心动 + romance 门开 → 触发恋爱里程碑。
        ledger.applyPrimary(
            WorldRelationshipBeats.GOSSIP, "primary-0", a, b,
            WorldRelationshipTypes.COLOR_HEARTBEAT, "投缘", 1.0, "", "s", null,
            beat, pk, daySeed, 1000L, Random(0), true,
        )
        assertTrue("双向 types 尾 +恋人", listOf(a.uuid to b.uuid, b.uuid to a.uuid).all {
            StringListJson.decode(dao.getEdge(it.first, it.second)!!.typesJson).contains(WorldRelationshipTypes.TYPE_ROMANCE)
        })
        val milestones = dao.getAllEvents().filter { it.kindRaw == WorldRelationshipBeats.MILESTONE }
        assertEquals("MILESTONE 事件恰 1 条", 1, milestones.size)
        val expected = WorldRelationshipBeats.MILESTONE_ROMANCE_TEMPLATES
            .map { it.replace("{a}", a.name).replace("{b}", b.name) }.toSet()
        assertTrue("summary ∈ 两模板", milestones.first().summary in expected)
        // 重跑同日同参：恋人已在 types → 不再产出。
        ledger.applyPrimary(
            WorldRelationshipBeats.GOSSIP, "primary-0", a, b,
            WorldRelationshipTypes.COLOR_HEARTBEAT, "投缘", 1.0, "", "s", null,
            beat, pk, daySeed, 1000L, Random(0), true,
        )
        assertEquals("幂等不双发", 1, dao.getAllEvents().count { it.kindRaw == WorldRelationshipBeats.MILESTONE })
    }

    @Test
    fun `T1-7 恋爱门事后关闭_恋人保留_不再新产出`() = runBlocking {
        val a = char("c1"); val b = char("c2")
        val pk = WorldIds.pairKey(a.uuid, b.uuid)
        val types = listOf(WorldRelationshipTypes.TYPE_ACQUAINTED, WorldRelationshipTypes.TYPE_FRIEND, WorldRelationshipTypes.TYPE_CLOSE)
        seedTypedEdge(a.uuid, b.uuid, 75, types)
        seedTypedEdge(b.uuid, a.uuid, 75, types)
        val ledger = WorldRelationshipLedger(dao)
        val beat = WorldRelationshipBeats.AXES.getValue(WorldRelationshipBeats.GOSSIP)
        // 先门开命中。
        ledger.applyPrimary(
            WorldRelationshipBeats.GOSSIP, "p0", a, b,
            WorldRelationshipTypes.COLOR_HEARTBEAT, "投缘", 1.0, "", "s", null,
            beat, pk, WorldSeeds.derive(SEED, "day", 0L), 1000L, Random(0), true,
        )
        assertEquals("命中后恰 1 条", 1, dao.getAllEvents().count { it.kindRaw == WorldRelationshipBeats.MILESTONE })
        // 关门（romance=false）再结算：类型保留、无新恋爱里程碑。
        ledger.applyPrimary(
            WorldRelationshipBeats.GOSSIP, "p1", a, b,
            WorldRelationshipTypes.COLOR_HEARTBEAT, "投缘", 1.0, "", "s", null,
            beat, pk, WorldSeeds.derive(SEED, "day", 1L), 2000L, Random(0), false,
        )
        assertTrue("恋人类型保留（历史不抹）", StringListJson.decode(dao.getEdge(a.uuid, b.uuid)!!.typesJson).contains(WorldRelationshipTypes.TYPE_ROMANCE))
        assertEquals("关门后无新恋爱里程碑", 1, dao.getAllEvents().count { it.kindRaw == WorldRelationshipBeats.MILESTONE })
    }

    private suspend fun seedTypedEdge(from: String, to: String, closeness: Int, types: List<String>) =
        dao.upsertEdge(
            WorldRelationshipEntity(
                fromId = from, toId = to,
                typesJson = StringListJson.encode(types),
                closeness = closeness, trust = closeness, tension = 0,
                colorRaw = "投缘", trajectoryRaw = "stable", dormant = false, updatedAt = 0L,
            ),
        )

    private fun edgeOf(from: String, to: String, dormant: Boolean) =
        WorldRelationshipEntity(
            fromId = from, toId = to,
            typesJson = StringListJson.encode(listOf(WorldRelationshipTypes.TYPE_ACQUAINTED)),
            closeness = 40, trust = 40, tension = 0, colorRaw = "投缘", trajectoryRaw = "stable",
            dormant = dormant, updatedAt = 0L,
        )

    private companion object {
        const val SEED = 42L
    }
}
