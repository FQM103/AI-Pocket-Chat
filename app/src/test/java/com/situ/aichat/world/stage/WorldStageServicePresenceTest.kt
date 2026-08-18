package com.situ.aichat.world.stage

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import com.situ.aichat.world.cast.WorldAffinityService
import com.situ.aichat.world.travel.WorldTravelService
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * [WorldStageService.presenceLineFor] T2-7（W13 图纸 §7）：joined+日程命中地点→AT_PLACE+placeName·未加入→null·
 * **世界未建→null 且 world_state 仍无行（绝不建世）**·无日程→IN_TOWN·在途→TRAVELING。真 Room·全程 UTC。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldStageServicePresenceTest {

    private lateinit var db: AppDatabase
    private lateinit var service: WorldStageService

    private val nowMs = LocalDateTime.of(2026, 6, 15, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val dayStart = LocalDate.of(2026, 6, 15).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val yunye = "city_yunye"
    private val taoqiu = "city_taoqiu"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val travel = WorldTravelService(db.worldDao(), db.characterDao(), mockk(relaxed = true), db, mockk(relaxed = true), mockk(relaxed = true))
        val affinity = WorldAffinityService(db.worldNativeDao(), db.worldDao())
        service = WorldStageService(
            db.characterDao(), db.scheduleDao(), db.worldNativeDao(), db.petDao(),
            db.worldDao(), travel, affinity,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun seedWorld() = runBlocking {
        db.worldDao().upsertState(WorldStateEntity(seed = 42L, userTimezoneId = "UTC", userHomeCityId = yunye, userCurrentCityId = yunye, createdAt = 0L))
    }

    private fun joinChar(uuid: String, home: String = yunye, joined: Boolean = true) = runBlocking {
        db.characterDao().upsert(CharacterEntity(uuid = uuid, name = "苏晚", creationDate = 0L, joinedWorld = joined, worldHomeCityId = home))
    }

    private fun putSchedule(uuid: String, location: String, activity: String, phoneAvailable: Boolean = true) = runBlocking {
        val schedUuid = UUID.randomUUID().toString()
        db.scheduleDao().insertScheduleWithEvents(
            CharacterDailyScheduleEntity(uuid = schedUuid, characterUuid = uuid, date = dayStart, generatedAt = 1L),
            listOf(
                ScheduleEventEntity(
                    uuid = UUID.randomUUID().toString(), scheduleUuid = schedUuid,
                    startTime = nowMs - 3_600_000L, endTime = nowMs + 3_600_000L,
                    periodLabel = "上午", location = location, activity = activity, isPhoneAvailable = phoneAvailable,
                ),
            ),
        )
    }

    private fun line(uuid: String) = runBlocking { service.presenceLineFor(uuid, nowMs) }

    @Test
    fun `joined日程命中咖啡馆_AT_PLACE带placeName与placeType`() {
        seedWorld(); joinChar("c1"); putSchedule("c1", "在拾光咖啡馆画画", "画画")
        val l = line("c1")!!
        assertEquals(WorldPresenceLine.Kind.AT_PLACE, l.kind)
        assertEquals("拾光咖啡馆", l.placeName)
        assertEquals(WorldStageResolver.PlaceType.CAFE, l.placeType)
        assertEquals("yunye_cafe", l.placeId)
        assertEquals(yunye, l.cityId)
        assertEquals("云野镇", l.cityName)
    }

    @Test
    fun `未加入世界_null`() {
        seedWorld(); joinChar("c1", joined = false)
        assertNull(line("c1"))
    }

    @Test
    fun `查无角色_null`() {
        seedWorld()
        assertNull(line("nope"))
    }

    @Test
    fun `世界未建_null且world_state仍无行_绝不建世`() {
        joinChar("c1") // 已加入但世界从未初始化
        assertNull("世界未建返 null", line("c1"))
        assertNull("presenceLineFor 绝不 ensureCreated·world_state 仍空", runBlocking { db.worldDao().getState() })
    }

    @Test
    fun `无日程_IN_TOWN`() {
        seedWorld(); joinChar("c1")
        val l = line("c1")!!
        assertEquals(WorldPresenceLine.Kind.IN_TOWN, l.kind)
        assertNull(l.placeName)
        assertEquals(yunye, l.cityId)
    }

    @Test
    fun `在途_TRAVELING带目的城`() {
        seedWorld(); joinChar("c1")
        db.worldDao().let { runBlocking { it.upsertTravel(WorldTravelEntity(ownerId = "c1", fromCityId = yunye, toCityId = taoqiu, departAt = nowMs - 1000L, arriveAt = nowMs + 10_000L, modeRaw = "car")) } }
        val l = line("c1")!!
        assertEquals(WorldPresenceLine.Kind.TRAVELING, l.kind)
        assertEquals(taoqiu, l.destCityId)
        assertEquals("陶丘", l.destCityName)
    }
}
