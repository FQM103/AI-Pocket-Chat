package com.situ.aichat.data.repository

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.cast.CreateResult
import com.situ.aichat.world.cast.ResidentDraft
import com.situ.aichat.world.cast.WorldAffinityService
import com.situ.aichat.world.cast.WorldNativeRoster
import com.situ.aichat.world.cast.WorldRecruitService
import com.situ.aichat.world.cast.WorldResidentService
import kotlinx.coroutines.runBlocking
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
 * [CharacterRepository.delete] 的 O2 连坐 T2-5（战役 B·图纸 §7·E9·Robolectric 真 Room + 真招募链）：
 * 删已招募的**用户自建居民**联系人 → def 行 + state 行连坐消失（彻底消失不回城）；**官方对照组**删后仍走
 * resetRecruitment（缘分归零、行不删=回城）。断言从图纸 §3.3/E9 独立反推。花名册单例在 [After] 复位防污染。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacterRepositoryResidentDeleteTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CharacterRepository
    private lateinit var residentService: WorldResidentService
    private lateinit var recruit: WorldRecruitService
    private val seed = 1L
    private val day0 = 1_000_000_000_000L

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        residentService = WorldResidentService(db.worldUserResidentDao(), db.worldNativeDao(), db.worldDao(), db)
        repo = CharacterRepository(db.characterDao(), db.milestoneDao(), db, residentService, io.mockk.mockk(relaxed = true))
        val affinity = WorldAffinityService(db.worldNativeDao(), db.worldDao())
        recruit = WorldRecruitService(
            db.worldNativeDao(), db.worldSocialDao(), db.worldDao(), repo, db, affinity, db.worldUserResidentDao(),
        )
        db.worldDao().upsertState(WorldStateEntity(seed = seed, userTimezoneId = "UTC", createdAt = 0L))
        affinity.ensureSeeded() // 20 官方
    }

    @After
    fun tearDown() {
        WorldNativeRoster.registerUserDefs(emptyList())
        db.close()
    }

    /** 令某 nativeId 过门槛（narrativeFuel 稳过 threshold）。 */
    private fun makeWilling(nativeId: String) = runBlocking {
        val s = db.worldNativeDao().get(nativeId)!!
        db.worldNativeDao().upsert(s.copy(discovered = true, discoveredAt = day0, narrativeFuel = 1000))
    }

    private fun createResident() = runBlocking {
        val r = residentService.create(
            ResidentDraft(
                name = "江晚棠", gender = "female", ageText = "26", cityId = "city_yunye", occupation = "旧书店店主",
                personaBrief = "安静", traits = listOf("温吞"), freeformLore = "", initialRelationText = "",
                fuelBias = "balanced", avatarPath = "/data/av/j.png",
            ),
            day0,
        )
        (r as CreateResult.Ok).slug
    }

    @Test
    fun `E9 删已招募用户居民_def 行 state 行连坐消失_花名册移除`() = runBlocking {
        val slug = createResident()
        val nativeId = WorldIds.nativeId(slug)
        // 官方对照组落户事件：证 O2 按 nativeId 连坐清事件不误伤他人（LIKE 精确不越界）。
        val controlEventUuid = "ctrl-arrive-su-wan"
        db.worldDao().upsertEvent(
            WorldEventEntity(
                uuid = controlEventUuid,
                kindRaw = WorldResidentService.ARRIVE_KIND,
                involvedIdsJson = StringListJson.encode(listOf(WorldIds.nativeId("su_wan"))),
                cityId = "city_yunye",
                summary = "苏婉 搬来了云野镇，住进了新家",
                happenedAt = day0,
            ),
        )
        makeWilling(nativeId)
        val uuid = recruit.recruit(nativeId, day0)!!
        assertNotNull(db.characterDao().getByUuid(uuid))
        assertNotNull(db.worldUserResidentDao().get(slug))
        assertEquals("落户事件删前在库", 1, db.worldDao().getAllEvents().count { it.involvedIdsJson.contains(nativeId) })

        repo.delete(uuid)

        assertNull("角色删", db.characterDao().getByUuid(uuid))
        assertNull("def 行连坐删（彻底消失）", db.worldUserResidentDao().get(slug))
        assertNull("state 行连坐删", db.worldNativeDao().get(nativeId))
        assertNull("花名册移除（地图/星图/计数即少一位）", WorldNativeRoster.bySlug(slug))
        // 🟡-1（复核 R1）：落户事件（involvedIdsJson=[nativeId]）连坐清 → 按 nativeId 查世界事件为 0（O2 彻底消失不残留）。
        assertEquals("落户事件按 nativeId 查为 0", 0, db.worldDao().getAllEvents().count { it.involvedIdsJson.contains(nativeId) })
        assertNotNull("官方对照组事件不受影响", db.worldDao().getEvent(controlEventUuid))
    }

    @Test
    fun `E9 官方对照组_删后仍 resetRecruitment_行不删缘分归零`() = runBlocking {
        val suId = WorldIds.nativeId("su_wan")
        makeWilling(suId)
        val uuid = recruit.recruit(suId, day0)!!

        repo.delete(uuid)

        val st = db.worldNativeDao().get(suId)
        assertNotNull("官方 state 行不删（回城常驻）", st)
        assertNull("招募指针清", st!!.recruitedCharacterUuid)
        assertEquals("叙事燃料归零", 0, st.narrativeFuel)
        assertEquals("心意燃料归零", 0, st.giftFuel)
    }
}
