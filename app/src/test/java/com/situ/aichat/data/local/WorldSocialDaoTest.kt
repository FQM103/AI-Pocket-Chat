package com.situ.aichat.data.local

import androidx.room.Room
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.world.WorldIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 世界社交 DAO T2（Robolectric 真 Room 内存库·W1 图纸 §7 T2-8）。断言从图纸 §3/§5 规格独立反推：
 * - 有向边独立：A→B 改动不影响 B→A（方向不对称）
 * - setDormantFor 双向命中该参与者的边、不碰无关边
 * - 关系事件按 pairKey 查询、happenedAt 升序
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldSocialDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: WorldSocialDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.worldSocialDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 有向边：A→B 与 B→A 是两独立行；改 A→B 的数值不动 B→A。 */
    @Test
    fun 有向边独立() = runBlocking {
        dao.upsertEdge(WorldRelationshipEntity(fromId = "A", toId = "B", closeness = 10, trust = 10))
        dao.upsertEdge(WorldRelationshipEntity(fromId = "B", toId = "A", closeness = 20, trust = 20))
        assertEquals("两独立行", 2, dao.getAllEdges().size)

        dao.upsertEdge(WorldRelationshipEntity(fromId = "A", toId = "B", closeness = 50, trust = 55))

        assertEquals("A→B 已更新", 50, dao.getEdge("A", "B")!!.closeness)
        assertEquals("B→A 不受扰：closeness", 20, dao.getEdge("B", "A")!!.closeness)
        assertEquals("B→A 不受扰：trust", 20, dao.getEdge("B", "A")!!.trust)
        assertEquals("仍两行", 2, dao.getAllEdges().size)
    }

    /** setDormantFor(A) 命中所有含 A 的两向边；不含 A 的边不动。 */
    @Test
    fun setDormantFor双向命中不碰无关边() = runBlocking {
        dao.upsertEdge(WorldRelationshipEntity(fromId = "A", toId = "B"))       // A 出向
        dao.upsertEdge(WorldRelationshipEntity(fromId = "B", toId = "A"))       // A 入向
        dao.upsertEdge(WorldRelationshipEntity(fromId = "A", toId = "native:c")) // A 出向（对原住民）
        dao.upsertEdge(WorldRelationshipEntity(fromId = "B", toId = "native:c")) // 无 A

        dao.setDormantFor("A", true)

        val byPair = dao.getAllEdges().associateBy { it.fromId to it.toId }
        assertTrue("A→B 休眠", byPair.getValue("A" to "B").dormant)
        assertTrue("B→A 休眠", byPair.getValue("B" to "A").dormant)
        assertTrue("A→native:c 休眠", byPair.getValue("A" to "native:c").dormant)
        assertFalse("B→native:c 不含 A，不休眠", byPair.getValue("B" to "native:c").dormant)
    }

    /** 关系事件：按 pairKey 取两人历史、happenedAt 升序；别的对键不混入。 */
    @Test
    fun 关系事件按pairKey查询且happenedAt升序() = runBlocking {
        val ab = WorldIds.pairKey("A", "B")
        val ac = WorldIds.pairKey("A", "C")
        // 乱序插入 A-B 的三条事件 + 一条 A-C 干扰事件。
        dao.upsertEvent(ev("e3", ab, actor = "A", target = "B", at = 300L))
        dao.upsertEvent(ev("e1", ab, actor = "B", target = "A", at = 100L))
        dao.upsertEvent(ev("e2", ab, actor = "A", target = "B", at = 200L))
        dao.upsertEvent(ev("x1", ac, actor = "A", target = "C", at = 150L))

        val history = dao.eventsForPair(ab)
        assertEquals("只取 A-B 对键、按 happenedAt 升序", listOf("e1", "e2", "e3"), history.map { it.uuid })
        assertEquals("A-C 对键独立", listOf("x1"), dao.eventsForPair(ac).map { it.uuid })
    }

    private fun ev(uuid: String, pairKey: String, actor: String, target: String, at: Long) =
        WorldRelationshipEventEntity(
            uuid = uuid, pairKey = pairKey, actorId = actor, targetId = target,
            kindRaw = "chat", summary = "s", happenedAt = at, settledAt = at,
        )
}
