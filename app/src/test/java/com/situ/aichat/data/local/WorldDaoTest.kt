package com.situ.aichat.data.local

import androidx.room.Room
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.entity.WorldCityLoreEntity
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import com.situ.aichat.data.repository.WorldRepository
import com.situ.aichat.world.WorldIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 世界核心 DAO / 仓库 T2（Robolectric 真 Room 内存库·W1 图纸 §7 T2-1/2/3/6）。断言从图纸 §3/§5 规格独立反推：
 * - E2 懒结算单调锚只进不退（设备时间回拨 → 冻结）
 * - 世界事件 unseen→markSeen 幂等（重标不改原始时刻）
 * - discovery IGNORE 幂等
 * - E3 在途旅行一 owner 一行（PK 替换·后写覆盖前写）
 * - E4 风物志一次定稿 IGNORE（canon 永久）
 * - E9 并发 ensureState 只一个种子
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: WorldDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.worldDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** E2：advanceSettledAt 用 MAX 语义——回拨（较小值）不后退，前进（较大值）照走。 */
    @Test
    fun advanceSettledAt只进不退() = runBlocking {
        dao.upsertState(WorldStateEntity(id = 1, seed = 7L, createdAt = 1_000L)) // lastSettledAt 默认 0
        dao.advanceSettledAt(100L)
        assertEquals("前进到 100", 100L, dao.getState()!!.lastSettledAt)
        dao.advanceSettledAt(50L) // 设备时间回拨
        assertEquals("回拨 50 不后退，仍 100", 100L, dao.getState()!!.lastSettledAt)
        dao.advanceSettledAt(200L)
        assertEquals("再前进到 200", 200L, dao.getState()!!.lastSettledAt)
    }

    /** 世界事件 unseen→markSeen 幂等：首标写入时刻，重标（不同时刻）不改原始 seenAt；notifiedAt 同理。 */
    @Test
    fun 世界事件markSeen幂等() = runBlocking {
        dao.upsertEvent(
            WorldEventEntity(uuid = "e1", kindRaw = "join", summary = "小K 来到了云野镇", happenedAt = 500L),
        )
        assertEquals("初始未被消费", listOf("e1"), dao.unseenEvents().map { it.uuid })

        dao.markEventSeen("e1", 900L)
        assertEquals("标记后不再 unseen", emptyList<String>(), dao.unseenEvents().map { it.uuid })
        assertEquals("seenAt 记为首标时刻", 900L, dao.getAllEvents().single().seenAt)

        dao.markEventSeen("e1", 1_500L) // 重标
        assertEquals("重标不改原始 seenAt", 900L, dao.getAllEvents().single().seenAt)

        dao.markEventNotified("e1", 1_000L)
        assertEquals(1_000L, dao.getAllEvents().single().notifiedAt)
        dao.markEventNotified("e1", 2_000L)
        assertEquals("重标 notifiedAt 不改原始值", 1_000L, dao.getAllEvents().single().notifiedAt)
    }

    /** discovery IGNORE 幂等：重复发现同一 placeId 保留首次时刻，行数不增。 */
    @Test
    fun 发现记录IGNORE幂等() = runBlocking {
        dao.insertDiscovery(com.situ.aichat.data.local.entity.WorldDiscoveryEntity("wonder:falls", 100L))
        dao.insertDiscovery(com.situ.aichat.data.local.entity.WorldDiscoveryEntity("wonder:falls", 999L))
        assertEquals("仅一行", 1, dao.getAllDiscoveries().size)
        assertEquals("保留首次发现时刻", 100L, dao.getDiscovery("wonder:falls")!!.discoveredAt)
    }

    /** E3：在途旅行 PK=ownerId，一 owner 至多一行；二次 upsert 覆盖前一行。 */
    @Test
    fun 在途旅行一owner一行() = runBlocking {
        dao.upsertTravel(
            WorldTravelEntity(
                ownerId = WorldIds.USER_ID, fromCityId = "city_yunye", toCityId = "city_hai",
                departAt = 10L, arriveAt = 100L, modeRaw = WorldIds.TravelModes.TRAIN, costGold = 50L,
            ),
        )
        dao.upsertTravel(
            WorldTravelEntity(
                ownerId = WorldIds.USER_ID, fromCityId = "city_hai", toCityId = "city_shan",
                departAt = 200L, arriveAt = 300L, modeRaw = WorldIds.TravelModes.PLANE, costGold = 120L,
            ),
        )
        assertEquals("同 owner 仅一行", 1, dao.getAllTravels().size)
        val t = dao.getTravel(WorldIds.USER_ID)!!
        assertEquals("后写覆盖前写：目的地", "city_shan", t.toCityId)
        assertEquals("后写覆盖前写：方式", WorldIds.TravelModes.PLANE, t.modeRaw)
    }

    /** E4：风物志首访定稿后 IGNORE——再写不覆盖（canon 永久·契约 §7.A）。 */
    @Test
    fun 风物志一次定稿IGNORE永久() = runBlocking {
        dao.insertLore(WorldCityLoreEntity(cityId = "city_yunye", loreJson = "{\"v\":1}", generatedAt = 1L))
        dao.insertLore(WorldCityLoreEntity(cityId = "city_yunye", loreJson = "{\"v\":2}", generatedAt = 2L))
        val lore = dao.getLore("city_yunye")!!
        assertEquals("canon 不被覆盖：正文", "{\"v\":1}", lore.loreJson)
        assertEquals("canon 不被覆盖：时刻", 1L, lore.generatedAt)
        assertEquals("仅一行", 1, dao.getAllLore().size)
    }

    /** E9：两协程（真多线程 Dispatcher）并发 ensureState → 双检 + Mutex 保证只建一行、只一个种子。 */
    @Test
    fun 并发ensureState只一个种子() = runBlocking {
        val repo = WorldRepository(dao)
        assertNull("初始世界未初始化", dao.getState())
        val seeds = withContext(Dispatchers.Default) {
            listOf(async { repo.ensureState().seed }, async { repo.ensureState().seed }).awaitAll()
        }
        assertEquals("两次 ensureState 返回同一种子", seeds[0], seeds[1])
        val state = dao.getState()
        assertNotNull("状态行已建", state)
        assertEquals("落库种子 = 返回种子", seeds[0], state!!.seed)
    }
}
