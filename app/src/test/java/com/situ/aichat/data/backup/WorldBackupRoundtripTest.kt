package com.situ.aichat.data.backup

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldCityLoreEntity
import com.situ.aichat.data.local.entity.WorldDiscoveryEntity
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldMemoryEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import com.situ.aichat.data.repository.WorldRepository
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.WorldSettlementCoordinator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 世界系统备份往返 T2-5（Robolectric·W1 图纸 §7）。断言从图纸 §3/§5 独立反推：
 * - 采集→(JSON encodeDefaults=false 往返)→恢复 行级相等（源库与目标库逐表对齐）
 * - E8：世界从未初始化 → collectWorld 返回 null
 * - E6：恢复 null 世界段不崩、世界表保持空；角色三新列旧备份缺字段 → 默认兜底
 * - E7：幽灵参与者（不在库中真实角色）的边/事件/旅行整行跳过、招募指针指向幽灵 → 置 null 恢复其余字段
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBackupRoundtripTest {

    private lateinit var src: AppDatabase
    private lateinit var dst: AppDatabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Before
    fun setUp() {
        src = newDb()
        dst = newDb()
    }

    @After
    fun tearDown() {
        src.close()
        dst.close()
    }

    private fun newDb() =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /** 采集 → JSON 往返 → 恢复：源库与目标库逐表行级相等（无幽灵，全参与者都在 existing）。 */
    @Test
    fun 世界备份JSON往返行级相等() = runBlocking {
        val a = "charA"
        val b = "charB"
        src.worldDao().upsertState(
            WorldStateEntity(
                id = 1, seed = 42L, userTimezoneId = "Asia/Shanghai", userHomeCityId = "city_yunye",
                userCurrentCityId = "city_hai", lastSettledAt = 999L, createdAt = 111L,
            ),
        )
        src.worldNativeDao().upsert(
            WorldNativeStateEntity(
                nativeId = "native:lin", discovered = true, discoveredAt = 7L, narrativeFuel = 15,
                giftFuel = 8, encounterCount = 3, lastEncounterAt = 20L, recruitedCharacterUuid = a,
                currentCityId = "city_yunye",
            ),
        )
        src.worldSocialDao().upsertEdge(
            WorldRelationshipEntity(
                fromId = a, toId = b, typesJson = "[\"friend\"]", closeness = 40, trust = 30, tension = 5,
                colorRaw = "warm", trajectoryRaw = "warming", bond = "旧识", origin = "同校", dormant = false,
                updatedAt = 50L,
            ),
        )
        src.worldSocialDao().upsertEdge(WorldRelationshipEntity(fromId = WorldIds.USER_ID, toId = a, closeness = 90))
        src.worldSocialDao().upsertEvent(
            WorldRelationshipEventEntity(
                uuid = "re1", pairKey = WorldIds.pairKey(a, b), actorId = a, targetId = b, kindRaw = "meet",
                arcId = "arc1", summary = "初遇", happenedAt = 60L, settledAt = 61L,
            ),
        )
        src.worldDao().upsertEvent(
            WorldEventEntity(
                uuid = "we1", kindRaw = "join", involvedIdsJson = "[\"$a\"]", cityId = "city_yunye",
                summary = "A 加入", happenedAt = 70L, notifiedAt = 71L, seenAt = null,
            ),
        )
        src.worldDao().insertLore(WorldCityLoreEntity(cityId = "city_yunye", loreJson = "{\"n\":\"云野\"}", generatedAt = 80L))
        src.worldDao().insertDiscovery(WorldDiscoveryEntity(placeId = "wonder:falls", discoveredAt = 90L))
        src.worldDao().upsertTravel(
            WorldTravelEntity(
                ownerId = a, fromCityId = "city_yunye", toCityId = "city_hai", departAt = 100L, arriveAt = 200L,
                modeRaw = WorldIds.TravelModes.TRAIN, costGold = 33L,
            ),
        )

        val collected = collectWorld(src.worldDao(), src.worldSocialDao(), src.worldNativeDao(), src.worldMemoryDao(), src.worldUserResidentDao())
        val decoded = json.decodeFromString(
            WorldBackupData.serializer(),
            json.encodeToString(WorldBackupData.serializer(), collected!!),
        )
        restoreWorld(dst.worldDao(), dst.worldSocialDao(), dst.worldNativeDao(), dst.worldMemoryDao(), dst.worldUserResidentDao(), decoded, setOf(a, b))

        assertEquals(src.worldDao().getState(), dst.worldDao().getState())
        assertEquals(src.worldNativeDao().getAll().toSet(), dst.worldNativeDao().getAll().toSet())
        assertEquals(src.worldSocialDao().getAllEdges().toSet(), dst.worldSocialDao().getAllEdges().toSet())
        assertEquals(src.worldSocialDao().getAllEvents().toSet(), dst.worldSocialDao().getAllEvents().toSet())
        assertEquals(src.worldDao().getAllEvents().toSet(), dst.worldDao().getAllEvents().toSet())
        assertEquals(src.worldDao().getAllLore().toSet(), dst.worldDao().getAllLore().toSet())
        assertEquals(src.worldDao().getAllDiscoveries().toSet(), dst.worldDao().getAllDiscoveries().toSet())
        assertEquals(src.worldDao().getAllTravels().toSet(), dst.worldDao().getAllTravels().toSet())
    }

    /** E8：世界从未初始化（无 state 行）→ collectWorld 返回 null，整段跳过。 */
    @Test
    fun 世界从未初始化采集null_E8() = runBlocking {
        // 即使有别的世界表数据，无 state 行也返回 null（世界未真正初始化）。
        src.worldDao().insertDiscovery(WorldDiscoveryEntity("wonder:x", 1L))
        assertNull(collectWorld(src.worldDao(), src.worldSocialDao(), src.worldNativeDao(), src.worldMemoryDao(), src.worldUserResidentDao()))
    }

    /** E6：恢复 null 世界段不崩，世界表保持空。 */
    @Test
    fun 恢复null段不崩表空_E6() = runBlocking {
        restoreWorld(dst.worldDao(), dst.worldSocialDao(), dst.worldNativeDao(), dst.worldMemoryDao(), dst.worldUserResidentDao(), null, emptySet())
        assertNull(dst.worldDao().getState())
        assertTrue(dst.worldSocialDao().getAllEdges().isEmpty())
        assertTrue(dst.worldDao().getAllTravels().isEmpty())
    }

    /** E7：幽灵参与者的边/事件/旅行跳过；招募指针指向幽灵 → 置 null、其余字段照恢复；USER_ID / 原住民不算幽灵。 */
    @Test
    fun 幽灵参与者跳过招募指针置null_E7() = runBlocking {
        val real = "realChar"
        val ghost = "ghostChar"
        src.worldDao().upsertState(WorldStateEntity(id = 1, seed = 1L, createdAt = 1L))
        // 边：real↔real / user→real / real→native 应留；含 ghost 的两向应跳过。
        src.worldSocialDao().upsertEdge(WorldRelationshipEntity(fromId = real, toId = "native:z"))
        src.worldSocialDao().upsertEdge(WorldRelationshipEntity(fromId = WorldIds.USER_ID, toId = real))
        src.worldSocialDao().upsertEdge(WorldRelationshipEntity(fromId = real, toId = ghost))
        src.worldSocialDao().upsertEdge(WorldRelationshipEntity(fromId = ghost, toId = real))
        // 关系事件：actor=ghost 跳过 / 全 real 留。
        src.worldSocialDao().upsertEvent(evt("keep", real, "native:z"))
        src.worldSocialDao().upsertEvent(evt("drop", ghost, real))
        // 旅行：owner=ghost 跳过 / owner=real 留。
        src.worldDao().upsertTravel(travel(real))
        src.worldDao().upsertTravel(travel(ghost))
        // 原住民：招募指向 ghost → 置 null 保留其余；招募指向 real → 保留。
        src.worldNativeDao().upsert(
            WorldNativeStateEntity(nativeId = "native:g", narrativeFuel = 9, recruitedCharacterUuid = ghost),
        )
        src.worldNativeDao().upsert(
            WorldNativeStateEntity(nativeId = "native:r", narrativeFuel = 4, recruitedCharacterUuid = real),
        )

        val collected = collectWorld(src.worldDao(), src.worldSocialDao(), src.worldNativeDao(), src.worldMemoryDao(), src.worldUserResidentDao())!!
        restoreWorld(dst.worldDao(), dst.worldSocialDao(), dst.worldNativeDao(), dst.worldMemoryDao(), dst.worldUserResidentDao(), collected, setOf(real))

        val edges = dst.worldSocialDao().getAllEdges().map { it.fromId to it.toId }.toSet()
        assertEquals(
            "仅非幽灵边恢复",
            setOf(real to "native:z", WorldIds.USER_ID to real),
            edges,
        )
        assertEquals("含幽灵关系事件跳过", listOf("keep"), dst.worldSocialDao().getAllEvents().map { it.uuid })
        assertNull("owner=ghost 旅行跳过", dst.worldDao().getTravel(ghost))
        assertTrue("owner=real 旅行恢复", dst.worldDao().getTravel(real) != null)

        val ng = dst.worldNativeDao().get("native:g")!!
        assertNull("招募指针指向幽灵 → 置 null", ng.recruitedCharacterUuid)
        assertEquals("其余字段照恢复", 9, ng.narrativeFuel)
        assertEquals("招募指向真实角色 → 保留", real, dst.worldNativeDao().get("native:r")!!.recruitedCharacterUuid)
    }

    /** E6（角色侧）：角色三新列备份往返；旧备份缺字段 → 默认兜底（不加入 + 家乡城 + null）。 */
    @Test
    fun 角色三新列备份往返与旧默认兜底() {
        val entity = CharacterEntity(
            uuid = "c1", name = "小满", creationDate = 0L,
            joinedWorld = true, worldHomeCityId = "city_hai", worldJoinedAt = 12345L,
        )
        val roundtrip = entity.toExport(null, null).toEntity(null, null)
        assertTrue("joinedWorld 往返保真", roundtrip.joinedWorld)
        assertEquals("worldHomeCityId 往返保真", "city_hai", roundtrip.worldHomeCityId)
        assertEquals("worldJoinedAt 往返保真", 12345L, roundtrip.worldJoinedAt)

        // 旧备份（DTO 无这三字段 → 取默认）恢复为实体：不加入 + 家乡城 + null。
        val legacy = CharacterExport(uuid = "c2", name = "旧", creationDate = 0L).toEntity(null, null)
        assertTrue("旧备份 joinedWorld 默认 false", !legacy.joinedWorld)
        assertEquals("旧备份 worldHomeCityId 默认家乡城", "city_yunye", legacy.worldHomeCityId)
        assertNull("旧备份 worldJoinedAt 默认 null", legacy.worldJoinedAt)
    }

    /** T2-9 E18：世界记忆往返——导出剥 embedding（源有向量·恢复后 null）+ 幽灵提及行整行过滤。 */
    @Test
    fun 世界记忆往返剥embedding_幽灵过滤_E18() = runBlocking {
        val a = "charA"
        val b = "charB"
        val ghost = "ghostX"
        src.worldDao().upsertState(WorldStateEntity(id = 1, seed = 1L, createdAt = 1L))
        // 三条记忆：① a 视角提及 b（留）② a 视角提及幽灵（整行跳过）③ 幽灵视角（跳过）。①带 embedding 证剥离。
        src.worldMemoryDao().upsert(
            WorldMemoryEntity(
                uuid = "m_keep", characterUuid = a, otherIdsJson = "[\"$b\"]", kindRaw = "rel_first_meet",
                content = "记得", happenedAt = 100L, sourceUuid = "re1", createdAt = 100L,
                embedding = byteArrayOf(1, 2, 3, 4),
            ),
        )
        src.worldMemoryDao().upsert(
            WorldMemoryEntity(
                uuid = "m_ghostOther", characterUuid = a, otherIdsJson = "[\"$ghost\"]", kindRaw = "rel_milestone",
                content = "提及幽灵", happenedAt = 110L, sourceUuid = "re2", createdAt = 110L,
            ),
        )
        src.worldMemoryDao().upsert(
            WorldMemoryEntity(
                uuid = "m_ghostSelf", characterUuid = ghost, otherIdsJson = "[\"$a\"]", kindRaw = "rel_quarrel_mend",
                content = "幽灵视角", happenedAt = 120L, sourceUuid = "re3", createdAt = 120L,
            ),
        )

        val collected = collectWorld(src.worldDao(), src.worldSocialDao(), src.worldNativeDao(), src.worldMemoryDao(), src.worldUserResidentDao())!!
        // 导出侧 embedding 已剥离（DTO 无该字段·序列化后不含向量）。
        assertTrue("导出含三条记忆", collected.worldMemories!!.size == 3)
        val decoded = json.decodeFromString(
            WorldBackupData.serializer(),
            json.encodeToString(WorldBackupData.serializer(), collected),
        )
        restoreWorld(dst.worldDao(), dst.worldSocialDao(), dst.worldNativeDao(), dst.worldMemoryDao(), dst.worldUserResidentDao(), decoded, setOf(a, b))

        val restored = dst.worldMemoryDao().getAll()
        assertEquals("仅非幽灵行恢复", listOf("m_keep"), restored.map { it.uuid })
        assertNull("恢复后 embedding = null（导出已剥·待 worker 重嵌）", restored.first().embedding)
        assertEquals("正文往返保真", "记得", restored.first().content)
    }

    /** T2-9 E17（恢复半边）：旧备份无 worldMemories 段（null）→ 恢复不崩、记忆表空。 */
    @Test
    fun 旧备份无worldMemories段恢复不崩表空_E17() = runBlocking {
        val legacy = WorldBackupData(state = WorldStateExport(id = 1, seed = 1L, createdAt = 1L)) // worldMemories 缺省 null
        restoreWorld(dst.worldDao(), dst.worldSocialDao(), dst.worldNativeDao(), dst.worldMemoryDao(), dst.worldUserResidentDao(), legacy, emptySet())
        assertTrue("旧备份无记忆段 → 表空", dst.worldMemoryDao().getAll().isEmpty())
        assertTrue("state 照恢复", dst.worldDao().getState() != null)
    }

    // ─────────────────────────── W14 备份回归扩容（既有断言零删改·断言从图纸 §3.3 独立反推） ───────────────────────────

    /**
     * W14 E3 幽灵多路径五路齐下：备份含幽灵 uuid 的关系边(from)/关系事件(target)/在途(owner)/世界记忆(otherIds)/
     * 原住民招募指针 → collect→JSON→restore 后：四类行零恢复、原住民行在但指针 null。
     */
    @Test
    fun 幽灵多路径五路齐下四类零恢复招募指针null_E3_W14() = runBlocking {
        val real = "realChar"
        val ghost = "ghostChar"
        src.worldDao().upsertState(WorldStateEntity(id = 1, seed = 1L, createdAt = 1L))
        src.worldSocialDao().upsertEdge(WorldRelationshipEntity(fromId = ghost, toId = WorldIds.USER_ID))
        src.worldSocialDao().upsertEvent(evt("evGhost", WorldIds.USER_ID, ghost))
        src.worldDao().upsertTravel(travel(ghost))
        src.worldMemoryDao().upsert(
            WorldMemoryEntity(
                uuid = "m_g", characterUuid = real, otherIdsJson = "[\"$ghost\"]", kindRaw = "rel_milestone",
                content = "提及幽灵", happenedAt = 100L, sourceUuid = "s1", createdAt = 100L,
            ),
        )
        src.worldNativeDao().upsert(
            WorldNativeStateEntity(nativeId = "native:g", narrativeFuel = 9, recruitedCharacterUuid = ghost),
        )

        val collected = collectWorld(src.worldDao(), src.worldSocialDao(), src.worldNativeDao(), src.worldMemoryDao(), src.worldUserResidentDao())!!
        val decoded = json.decodeFromString(
            WorldBackupData.serializer(),
            json.encodeToString(WorldBackupData.serializer(), collected),
        )
        restoreWorld(dst.worldDao(), dst.worldSocialDao(), dst.worldNativeDao(), dst.worldMemoryDao(), dst.worldUserResidentDao(), decoded, setOf(real))

        assertTrue("幽灵关系边零恢复", dst.worldSocialDao().getAllEdges().isEmpty())
        assertTrue("幽灵关系事件零恢复", dst.worldSocialDao().getAllEvents().isEmpty())
        assertTrue("幽灵在途零恢复", dst.worldDao().getAllTravels().isEmpty())
        assertTrue("幽灵提及记忆零恢复", dst.worldMemoryDao().getAll().isEmpty())
        val ng = dst.worldNativeDao().get("native:g")!!
        assertNull("招募指针指向幽灵 → 置 null", ng.recruitedCharacterUuid)
        assertEquals("原住民行其余字段照恢复", 9, ng.narrativeFuel)
    }

    /** W14 E4 备份种子胜：本机已建 state(seed=111) → 恢复含 state(seed=222) 的备份段 → seed=222（upsertState 覆盖=恢复本意）。 */
    @Test
    fun 备份种子覆盖本机_E4_W14() = runBlocking {
        dst.worldDao().upsertState(WorldStateEntity(id = 1, seed = 111L, userTimezoneId = "Asia/Shanghai", createdAt = 10L))
        val backup = WorldBackupData(state = WorldStateExport(id = 1, seed = 222L, userTimezoneId = "UTC", createdAt = 20L))
        restoreWorld(dst.worldDao(), dst.worldSocialDao(), dst.worldNativeDao(), dst.worldMemoryDao(), dst.worldUserResidentDao(), backup, emptySet())
        val s = dst.worldDao().getState()!!
        assertEquals("备份种子胜过本机", 222L, s.seed)
        assertEquals("UTC", s.userTimezoneId)
        assertEquals(20L, s.createdAt)
    }

    /**
     * W14 E6 恢复锚在未来（换机时钟落后）：state.lastSettledAt = now+3天 → 跑一次结算（W2 既有入口
     * [WorldSettlementCoordinator.ensureSettled]·空贡献者）→ 冻结零新事件、锚经 MAX() 不回退（契约 §7 冻结待现实追上）。
     */
    @Test
    fun 恢复锚在未来结算零事件锚不回退_E6_W14() = runBlocking {
        val now = 1_000_000_000_000L
        val futureAnchor = now + 3L * 86_400_000L
        dst.worldDao().upsertState(WorldStateEntity(id = 1, seed = 7L, lastSettledAt = futureAnchor, createdAt = 1L))
        val coordinator = WorldSettlementCoordinator(WorldRepository(dst.worldDao()), emptySet())

        val window = coordinator.ensureSettled(now)

        assertTrue("冻结窗零日", window.days.isEmpty())
        assertTrue("非首启（锚 != 0）", !window.firstRun)
        assertEquals("锚经 MAX() 不回退", futureAnchor, dst.worldDao().getState()!!.lastSettledAt)
        assertTrue("零新世界事件", dst.worldDao().getAllEvents().isEmpty())
    }

    /** W14 E8 旧备份无世界段：已有世界数据 + restoreWorld(world=null) → 各表零变化、导入不崩（既有 null 早退的回归钉）。 */
    @Test
    fun 旧备份无世界段已有数据零变化_E8_W14() = runBlocking {
        dst.worldDao().upsertState(WorldStateEntity(id = 1, seed = 5L, userTimezoneId = "UTC", createdAt = 3L))
        dst.worldDao().upsertTravel(travel("keepChar"))
        dst.worldSocialDao().upsertEdge(WorldRelationshipEntity(fromId = WorldIds.USER_ID, toId = "keepChar", closeness = 40))
        val stateBefore = dst.worldDao().getState()
        val travelsBefore = dst.worldDao().getAllTravels().toSet()
        val edgesBefore = dst.worldSocialDao().getAllEdges().toSet()

        restoreWorld(dst.worldDao(), dst.worldSocialDao(), dst.worldNativeDao(), dst.worldMemoryDao(), dst.worldUserResidentDao(), null, emptySet())

        assertEquals("state 零变化", stateBefore, dst.worldDao().getState())
        assertEquals("在途零变化", travelsBefore, dst.worldDao().getAllTravels().toSet())
        assertEquals("关系边零变化", edgesBefore, dst.worldSocialDao().getAllEdges().toSet())
    }

    private fun evt(uuid: String, actor: String, target: String) = WorldRelationshipEventEntity(
        uuid = uuid, pairKey = WorldIds.pairKey(actor, target), actorId = actor, targetId = target,
        kindRaw = "chat", summary = "s", happenedAt = 1L, settledAt = 1L,
    )

    private fun travel(ownerId: String) = WorldTravelEntity(
        ownerId = ownerId, fromCityId = "city_yunye", toCityId = "city_hai", departAt = 1L, arriveAt = 2L,
        modeRaw = WorldIds.TravelModes.WALK,
    )
}
