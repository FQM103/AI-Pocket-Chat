package com.situ.aichat.world.travel

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import com.situ.aichat.economy.CurrencyService
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.WorldIds.TravelModes.BIKE
import com.situ.aichat.world.WorldIds.TravelModes.CAR
import com.situ.aichat.world.WorldIds.TravelModes.PLANE
import com.situ.aichat.world.WorldIds.TravelModes.WALK
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.notify.WorldNotifyService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * [WorldTravelService] T2-1/2（W7 图纸 §7·E2–E8·Robolectric 真 Room + **真 [CurrencyService]**）：出发守卫
 * （在途 / 同城 / 未初始化 / 模式不可用 / 已到达先就地结算）+ **钱路对抗基线**（余额不足 / 恰好 / 免费零调用 /
 * 崩溃回滚）。断言从图纸 §3.2/§4 独立反推；距离取自真 [WorldAtlas]（seed=1·home→city_g_yunze_2 = 50 里·带 bike·car）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldTravelServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var currency: CurrencyService
    private lateinit var notify: WorldNotifyService
    private lateinit var service: WorldTravelService
    private val seed = 1L
    private val t0 = 1_000_000_000_000L
    private val home = WorldIds.HOME_CITY_ID // city_yunye（默认当前城）
    private val near = "city_g_yunze_2" // seed=1·距 home 50 里（12<d≤60 → bike·car）

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        currency = CurrencyService(db, db.currencyDao())
        notify = mockk(relaxed = true) // W8 排期挂钩·relaxed=默认无副作用（E15 精确验参）
        service = WorldTravelService(db.worldDao(), db.characterDao(), currency, db, notify, mockk(relaxed = true))
        db.worldDao().upsertState(WorldStateEntity(seed = seed, userTimezoneId = "UTC", createdAt = 0L))
    }

    @After
    fun tearDown() = db.close()

    private fun atlas() = WorldAtlas.of(seed)
    private fun carCost() = WorldTravelPlanner.costOf(CAR, atlas().distanceLi(home, near)) // = 8（d=50）
    private suspend fun balance() = currency.userCoinBalance(t0)
    private suspend fun worldTravelTxns() = db.currencyDao().getAllTransactions().filter { it.categoryRaw == "worldTravel" }

    // MARK: - E7 出发守卫（在途 / 同城 / 未初始化）

    @Test
    fun `E7 世界未初始化_WorldNotReady`() = runBlocking {
        val db2 = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val svc = WorldTravelService(db2.worldDao(), db2.characterDao(), CurrencyService(db2, db2.currencyDao()), db2, notify, mockk(relaxed = true))
        assertTrue(svc.depart(near, BIKE, t0) is DepartResult.WorldNotReady)
        db2.close()
    }

    @Test
    fun `E7 同城出发_SameCity_零副作用`() = runBlocking {
        assertTrue(service.depart(home, BIKE, t0) is DepartResult.SameCity)
        assertNull(db.worldDao().getTravel(WorldIds.USER_ID))
    }

    @Test
    fun `E7 在途不可再出发_AlreadyTraveling_原行不动`() = runBlocking {
        val first = service.depart(near, BIKE, t0) as DepartResult.Departed
        val again = service.depart(home, BIKE, t0 + 1_000L) // 仍在途（t0+1s < arriveAt）
        assertTrue(again is DepartResult.AlreadyTraveling)
        val row = db.worldDao().getTravel(WorldIds.USER_ID)!!
        assertEquals("原行不动", near, row.toCityId)
        assertEquals(first.arriveAtMs, row.arriveAt)
    }

    // MARK: - E2 模式不在距离带 → ModeUnavailable

    @Test
    fun `E2 模式不在距离带_ModeUnavailable_零副作用`() = runBlocking {
        // d=50 带 = bike·car；walk / plane 均不在 → ModeUnavailable。
        assertTrue(service.depart(near, WALK, t0) is DepartResult.ModeUnavailable)
        assertTrue(service.depart(near, PLANE, t0) is DepartResult.ModeUnavailable)
        assertNull(db.worldDao().getTravel(WorldIds.USER_ID))
        assertTrue(worldTravelTxns().isEmpty())
    }

    // MARK: - E8 已到达未结算再出发 → 先就地结算再走新起点

    @Test
    fun `E8 已到达未结算_再出发先就地结算_按新起点走`() = runBlocking {
        val trip = service.depart(near, BIKE, t0) as DepartResult.Departed
        // 到达后（now ≥ arriveAt）从 near 再出发回 home（car）。
        val now2 = trip.arriveAtMs + 1L
        val second = service.depart(home, CAR, now2) as DepartResult.Departed
        // 就地结算把当前城更新为 near，新行起点 = near。
        assertEquals(near, db.worldDao().getState()!!.userCurrentCityId)
        val row = db.worldDao().getTravel(WorldIds.USER_ID)!!
        assertEquals(near, row.fromCityId)
        assertEquals(home, row.toCityId)
        assertEquals(CAR, row.modeRaw)
        assertEquals(now2 + second.option.durationMs, row.arriveAt)
    }

    // MARK: - E5 免费模式 → CurrencyService 全程零调用（钱路铁则）

    @Test
    fun `E5 免费模式_CurrencyService 零调用_costGold0_余额不动`() = runBlocking {
        currency.setUserCoinBalance(100, t0)
        val spy = spyk(currency)
        val svc = WorldTravelService(db.worldDao(), db.characterDao(), spy, db, notify, mockk(relaxed = true))
        val res = svc.depart(near, BIKE, t0)
        assertTrue(res is DepartResult.Departed)
        coVerify(exactly = 0) { spy.spendCoinsFromUser(any(), any(), any(), any(), any()) }
        assertEquals(0L, db.worldDao().getTravel(WorldIds.USER_ID)!!.costGold)
        assertEquals(100, balance())
        assertTrue("免费模式不写流水", worldTravelTxns().isEmpty())
    }

    // MARK: - E4 余额恰好等于票价 → 扣至 0 成功

    @Test
    fun `E4 余额恰好=票价_扣至0成功出发`() = runBlocking {
        val cost = carCost()
        currency.setUserCoinBalance(cost, t0)
        val res = service.depart(near, CAR, t0)
        assertTrue(res is DepartResult.Departed)
        assertEquals(0, balance())
        assertEquals(cost.toLong(), db.worldDao().getTravel(WorldIds.USER_ID)!!.costGold)
        // 一条「世界旅行」SPEND 流水·note=前往{城名}·relatedId=destCityId。
        val txn = worldTravelTxns().single()
        assertEquals("spend", txn.kindRaw)
        assertEquals(cost, txn.amount)
        assertEquals(near, txn.relatedEntityId)
        val cityName = atlas().cityById(near)!!.name
        assertEquals("前往$cityName", txn.note)
    }

    // MARK: - E3 余额不足 → InsufficientGold·零扣款零落行

    @Test
    fun `E3 余额不足_InsufficientGold_零扣款零落行`() = runBlocking {
        val cost = carCost()
        currency.setUserCoinBalance(cost - 1, t0)
        val res = service.depart(near, CAR, t0)
        assertTrue(res is DepartResult.InsufficientGold)
        res as DepartResult.InsufficientGold
        assertEquals(cost, res.need)
        assertEquals(cost - 1, res.have)
        assertEquals("余额分毫未动", cost - 1, balance())
        assertNull("零落行", db.worldDao().getTravel(WorldIds.USER_ID))
        assertTrue("流水无记录", worldTravelTxns().isEmpty())
    }

    // MARK: - E6 扣款后落行前崩溃 → 全事务回滚

    @Test
    fun `E6 落行前抛异常_全事务回滚_余额未动无行无流水`() = runBlocking {
        currency.setUserCoinBalance(100, t0)
        val spyWorld = spyk(db.worldDao())
        coEvery { spyWorld.upsertTravel(any()) } throws RuntimeException("boom")
        val svc = WorldTravelService(spyWorld, db.characterDao(), currency, db, notify, mockk(relaxed = true))
        var threw = false
        try {
            svc.depart(near, CAR, t0)
        } catch (e: RuntimeException) {
            threw = true
        }
        assertTrue("应抛异常", threw)
        assertEquals("扣款回滚", 100, balance())
        assertNull("无行", db.worldDao().getTravel(WorldIds.USER_ID))
        assertTrue("流水回滚", worldTravelTxns().isEmpty())
    }

    // ────────────────────────────────────────────────────────────────────────────
    // chunk 3：邀请来访 / 到达结算 / 返程 / 位置查询（T2-3..6·E9–E15/E17）
    // ────────────────────────────────────────────────────────────────────────────

    private suspend fun joinChar(uuid: String, name: String, homeCityId: String) =
        db.characterDao().upsert(
            CharacterEntity(uuid = uuid, name = name, creationDate = t0, joinedWorld = true, worldHomeCityId = homeCityId),
        )

    // MARK: - E9 用户到达结算（位置更新·行删·重跑幂等）

    @Test
    fun `E9 用户到达_当前城更新_行删_重跑幂等`() = runBlocking {
        val trip = service.depart(near, BIKE, t0) as DepartResult.Departed
        service.settleArrivals(trip.arriveAtMs + 1L)
        assertEquals(near, db.worldDao().getState()!!.userCurrentCityId)
        assertNull(db.worldDao().getTravel(WorldIds.USER_ID))
        // 重跑幂等：位置不变·仍无行。
        service.settleArrivals(trip.arriveAtMs + 1_000_000L)
        assertEquals(near, db.worldDao().getState()!!.userCurrentCityId)
        assertNull(db.worldDao().getTravel(WorldIds.USER_ID))
    }

    // MARK: - E10 邀请守卫（未入世 / 在途中 / 同城 → 零副作用）

    @Test
    fun `E10 邀请守卫_未入世_在途中_同城_均零副作用`() = runBlocking {
        // 角色查无 → NotInWorld。
        assertTrue(service.invite("ghost", t0) is InviteResult.NotInWorld)
        // 未入世（joinedWorld=false）→ NotInWorld。
        db.characterDao().upsert(CharacterEntity(uuid = "c_out", name = "阿离", creationDate = t0, joinedWorld = false, worldHomeCityId = near))
        assertTrue(service.invite("c_out", t0) is InviteResult.NotInWorld)
        // 同城（家 = 用户当前城 home）→ SameCity。
        joinChar("c_same", "同城", home)
        assertTrue(service.invite("c_same", t0) is InviteResult.SameCity)
        // 已有行 → AlreadyOnTheWay。
        joinChar("c_busy", "在路上", near)
        service.invite("c_busy", t0)
        assertTrue(service.invite("c_busy", t0 + 1000L) is InviteResult.AlreadyOnTheWay)
        assertTrue("守卫零落用户行", db.worldDao().getTravel(WorldIds.USER_ID) == null)
        assertTrue("零世界事件", db.worldDao().getAllEvents().isEmpty())
    }

    // MARK: - E11–E14 全链：邀请 → 到达来访 → 次日返程 → 到家（含钱路零碰）

    @Test
    fun `E11-14 全链_邀请零扣款_来访事件_次日返程_到家行删`() = runBlocking {
        val c = "c_wan"
        joinChar(c, "小晚", near) // 家 = near·用户当前城 = home
        currency.setUserCoinBalance(100, t0)
        val spy = spyk(currency)
        val svc = WorldTravelService(db.worldDao(), db.characterDao(), spy, db, notify, mockk(relaxed = true))
        val dist = atlas().distanceLi(near, home) // 50
        val carDur = WorldTravelPlanner.durationOf(CAR, dist)

        // E11 邀请成行：耗时最短档（car）·零扣款（两侧钱包分毫不动）·行 from=家 to=用户当前城。
        val inv = svc.invite(c, t0) as InviteResult.Invited
        coVerify(exactly = 0) { spy.spendCoinsFromUser(any(), any(), any(), any(), any()) }
        val row = db.worldDao().getTravel(c)!!
        assertEquals(near, row.fromCityId)
        assertEquals(home, row.toCityId)
        assertEquals(CAR, row.modeRaw)
        assertEquals(0L, row.costGold)
        assertEquals(t0, row.departAt)
        assertEquals(t0 + carDur, row.arriveAt)
        assertEquals(t0 + carDur, inv.arriveAtMs)
        assertEquals("用户钱包不动", 100, currency.userCoinBalance(t0))
        assertNull("角色钱包未创建（分毫不动）", db.currencyDao().getCharacterWallet(c))

        val arriveAt = row.arriveAt
        // E12 来访到达（同一本地日）：落 visit 事件·无返程·重跑不重复。
        svc.settleArrivals(arriveAt + 60_000L)
        val evUuid = UUID.nameUUIDFromBytes("world:visit:$c:$t0".toByteArray()).toString()
        val ev = db.worldDao().getAllEvents().single { it.kindRaw == WorldTravelService.VISIT_KIND }
        assertEquals(evUuid, ev.uuid)
        assertEquals("小晚到了${atlas().cityById(home)!!.name}——TA 说，就是想来看看你", ev.summary)
        assertEquals(home, ev.cityId)
        assertEquals(arriveAt, ev.happenedAt)
        assertEquals(listOf(WorldIds.USER_ID, c), StringListJson.decode(ev.involvedIdsJson))
        // 仍在来访（未返程）：presence = 用户城·行仍来访腿。
        assertEquals(home, svc.characterPresence(c, arriveAt + 60_000L).cityId)
        assertEquals(home, db.worldDao().getTravel(c)!!.toCityId)
        svc.settleArrivals(arriveAt + 120_000L)
        assertEquals("重跑不重复", 1, db.worldDao().getAllEvents().count { it.kindRaw == WorldTravelService.VISIT_KIND })

        // E13 次日返程：本地日跨天才生成·departAt=次日 09:00 本地·同模式·costGold=0·重跑同值。
        val nextDay = arriveAt + 86_400_000L
        svc.settleArrivals(nextDay)
        val ret = db.worldDao().getTravel(c)!!
        assertEquals(home, ret.fromCityId)
        assertEquals(near, ret.toCityId)
        assertEquals(CAR, ret.modeRaw)
        assertEquals(0L, ret.costGold)
        val expectedDepart = LocalDate.ofInstant(Instant.ofEpochMilli(arriveAt), ZoneOffset.UTC)
            .plusDays(1).atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedDepart, ret.departAt)
        assertEquals(expectedDepart + carDur, ret.arriveAt)
        svc.settleArrivals(nextDay + 1000L) // 返程未到 → 行不动（重跑同值）
        assertEquals(expectedDepart, db.worldDao().getTravel(c)!!.departAt)

        // E14 返程到家：行删·presence 回家乡·全链闭合。
        svc.settleArrivals(ret.arriveAt + 1000L)
        assertNull(db.worldDao().getTravel(c))
        assertEquals(near, svc.characterPresence(c, ret.arriveAt + 2000L).cityId)
        // 全程零扣款（钱路铁则）。
        coVerify(exactly = 0) { spy.spendCoinsFromUser(any(), any(), any(), any(), any()) }
        assertNull(db.currencyDao().getCharacterWallet(c))
    }

    // MARK: - E13 未跨天不生成返程（同一本地日多次结算·角色继续来访）

    @Test
    fun `E13 同一本地日_不生成返程_角色继续来访`() = runBlocking {
        val c = "c_stay"
        joinChar(c, "留客", near)
        val inv = service.invite(c, t0) as InviteResult.Invited
        // 到达后同一本地日多次结算：始终来访腿（to=用户城）·无返程。
        service.settleArrivals(inv.arriveAtMs + 1L)
        service.settleArrivals(inv.arriveAtMs + 3_600_000L) // 1 小时后·仍同日
        val row = db.worldDao().getTravel(c)!!
        assertEquals("仍来访腿", home, row.toCityId)
        assertEquals(near, row.fromCityId)
    }

    // MARK: - E15 位置三态（用户与角色两域·逐点）

    @Test
    fun `E15 位置三态_用户与角色两域`() = runBlocking {
        val c = "c_pres"
        joinChar(c, "定位", near)
        // 用户行：departAt=100 arriveAt=200（手工造行精确覆盖「未出发」态）。
        db.worldDao().upsertTravel(WorldTravelEntity(WorldIds.USER_ID, home, near, 100L, 200L, CAR, 0L))
        assertEquals(WorldPresence(home, null, null), service.userPresence(50L))    // 未出发 → from
        assertEquals(WorldPresence(home, near, 200L), service.userPresence(150L))   // 在途 → from+目的地+到达时刻
        assertEquals(WorldPresence(near, null, null), service.userPresence(250L))   // 已到 → to
        db.worldDao().deleteTravel(WorldIds.USER_ID)
        assertEquals(WorldPresence(home, null, null), service.userPresence(999L))   // 无行 → 当前城
        // 角色行：near→home。
        db.worldDao().upsertTravel(WorldTravelEntity(c, near, home, 100L, 200L, CAR, 0L))
        assertEquals(WorldPresence(near, null, null), service.characterPresence(c, 50L))
        assertEquals(WorldPresence(near, home, 200L), service.characterPresence(c, 150L))
        assertEquals(WorldPresence(home, null, null), service.characterPresence(c, 250L))
        db.worldDao().deleteTravel(c)
        assertEquals(WorldPresence(near, null, null), service.characterPresence(c, 999L)) // 无行 → 家乡
        // isUserTraveling：仅在途为真。
        db.worldDao().upsertTravel(WorldTravelEntity(WorldIds.USER_ID, home, near, 100L, 200L, CAR, 0L))
        assertFalse(service.isUserTraveling(50L))
        assertTrue(service.isUserTraveling(150L))
        assertFalse(service.isUserTraveling(250L))
        db.worldDao().deleteTravel(WorldIds.USER_ID)
        assertFalse(service.isUserTraveling(999L))
    }

    // MARK: - E17 设备时间回拨（三态按 now·不产生二次扣款/错误事件）

    @Test
    fun `E17 时间回拨_不二次扣款_位置回出发地_无错误事件`() = runBlocking {
        currency.setUserCoinBalance(100, t0)
        val trip = service.depart(near, CAR, t0) as DepartResult.Departed
        val afterDepart = balance()
        // 回拨到 departAt 之前：结算不推进（未到达）·无扣款·无事件。
        service.settleArrivals(t0 - 10_000L)
        assertEquals("无二次扣款", afterDepart, balance())
        assertEquals("单笔扣款", 1, worldTravelTxns().size)
        assertTrue("无来访事件", db.worldDao().getAllEvents().isEmpty())
        // 位置：now < departAt → 显示还在出发地（home）·目的地城未更新。
        assertEquals(home, service.userPresence(t0 - 10_000L).cityId)
        assertEquals(home, db.worldDao().getState()!!.userCurrentCityId)
        assertTrue("行仍在", db.worldDao().getTravel(WorldIds.USER_ID) != null)
    }

    // MARK: - R1 🔴-1 invite 行感知（用户到达未结算先就地结算 / 用户在途挡）

    @Test
    fun `R1-red1 用户到达未结算再邀请_先就地结算_按新城判`() = runBlocking {
        // 用户 depart 到 near 并越过 arriveAt（行未结算·当天前台窗为空 Runner 未跑 step3.5）。
        val trip = service.depart(near, BIKE, t0) as DepartResult.Departed
        val after = trip.arriveAtMs + 1L
        // 邀请家在旧城（home=city_yunye）的角色 → 应先把用户结算到 near·TA 派往 near（非旧城）。
        joinChar("c_far", "远客", home)
        val inv = service.invite("c_far", after) as InviteResult.Invited
        assertEquals("用户已就地结算为新城", near, db.worldDao().getState()!!.userCurrentCityId)
        assertEquals("TA 派往新城 near（非旧城 home）", near, db.worldDao().getTravel("c_far")!!.toCityId)
        assertNull("用户行已结算清除", db.worldDao().getTravel(WorldIds.USER_ID))
        // 家 = near 的角色 → 按新城判为 SameCity。
        joinChar("c_near", "近邻", near)
        assertTrue(service.invite("c_near", after) is InviteResult.SameCity)
    }

    @Test
    fun `R1-red1 用户在途邀请_UserTraveling_零角色行零事件零扣款`() = runBlocking {
        currency.setUserCoinBalance(100, t0)
        val spy = spyk(currency)
        val svc = WorldTravelService(db.worldDao(), db.characterDao(), spy, db, notify, mockk(relaxed = true))
        svc.depart(near, BIKE, t0) // 免费·在途
        joinChar("c_wait", "等客", near)
        val res = svc.invite("c_wait", t0 + 1000L) // arriveAt 之前（在途）
        assertTrue(res is InviteResult.UserTraveling)
        assertNull("零角色行", db.worldDao().getTravel("c_wait"))
        assertTrue("零世界事件", db.worldDao().getAllEvents().isEmpty())
        coVerify(exactly = 0) { spy.spendCoinsFromUser(any(), any(), any(), any(), any()) } // 零扣款
    }

    // MARK: - R1 🟡-1 visit 事件「存在即跳过」（重复结算不清 notifiedAt/seenAt）

    @Test
    fun `R1-yellow1 visit 事件存在即跳过_重复结算保留 notifiedAt 与 seenAt`() = runBlocking {
        val c = "c_once"
        joinChar(c, "小晚", near)
        val inv = service.invite(c, t0) as InviteResult.Invited
        service.settleArrivals(inv.arriveAtMs + 60_000L) // 落 visit 事件
        val uuid = UUID.nameUUIDFromBytes("world:visit:$c:$t0".toByteArray()).toString()
        // 模拟 W5/W8 消费：标记已看/已通知。
        db.worldDao().markEventSeen(uuid, 888L)
        db.worldDao().markEventNotified(uuid, 999L)
        // 同日再结算 + 跨天生成返程那一趟——landVisitEvent 会再被调，但「存在即跳过」不重写。
        service.settleArrivals(inv.arriveAtMs + 120_000L)
        service.settleArrivals(inv.arriveAtMs + 86_400_000L)
        val events = db.worldDao().getAllEvents().filter { it.kindRaw == WorldTravelService.VISIT_KIND }
        assertEquals("仍 1 条 visit 事件", 1, events.size)
        assertEquals("seenAt 保留未被清", 888L, events.single().seenAt)
        assertEquals("notifiedAt 保留未被清", 999L, events.single().notifiedAt)
        // 返程确已生成（跨天那趟正常走·守卫只挡事件重写不挡返程）。
        assertEquals(near, db.worldDao().getTravel(c)!!.toCityId)
    }

    // ────────────────────────────────────────────────────────────────────────────
    // E15 W8 排期接线（invite/depart 成功才排·守卫路径全不排·排期抛异常不拦旅行结果）
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `E15 出发成功_排用户到达_精确到arriveAt`() = runBlocking {
        val trip = service.depart(near, BIKE, t0) as DepartResult.Departed
        coVerify(exactly = 1) { notify.onUserDeparted(trip.arriveAtMs) }
        coVerify(exactly = 0) { notify.onVisitInvited(any(), any()) }
    }

    @Test
    fun `E15 邀请成功_排来访到达_精确到charUuid与arriveAt`() = runBlocking {
        val c = "c_inv"
        joinChar(c, "小晚", near)
        val inv = service.invite(c, t0) as InviteResult.Invited
        coVerify(exactly = 1) { notify.onVisitInvited(c, inv.arriveAtMs) }
        coVerify(exactly = 0) { notify.onUserDeparted(any()) }
    }

    @Test
    fun `E15 守卫路径全不排期`() = runBlocking {
        // 出发守卫：同城 / 模式不可用 / 余额不足。
        service.depart(home, BIKE, t0) // SameCity
        service.depart(near, WALK, t0) // ModeUnavailable
        currency.setUserCoinBalance(carCost() - 1, t0)
        service.depart(near, CAR, t0) // InsufficientGold
        // 邀请守卫：未入世 / 同城 / 用户在途。
        service.invite("ghost", t0) // NotInWorld
        joinChar("c_same", "同城", home)
        service.invite("c_same", t0) // SameCity
        service.depart(near, BIKE, t0 + 1L) // 用户在途（此次成功排 1 次 depart）
        joinChar("c_wait", "等客", near)
        service.invite("c_wait", t0 + 2L) // UserTraveling（用户在途 → 不排）
        coVerify(exactly = 0) { notify.onVisitInvited(any(), any()) } // 全部邀请守卫零排期
    }

    @Test
    fun `E15 排期抛异常_不拦出发结果_行照落`() = runBlocking {
        coEvery { notify.onUserDeparted(any()) } throws RuntimeException("boom")
        val res = service.depart(near, BIKE, t0)
        assertTrue("排期失败仍返回 Departed", res is DepartResult.Departed)
        assertEquals("行照落", near, db.worldDao().getTravel(WorldIds.USER_ID)!!.toCityId)
    }

    @Test
    fun `E15 邀请排期抛异常_不拦邀请结果`() = runBlocking {
        coEvery { notify.onVisitInvited(any(), any()) } throws RuntimeException("boom")
        val c = "c_inv2"
        joinChar(c, "小晚", near)
        val res = service.invite(c, t0)
        assertTrue("排期失败仍返回 Invited", res is InviteResult.Invited)
        assertEquals("角色行照落", home, db.worldDao().getTravel(c)!!.toCityId)
    }
}
