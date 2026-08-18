package com.situ.aichat.ui.world

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.economy.CurrencyService
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.travel.DepartResult
import com.situ.aichat.world.travel.WorldTravelPlanner
import com.situ.aichat.world.travel.WorldTravelService
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
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
 * [WorldTravelSheetViewModel] T2-4（W9d 图纸 §7·E8 报价 vs W7 金标独立复算 / E9 depart 四态真库真 CurrencyService /
 * E10 扑空检测）。seed=1·home=city_yunye·near=city_g_yunze_2（d=50·bike/car 带·同 W7 测）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldTravelSheetViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var currency: CurrencyService
    private lateinit var vm: WorldTravelSheetViewModel

    private val seed = 1L
    private val t0 = 1_000_000_000_000L
    private val home = WorldIds.HOME_CITY_ID
    private val near = "city_g_yunze_2"

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        currency = CurrencyService(db, db.currencyDao())
        val travel = WorldTravelService(db.worldDao(), db.characterDao(), currency, db, mockk(relaxed = true), mockk(relaxed = true))
        vm = WorldTravelSheetViewModel(db.worldDao(), db.characterDao(), travel)
        db.worldDao().upsertState(WorldStateEntity(seed = seed, userTimezoneId = "UTC", userCurrentCityId = home, createdAt = 0L))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun balance() = currency.userCoinBalance(t0)
    private fun dist() = WorldAtlas.of(seed).distanceLi(home, near)

    // ---- E8 报价 vs W7 金标独立复算 ----

    @Test
    fun `E8 报价选项票价耗时与planner一致`() = runBlocking {
        val q = vm.quote(near, t0)!!
        val d = dist()
        assertEquals(d, q.distanceLi)
        assertEquals(WorldTravelPlanner.optionsFor(d).size, q.options.size)
        q.options.forEach { o ->
            assertEquals("票价独立复算 ${o.mode}", WorldTravelPlanner.costOf(o.mode, d), o.costGold)
            assertEquals("耗时独立复算 ${o.mode}", WorldTravelPlanner.durationOf(o.mode, d), o.durationMs)
        }
        assertTrue(q.destName.isNotEmpty()); assertTrue(q.fromName.isNotEmpty())
    }

    @Test
    fun `E8 同城报价null`() = runBlocking {
        assertNull(vm.quote(home, t0))
    }

    // ---- E9 depart 四态 ----

    @Test
    fun `E9 Departed_扣款落行`() = runBlocking {
        val cost = WorldTravelPlanner.costOf(WorldIds.TravelModes.CAR, dist())
        val before = balance() // 默认起始 100
        val r = vm.depart(near, WorldIds.TravelModes.CAR, t0)
        assertTrue(r is DepartResult.Departed)
        assertEquals(before - cost, balance())
        assertEquals(near, db.worldDao().getTravel(WorldIds.USER_ID)!!.toCityId)
    }

    @Test
    fun `E9 InsufficientGold_零扣款零落行`() = runBlocking {
        currency.spendCoinsFromUser(balance(), CurrencyTransactionCategory.WORLD_TRAVEL, now = t0) // 花光到 0
        assertEquals(0, balance())
        val r = vm.depart(near, WorldIds.TravelModes.CAR, t0) // 付费 car·余额 0
        assertTrue(r is DepartResult.InsufficientGold)
        assertEquals(0, balance()) // 余额不动
        assertNull(db.worldDao().getTravel(WorldIds.USER_ID)) // 无行
    }

    @Test
    fun `E9 SameCity`() = runBlocking {
        assertTrue(vm.depart(home, WorldIds.TravelModes.BIKE, t0) is DepartResult.SameCity)
    }

    @Test
    fun `E9 AlreadyTraveling`() = runBlocking {
        assertTrue(vm.depart(near, WorldIds.TravelModes.BIKE, t0) is DepartResult.Departed) // bike 免费
        assertTrue(vm.depart(near, WorldIds.TravelModes.BIKE, t0 + 1000) is DepartResult.AlreadyTraveling)
    }

    // ---- E10 扑空检测 ----

    @Test
    fun `E10 他人来当前城未到_扑空提示名`() = runBlocking {
        db.characterDao().upsert(CharacterEntity(uuid = "cv", name = "阿来", creationDate = 0L, joinedWorld = true, worldHomeCityId = near))
        db.worldDao().upsertTravel(WorldTravelEntity(ownerId = "cv", fromCityId = near, toCityId = home, departAt = t0 - 5000, arriveAt = t0 + 10_000, modeRaw = "car"))
        assertEquals("阿来", vm.quote(near, t0)!!.visitorName)
    }

    @Test
    fun `E10 无来客_visitorName为null`() = runBlocking {
        assertNull(vm.quote(near, t0)!!.visitorName)
    }
}
