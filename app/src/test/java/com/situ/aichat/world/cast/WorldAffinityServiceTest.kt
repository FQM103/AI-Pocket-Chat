package com.situ.aichat.world.cast

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
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
 * [WorldAffinityService] T2-1/2（W6 图纸 §7·E2 播种幂等 / E3 同本地日偶遇去重 / E4 不衰减 / E7 未发现 no-op·
 * Robolectric 真 Room + 真 DAO）。全程 UTC 令本地日无歧义（+86400000ms = 恰跨一 UTC 日）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldAffinityServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var nativeDao: WorldNativeDao
    private lateinit var worldDao: WorldDao
    private lateinit var service: WorldAffinityService

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        nativeDao = db.worldNativeDao()
        worldDao = db.worldDao()
        service = WorldAffinityService(nativeDao, worldDao)
    }

    @After
    fun tearDown() = db.close()

    private fun seedWorld() = runBlocking {
        worldDao.upsertState(WorldStateEntity(seed = 1L, userTimezoneId = "UTC", createdAt = 0L))
    }

    private fun id(slug: String) = WorldIds.nativeId(slug)
    private val day0 = 1_000_000_000_000L // 2001-09-09T01:46:40Z

    // MARK: - E2 播种幂等

    @Test
    fun `E2 空库播种_20 行全新_currentCityId=家城`() = runBlocking {
        service.ensureSeeded()
        val all = nativeDao.getAll()
        assertEquals(20, all.size)
        assertTrue("应全未发现·零燃料", all.all { !it.discovered && it.narrativeFuel == 0 && it.giftFuel == 0 })
        for (def in WorldNativeRoster.ALL) {
            assertEquals("${def.slug} currentCityId", def.cityId, nativeDao.get(id(def.slug))!!.currentCityId)
        }
    }

    @Test
    fun `E2 二次播种_已有燃料行绝不被重置`() = runBlocking {
        service.ensureSeeded()
        nativeDao.upsert(nativeDao.get(id("su_wan"))!!.copy(discovered = true, narrativeFuel = 50, giftFuel = 20))
        service.ensureSeeded() // 二次
        val s = nativeDao.get(id("su_wan"))!!
        assertEquals(20, nativeDao.getAll().size) // 不多不少
        assertEquals(50, s.narrativeFuel) // 绝不重置
        assertEquals(20, s.giftFuel)
        assertTrue(s.discovered)
    }

    @Test
    fun `E2 只补缺行_预置单行保留_其余新增`() = runBlocking {
        // 模拟「新版本追加 def」的反面：先只有 su_wan 一行（带燃料），播种应只补另 19 行。
        nativeDao.upsert(WorldNativeStateEntity(nativeId = id("su_wan"), discovered = true, narrativeFuel = 99))
        service.ensureSeeded()
        assertEquals(20, nativeDao.getAll().size)
        assertEquals(99, nativeDao.get(id("su_wan"))!!.narrativeFuel) // 预置行保留
        assertEquals(0, nativeDao.get(id("lin_moyu"))!!.narrativeFuel) // 新补行全新
    }

    // MARK: - E3 同本地日偶遇去重

    @Test
    fun `E3 同 UTC 日重复偶遇_只更时间戳_跨日正常累计`() = runBlocking {
        seedWorld(); service.ensureSeeded()
        val nid = id("su_wan")
        service.recordEncounter(nid, day0)
        nativeDao.get(nid)!!.let {
            assertTrue(it.discovered); assertEquals(1, it.encounterCount)
            assertEquals(WorldAffinityService.ENCOUNTER_FUEL, it.narrativeFuel); assertEquals(day0, it.lastEncounterAt)
        }
        // 同日 +1h：只更时间戳，燃料/计数不动。
        val sameDay = day0 + 3_600_000L
        service.recordEncounter(nid, sameDay)
        nativeDao.get(nid)!!.let {
            assertEquals(1, it.encounterCount)
            assertEquals(WorldAffinityService.ENCOUNTER_FUEL, it.narrativeFuel); assertEquals(sameDay, it.lastEncounterAt)
        }
        // 次日：正常累计。
        val nextDay = day0 + 86_400_000L
        service.recordEncounter(nid, nextDay)
        nativeDao.get(nid)!!.let {
            assertEquals(2, it.encounterCount)
            assertEquals(WorldAffinityService.ENCOUNTER_FUEL * 2, it.narrativeFuel); assertEquals(nextDay, it.lastEncounterAt)
        }
    }

    // MARK: - E4 不衰减（重复播种 / 反复读后 affinity 不变）

    @Test
    fun `E4 攒燃料后_重复播种与再读 affinity 一分不掉`() = runBlocking {
        seedWorld(); service.ensureSeeded()
        val nid = id("su_wan"); val def = WorldNativeRoster.bySlug("su_wan")!!
        // 攒：偶遇 3 个不同日 + 送心意。
        repeat(3) { d -> service.recordEncounter(nid, day0 + d * 86_400_000L) }
        service.addGiftFuel(nid, 25)
        val before = WorldAffinityService.affinityOf(nativeDao.get(nid)!!, def)
        assertTrue("应已积攒", before > 0)
        // 反复播种 + 再读（全库无衰减路径）→ 不变。
        repeat(5) { service.ensureSeeded() }
        assertEquals(before, WorldAffinityService.affinityOf(nativeDao.get(nid)!!, def))
        assertEquals(WorldAffinityService.ENCOUNTER_FUEL * 3, nativeDao.get(nid)!!.narrativeFuel)
        assertEquals(25, nativeDao.get(nid)!!.giftFuel)
    }

    // MARK: - E7 未发现 no-op / 引荐已发现 no-op

    @Test
    fun `E7 未发现加燃料 no-op_points≤0 no-op`() = runBlocking {
        seedWorld(); service.ensureSeeded()
        val nid = id("su_wan")
        service.addNarrativeFuel(nid, 10) // 未发现 → no-op
        service.addGiftFuel(nid, 10)      // 未发现 → no-op
        nativeDao.get(nid)!!.let { assertEquals(0, it.narrativeFuel); assertEquals(0, it.giftFuel); assertFalse(it.discovered) }
        // 发现后：points≤0 no-op，正数才加。
        service.discover(nid, day0)
        service.addNarrativeFuel(nid, 0); service.addNarrativeFuel(nid, -5)
        assertEquals(0, nativeDao.get(nid)!!.narrativeFuel)
        service.addNarrativeFuel(nid, 7)
        assertEquals(7, nativeDao.get(nid)!!.narrativeFuel)
    }

    @Test
    fun `E7 引荐未发现=discover+18_已发现引荐 no-op`() = runBlocking {
        seedWorld(); service.ensureSeeded()
        val nid = id("mu_xing")
        service.introduce(nid, day0) // 未发现 → discover + REFERRAL_FUEL
        nativeDao.get(nid)!!.let {
            assertTrue(it.discovered); assertEquals(WorldAffinityService.REFERRAL_FUEL, it.narrativeFuel)
        }
        service.introduce(nid, day0 + 1000L) // 已发现 → no-op（只可被引荐一次）
        assertEquals(WorldAffinityService.REFERRAL_FUEL, nativeDao.get(nid)!!.narrativeFuel)
    }
}
