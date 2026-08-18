package com.situ.aichat.world.social

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T1 情绪轻碰三纯函数（[WorldMoodTouch]·§3.7）+ T2 结痂压缩（[WorldRelationshipCompactor]·E11·§3.6）。
 *
 * 结痂用例直接驱动压缩器（Robolectric 真 Room）：断言从 §3.6 参数（40/24/500）与走向规则独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldMoodTouchCompactionTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: WorldSocialDao
    private lateinit var compactor: WorldRelationshipCompactor

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.worldSocialDao()
        compactor = WorldRelationshipCompactor(dao)
    }

    @After
    fun tearDown() = db.close()

    // ==========================================================================
    // T1 情绪轻碰纯函数
    // ==========================================================================

    @Test
    fun `dayMoodDelta 正向合计封顶2`() {
        assertEquals(2, WorldMoodTouch.dayMoodDelta(listOf(1, 1, 1)))
        assertEquals(2, WorldMoodTouch.dayMoodDelta(listOf(1, 1)))
        assertEquals(1, WorldMoodTouch.dayMoodDelta(listOf(1)))
    }

    @Test
    fun `dayMoodDelta 负向合计封底负2`() {
        assertEquals(-2, WorldMoodTouch.dayMoodDelta(listOf(-1, -1, -1)))
        assertEquals(-2, WorldMoodTouch.dayMoodDelta(listOf(-1, -1)))
        assertEquals(-1, WorldMoodTouch.dayMoodDelta(listOf(-1)))
    }

    @Test
    fun `dayMoodDelta 空或抵消为0`() {
        assertEquals(0, WorldMoodTouch.dayMoodDelta(emptyList()))
        assertEquals(0, WorldMoodTouch.dayMoodDelta(listOf(1, -1)))
        assertEquals(0, WorldMoodTouch.dayMoodDelta(listOf(1, 1, -1, -1)))
    }

    @Test
    fun `decay 正向回落一格`() {
        assertEquals(1, WorldMoodTouch.decay(2))
        assertEquals(0, WorldMoodTouch.decay(1))
    }

    @Test
    fun `decay 负向回升一格`() {
        assertEquals(-1, WorldMoodTouch.decay(-2))
        assertEquals(0, WorldMoodTouch.decay(-1))
    }

    @Test
    fun `decay 到0停住`() {
        assertEquals(0, WorldMoodTouch.decay(0))
    }

    @Test
    fun `contagion 正向全传`() {
        assertEquals(2, WorldMoodTouch.contagion(2))
        assertEquals(1, WorldMoodTouch.contagion(1))
    }

    @Test
    fun `contagion 负向减半_加1后封顶0`() {
        assertEquals(-1, WorldMoodTouch.contagion(-2))
        assertEquals(0, WorldMoodTouch.contagion(-1))
        assertEquals(0, WorldMoodTouch.contagion(0))
    }

    // ==========================================================================
    // T2 结痂压缩（E11·§3.6）
    // ==========================================================================

    // MARK: - E11 主体：45 条 → 保留最新 24 + 1 条 rel_compact；origin 追加结痂句；trend=更近了

    @Test
    fun `E11 结痂_45条压成保留24加1_origin追加_trend更近了`() = runBlocking {
        val pk = WorldIds.pairKey("c1", "c2")
        seedEdges("c1", "c2", origin = "相识于码头")
        // 最老 21 条（被压区间）= 正向（outing）→ trend=更近了；其余 24 条留存。
        insertEvents(pk, "c1", "c2", List(21) { WorldRelationshipBeats.OUTING } + List(24) { WorldRelationshipBeats.GOSSIP })
        assertEquals(45, dao.countEventsForPair(pk))

        val didCompact = compactor.compactIfNeeded(pk, "c1", "c2", "c1", "c2", daySeed = 7L, happenedAt = 123_000L)

        assertTrue(didCompact)
        assertEquals("保留最新 24 + 1 条结痂", 25, dao.countEventsForPair(pk))
        val compact = dao.eventsForPair(pk).single { it.kindRaw == WorldRelationshipBeats.COMPACT }
        assertEquals("这段日子c1和c2之间大大小小发生了21件事，关系更近了", compact.summary)
        // origin 双向追加结痂句
        for (dir in listOf("c1" to "c2", "c2" to "c1")) {
            val origin = dao.getEdge(dir.first, dir.second)!!.origin
            assertEquals("相识于码头；这段日子c1和c2之间大大小小发生了21件事，关系更近了", origin)
        }
        // 被压的 21 条最老 outing 已删（留存的都非 outing 或为结痂）
        assertEquals("outing 被清空", 0, dao.eventsForPair(pk).count { it.kindRaw == WorldRelationshipBeats.OUTING })
    }

    // MARK: - E11 trend 三分支（§3.6 锁死）

    @Test
    fun `E11b trend_drift占比过半_淡了些`() = runBlocking {
        val pk = WorldIds.pairKey("c1", "c2")
        seedEdges("c1", "c2", origin = "x")
        // 被压 21 条中 drift 15 (>50%) → 淡了些
        insertEvents(pk, "c1", "c2", List(15) { WorldRelationshipBeats.DRIFT } + List(6) { WorldRelationshipBeats.OUTING } + List(24) { WorldRelationshipBeats.GOSSIP })
        compactor.compactIfNeeded(pk, "c1", "c2", "c1", "c2", daySeed = 1L, happenedAt = 1L)
        assertTrue(dao.eventsForPair(pk).single { it.kindRaw == WorldRelationshipBeats.COMPACT }.summary.endsWith("关系淡了些"))
    }

    @Test
    fun `E11c trend_quarrel多于正向_起起伏伏`() = runBlocking {
        val pk = WorldIds.pairKey("c1", "c2")
        seedEdges("c1", "c2", origin = "x")
        // 被压 21 条：quarrel 15、正向 6、drift 0 → 起起伏伏
        insertEvents(pk, "c1", "c2", List(15) { WorldRelationshipBeats.QUARREL_START } + List(6) { WorldRelationshipBeats.OUTING } + List(24) { WorldRelationshipBeats.GOSSIP })
        compactor.compactIfNeeded(pk, "c1", "c2", "c1", "c2", daySeed = 2L, happenedAt = 1L)
        assertTrue(dao.eventsForPair(pk).single { it.kindRaw == WorldRelationshipBeats.COMPACT }.summary.endsWith("关系起起伏伏"))
    }

    // MARK: - E11 边界：不足阈值不压、origin 500 封顶、幂等不重压

    @Test
    fun `E11d 不足40不压缩`() = runBlocking {
        val pk = WorldIds.pairKey("c1", "c2")
        seedEdges("c1", "c2", origin = "x")
        insertEvents(pk, "c1", "c2", List(40) { WorldRelationshipBeats.GOSSIP }) // 恰 40（> 40 才压）
        assertFalse(compactor.compactIfNeeded(pk, "c1", "c2", "c1", "c2", daySeed = 3L, happenedAt = 1L))
        assertEquals(40, dao.countEventsForPair(pk))
    }

    @Test
    fun `E11e origin超500只留最新500`() = runBlocking {
        val pk = WorldIds.pairKey("c1", "c2")
        seedEdges("c1", "c2", origin = "旧".repeat(495)) // 495 字旧渊源
        insertEvents(pk, "c1", "c2", List(21) { WorldRelationshipBeats.OUTING } + List(24) { WorldRelationshipBeats.GOSSIP })
        compactor.compactIfNeeded(pk, "c1", "c2", "c1", "c2", daySeed = 4L, happenedAt = 1L)
        val origin = dao.getEdge("c1", "c2")!!.origin
        assertEquals("超 500 只留最新 500", 500, origin.length)
        assertTrue("尾部是新结痂句", origin.endsWith("关系更近了"))
    }

    @Test
    fun `E11f 已压过再调不重压_幂等`() = runBlocking {
        val pk = WorldIds.pairKey("c1", "c2")
        seedEdges("c1", "c2", origin = "x")
        insertEvents(pk, "c1", "c2", List(45) { WorldRelationshipBeats.OUTING })
        assertTrue(compactor.compactIfNeeded(pk, "c1", "c2", "c1", "c2", daySeed = 5L, happenedAt = 1L))
        val countAfter = dao.countEventsForPair(pk)
        val originAfter = dao.getEdge("c1", "c2")!!.origin
        // 再调：已降到 25(<=40) → no-op；count/origin 不变（不重复追加结痂句）。
        assertFalse(compactor.compactIfNeeded(pk, "c1", "c2", "c1", "c2", daySeed = 5L, happenedAt = 1L))
        assertEquals(countAfter, dao.countEventsForPair(pk))
        assertEquals(originAfter, dao.getEdge("c1", "c2")!!.origin)
    }

    // MARK: - 脚手架

    private suspend fun seedEdges(a: String, b: String, origin: String) {
        for (dir in listOf(a to b, b to a)) {
            dao.upsertEdge(
                WorldRelationshipEntity(
                    fromId = dir.first, toId = dir.second,
                    typesJson = StringListJson.encode(listOf(WorldRelationshipTypes.TYPE_ACQUAINTED)),
                    closeness = 40, trust = 40, tension = 0, colorRaw = "投缘",
                    trajectoryRaw = "stable", origin = origin, updatedAt = 0L,
                ),
            )
        }
        assertNotNull(dao.getEdge(a, b))
    }

    /** 按顺序插入事件（happenedAt 递增 = 列表顺序即时间序·最老在前）。 */
    private suspend fun insertEvents(pairKey: String, a: String, b: String, kinds: List<String>) {
        kinds.forEachIndexed { i, k ->
            dao.upsertEvent(
                WorldRelationshipEventEntity(
                    uuid = "e-$pairKey-$i", pairKey = pairKey, actorId = a, targetId = b,
                    kindRaw = k, arcId = null, summary = "s$i", happenedAt = 1000L + i, settledAt = 1000L + i,
                ),
            )
        }
    }
}
