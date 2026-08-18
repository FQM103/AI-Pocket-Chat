package com.situ.aichat.world.cast

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.world.WorldIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * [WorldRecruitService] 第五步·引荐消费 T2-3（Robolectric 真 Room·图纸 §7·契约 §8.E）：招募成功 → 首位未发现邻居
 * discovered + 燃料 +18 + 一条 `referral` 世界事件（uuid 派生）；无候选 no-op。断言从图纸 §2/§9 独立反推。UTC。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldRecruitReferralTest {

    private lateinit var db: AppDatabase
    private lateinit var service: WorldRecruitService
    private lateinit var affinity: WorldAffinityService
    private val seed = 1L
    private val day0 = 1_000_000_000_000L

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        affinity = WorldAffinityService(db.worldNativeDao(), db.worldDao())
        service = WorldRecruitService(
            db.worldNativeDao(), db.worldSocialDao(), db.worldDao(),
            CharacterRepository(
                db.characterDao(), db.milestoneDao(), db,
                WorldResidentService(db.worldUserResidentDao(), db.worldNativeDao(), db.worldDao(), db),
                io.mockk.mockk(relaxed = true),
            ),
            db, affinity, db.worldUserResidentDao(),
        )
        db.worldDao().upsertState(WorldStateEntity(seed = seed, userTimezoneId = "UTC", createdAt = 0L))
        affinity.ensureSeeded()
    }

    @After
    fun tearDown() = db.close()

    private fun id(slug: String) = WorldIds.nativeId(slug)
    private fun makeWilling(slug: String) = runBlocking {
        val def = WorldNativeRoster.bySlug(slug)!!
        db.worldNativeDao().upsert(WorldNativeStateEntity(nativeId = id(slug), discovered = true, discoveredAt = day0, narrativeFuel = 1000, currentCityId = def.cityId))
    }
    private fun referralEvents() = runBlocking { db.worldDao().getAllEvents().filter { it.kindRaw == WorldRecruitService.REFERRAL_KIND } }

    @Test
    fun `招募成功_首位未发现邻居discovered_燃料加18_referral事件恰一条`() = runBlocking {
        makeWilling("su_wan") // su_wan 首位未发现邻居 = lin_moyu（声明序）
        service.recruit(id("su_wan"), day0)!!

        val lin = db.worldNativeDao().get(id("lin_moyu"))!!
        assertTrue("首位候选被引荐 → discovered", lin.discovered)
        assertEquals("叙事燃料 +REFERRAL_FUEL(18)", WorldAffinityService.REFERRAL_FUEL, lin.narrativeFuel)

        val events = referralEvents()
        assertEquals("referral 事件恰一条", 1, events.size)
        val expectedUuid = UUID.nameUUIDFromBytes("world:referral:su_wan:lin_moyu".toByteArray()).toString()
        assertEquals(expectedUuid, events[0].uuid)
        assertEquals(WorldNativeRoster.bySlug("lin_moyu")!!.cityId, events[0].cityId)
        assertTrue(events[0].summary.contains("苏晚") && events[0].summary.contains("林陌屿"))
    }

    @Test
    fun `无候选_no_op`() = runBlocking {
        // shen_zhou 唯一出厂邻居 = lu_wangxing；先发现之 → 招募 shen_zhou 时无候选。
        affinity.discover(id("lu_wangxing"), day0)
        makeWilling("shen_zhou")
        service.recruit(id("shen_zhou"), day0)!!
        assertTrue("无候选 → 零 referral 事件", referralEvents().isEmpty())
    }
}
