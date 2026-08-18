package com.situ.aichat.world.stage

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.cast.WorldAffinityService
import com.situ.aichat.world.travel.WorldTravelService
import io.mockk.mockk
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [WorldStageService] T2-1/T2-2（W9d 图纸 §7·Robolectric 真 Room + 真 DAO·真 WorldAffinityService/WorldTravelService）。
 *
 * E3 站位快照 / E4 原住民态 / E5 睡眠模式 / E12 宠物上限（T2-1）· E6 W6 触发 gate + 燃料去重（T2-2）。
 * 全程 UTC 时区令本地日无歧义。CurrencyService/WorldNotifyService 用 relaxed mock（本测不触旅行扣款/通知路径）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldStageServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var service: WorldStageService
    private lateinit var affinity: WorldAffinityService

    private val nowMs = LocalDateTime.of(2026, 6, 15, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val dayStart = LocalDate.of(2026, 6, 15).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val yunye = "city_yunye"
    private val taoqiu = "city_taoqiu"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val travel = WorldTravelService(db.worldDao(), db.characterDao(), mockk(relaxed = true), db, mockk(relaxed = true), mockk(relaxed = true))
        affinity = WorldAffinityService(db.worldNativeDao(), db.worldDao())
        service = WorldStageService(
            db.characterDao(), db.scheduleDao(), db.worldNativeDao(), db.petDao(),
            db.worldDao(), travel, affinity,
        )
    }

    @After
    fun tearDown() = db.close()

    // ---- 建材 ----

    private fun seedWorld(homeCity: String = yunye) = runBlocking {
        db.worldDao().upsertState(
            WorldStateEntity(seed = 42L, userTimezoneId = "UTC", userHomeCityId = homeCity, userCurrentCityId = homeCity, createdAt = 0L),
        )
    }

    private fun joinChar(uuid: String, name: String, home: String, joined: Boolean = true) = runBlocking {
        db.characterDao().upsert(
            CharacterEntity(uuid = uuid, name = name, creationDate = 0L, joinedWorld = joined, worldHomeCityId = home, avatarPath = "/a/$uuid.png"),
        )
    }

    private fun putSchedule(uuid: String, location: String, activity: String, period: String = "上午", phoneAvailable: Boolean = true) = runBlocking {
        val schedUuid = UUID.randomUUID().toString()
        db.scheduleDao().insertScheduleWithEvents(
            CharacterDailyScheduleEntity(uuid = schedUuid, characterUuid = uuid, date = dayStart, generatedAt = 1L),
            listOf(
                ScheduleEventEntity(
                    uuid = UUID.randomUUID().toString(), scheduleUuid = schedUuid,
                    startTime = nowMs - 3_600_000L, endTime = nowMs + 3_600_000L,
                    periodLabel = period, location = location, activity = activity, isPhoneAvailable = phoneAvailable,
                ),
            ),
        )
    }

    private fun putTravel(ownerId: String, from: String, to: String, departAt: Long, arriveAt: Long) = runBlocking {
        db.worldDao().upsertTravel(WorldTravelEntity(ownerId = ownerId, fromCityId = from, toCityId = to, departAt = departAt, arriveAt = arriveAt, modeRaw = "car"))
    }

    private fun putPet(name: String, adoptedDate: Long) = runBlocking {
        val owner = "owner_${UUID.randomUUID()}"
        db.characterDao().upsert(CharacterEntity(uuid = owner, name = "主人$name", creationDate = 0L, joinedWorld = false, worldHomeCityId = yunye))
        db.petDao().upsert(CharacterPetEntity(uuid = "pet_$name", name = name, characterUuid = owner, adoptedDate = adoptedDate))
    }

    private fun cast(city: String = yunye) = runBlocking { service.castOf(city, nowMs) }

    // ---- E3 站位快照 ----

    @Test
    fun `E3 日程在咖啡馆_AT_PLACE cafe_statusLine拼活动行`() {
        seedWorld(); joinChar("c1", "小南", yunye); putSchedule("c1", "在拾光咖啡馆画画", "画一幅画")
        val c = cast().characters.single { it.uuid == "c1" }
        assertEquals(StageMode.AT_PLACE, c.mode)
        assertEquals("yunye_cafe", c.placeId)
        assertEquals("上午·画一幅画", c.statusLine)
        assertFalse(c.visiting)
    }

    @Test
    fun `E3 睡眠事件_SLEEPING`() {
        seedWorld(); joinChar("c1", "小南", yunye); putSchedule("c1", "家里", "睡觉", phoneAvailable = false)
        val c = cast().characters.single { it.uuid == "c1" }
        assertEquals(StageMode.SLEEPING, c.mode)
        assertNull(c.placeId)
        assertEquals("", c.statusLine)
    }

    @Test
    fun `E3 无日程_IN_TOWN固定文案`() {
        seedWorld(); joinChar("c1", "小南", yunye)
        val c = cast().characters.single { it.uuid == "c1" }
        assertEquals(StageMode.IN_TOWN, c.mode)
        assertEquals("", c.statusLine) // 固定文案由 UI 层解析
    }

    @Test
    fun `E3 在途角色两镇都不出现`() {
        seedWorld(); joinChar("c1", "小南", yunye)
        putTravel("c1", yunye, taoqiu, departAt = nowMs - 1000L, arriveAt = nowMs + 10_000L) // 在途
        assertTrue(cast(yunye).characters.none { it.uuid == "c1" })
        assertTrue(cast(taoqiu).characters.none { it.uuid == "c1" })
    }

    @Test
    fun `E3 visiting恒IN_TOWN_不解析异城日程`() {
        seedWorld(); joinChar("c1", "远客", taoqiu) // 家在陶丘
        putTravel("c1", taoqiu, yunye, departAt = nowMs - 10_000L, arriveAt = nowMs - 1000L) // 已到云野
        putSchedule("c1", "在拾光咖啡馆画画", "画画") // 即便日程指向 cafe，visiting 也不解析
        val c = cast(yunye).characters.single { it.uuid == "c1" }
        assertEquals(StageMode.IN_TOWN, c.mode)
        assertTrue(c.visiting)
        assertNull(c.placeId)
        assertEquals("", c.statusLine)
    }

    @Test
    fun `E3 presence异城_不上本镇`() {
        seedWorld(); joinChar("c1", "陶客", taoqiu) // 无旅行行 → presence = 家城陶丘
        assertTrue(cast(yunye).characters.none { it.uuid == "c1" })
        assertTrue(cast(taoqiu).characters.any { it.uuid == "c1" })
    }

    @Test
    fun `E3 活动截14字加省略号`() {
        seedWorld(); joinChar("c1", "小南", yunye)
        putSchedule("c1", "在拾光咖啡馆", "一二三四五六七八九十甲乙丙丁戊", period = "") // 15 字 → 截 14 + …
        val c = cast().characters.single { it.uuid == "c1" }
        assertEquals("一二三四五六七八九十甲乙丙丁…", c.statusLine) // periodLabel 空则只 activity
    }

    // ---- E4 原住民态 ----

    private fun yunyeNativeIds() = runBlocking {
        affinity.ensureSeeded()
        db.worldNativeDao().getAll().filter { it.currentCityId == yunye }.map { it.nativeId }
    }

    @Test
    fun `E4 未招募城匹配上镇_discovered分名神秘`() {
        seedWorld()
        val ids = yunyeNativeIds()
        assertTrue("云野应有原住民", ids.size >= 2)
        runBlocking {
            val first = db.worldNativeDao().get(ids[0])!!
            db.worldNativeDao().upsert(first.copy(discovered = true)) // 名卡
            // ids[1] 保持 discovered=false → 神秘卡
        }
        val natives = cast().natives
        assertTrue(natives.any { it.nativeId == ids[0] && it.discovered })
        assertTrue(natives.any { it.nativeId == ids[1] && !it.discovered })
    }

    @Test
    fun `E4 已招募消失_清招募后回归_缘分归零`() {
        seedWorld()
        val id = yunyeNativeIds()[0]
        runBlocking {
            val s = db.worldNativeDao().get(id)!!
            db.worldNativeDao().upsert(s.copy(discovered = true, narrativeFuel = 30, recruitedCharacterUuid = "char_x"))
        }
        assertTrue("已招募原住民不上镇", cast().natives.none { it.nativeId == id })
        runBlocking { db.worldNativeDao().resetRecruitment("char_x") } // 删角 → 缘分归零
        assertTrue("清招募后回归", cast().natives.any { it.nativeId == id })
        assertEquals(0, runBlocking { db.worldNativeDao().get(id)!!.narrativeFuel }) // 燃料归零
    }

    // ---- E5 睡眠模式（民居几何认领的确定性归 chunk 8 UI 层测） ----

    @Test
    fun `E5 睡眠关键词落SLEEPING模式`() {
        seedWorld(); joinChar("c1", "小南", yunye); putSchedule("c1", "卧室", "午睡", phoneAvailable = false)
        assertEquals(StageMode.SLEEPING, cast().characters.single { it.uuid == "c1" }.mode)
    }

    @Test
    fun `E5 白天在家非睡眠落AT_HOME态_保活动行（R1返工）`() {
        // 「家里·做饭」非睡眠事件（无睡眠关键词·白天手机可用）→ AtHomeHouse → AT_HOME（不再顶「睡着了」）。
        seedWorld(); joinChar("c1", "小南", yunye); putSchedule("c1", "家里", "做饭")
        val c = cast().characters.single { it.uuid == "c1" }
        assertEquals(StageMode.AT_HOME, c.mode)
        assertNull(c.placeId)
        assertEquals("上午·做饭", c.statusLine) // 活动行保活·人物卡 body 优先显示
    }

    // ---- E12 宠物上限 ----

    @Test
    fun `E12 宠物大于3取前3_adoptedDate升序`() {
        seedWorld()
        putPet("五", 500L); putPet("一", 100L); putPet("三", 300L); putPet("四", 400L); putPet("二", 200L)
        val pets = cast(yunye).pets
        assertEquals(3, pets.size)
        assertEquals(listOf("一", "二", "三"), pets.map { it.name }) // 升序前 3
    }

    @Test
    fun `E12 宠物仅家城出现`() {
        seedWorld(homeCity = yunye)
        putPet("团子", 100L)
        assertTrue(cast(yunye).pets.isNotEmpty())
        assertTrue(cast(taoqiu).pets.isEmpty()) // 非家城无宠物
    }

    // ---- E6 W6 触发 gate（T2-2·真 WorldAffinityService） ----

    @Test
    fun `E6 不在场no-op_在场未发现discover返true_已发现返false`() {
        seedWorld(homeCity = yunye) // 用户在云野
        val id = yunyeNativeIds()[0]
        val taoqiuId = runBlocking { affinity.ensureSeeded(); db.worldNativeDao().getAll().first { it.currentCityId == taoqiu }.nativeId }

        // 不在场（原住民在陶丘·用户在云野）→ no-op 返 false。
        assertFalse(runBlocking { service.onMeetNative(taoqiuId, nowMs) })
        assertFalse("no-op 不发现", runBlocking { db.worldNativeDao().get(taoqiuId)!!.discovered })

        // 在场未发现 → discover + encounter 返 true。
        assertTrue(runBlocking { service.onMeetNative(id, nowMs) })
        runBlocking {
            db.worldNativeDao().get(id)!!.let {
                assertTrue(it.discovered)
                assertEquals(WorldAffinityService.ENCOUNTER_FUEL, it.narrativeFuel)
            }
        }
        // 已发现 → encounter 返 false（同本地日 → 燃料只记一次）。
        assertFalse(runBlocking { service.onMeetNative(id, nowMs + 1000L) })
        assertEquals(WorldAffinityService.ENCOUNTER_FUEL, runBlocking { db.worldNativeDao().get(id)!!.narrativeFuel })
    }

    @Test
    fun `E6 已发现原住民跨日再偶遇_燃料再加6（R1返工）`() {
        seedWorld(homeCity = yunye) // 用户在云野
        val id = yunyeNativeIds()[0]
        // 今日首遇 → discover + 记一次偶遇·fuel = 6。
        assertTrue(runBlocking { service.onMeetNative(id, nowMs) })
        assertEquals(WorldAffinityService.ENCOUNTER_FUEL, runBlocking { db.worldNativeDao().get(id)!!.narrativeFuel })
        // 次日再点卡（已发现）→ 返 false（非新发现）但记一次偶遇 → fuel = 12（招募燃料日常主通道）。
        val nextDay = nowMs + 24L * 3_600_000L
        assertFalse("已发现非新发现", runBlocking { service.onMeetNative(id, nextDay) })
        assertEquals(WorldAffinityService.ENCOUNTER_FUEL * 2, runBlocking { db.worldNativeDao().get(id)!!.narrativeFuel })
    }

    @Test
    fun `E6 用户在途gate挡_no-op`() {
        seedWorld(homeCity = yunye)
        val id = yunyeNativeIds()[0]
        putTravel(WorldIds.USER_ID, yunye, taoqiu, departAt = nowMs - 1000L, arriveAt = nowMs + 10_000L) // 用户在途
        assertFalse(runBlocking { service.onMeetNative(id, nowMs) })
        assertFalse(runBlocking { db.worldNativeDao().get(id)!!.discovered })
    }
}
