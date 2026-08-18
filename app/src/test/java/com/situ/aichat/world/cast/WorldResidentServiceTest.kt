package com.situ.aichat.world.cast

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldUserResidentEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * [WorldResidentService] T2-1 / T2-2（战役 B 图纸 §7·E1/E2/E3·Robolectric 真 Room in-memory）：
 * create 落三件（def 行 / 眼缘播种行 / 落户事件）+ slug 格式 + 上限 CapReached + 空名 InvalidName +
 * deleteUnrecruited 三清 + 已招募拒删。断言从图纸 §3.1/§3.3 独立反推。
 *
 * 花名册是进程级单例——[After] 复位 `registerUserDefs(emptyList())` 防跨测污染（本类是唯一注册用户 def 的测试）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldResidentServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var service: WorldResidentService
    private val seed = 1L
    private val day0 = 1_000_000_000_000L

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        service = WorldResidentService(db.worldUserResidentDao(), db.worldNativeDao(), db.worldDao(), db)
        db.worldDao().upsertState(WorldStateEntity(seed = seed, userTimezoneId = "UTC", createdAt = 0L))
        WorldNativeRoster.registerUserDefs(emptyList())
    }

    @After
    fun tearDown() {
        WorldNativeRoster.registerUserDefs(emptyList())
        db.close()
    }

    private fun draft(name: String = "江晚棠", cityId: String = "city_yunye") = ResidentDraft(
        name = name, gender = "female", ageText = "26", cityId = cityId, occupation = "旧书店店主",
        personaBrief = "安静", traits = listOf("温吞", "毒舌"), freeformLore = "旧怀表",
        initialRelationText = "", fuelBias = "balanced", avatarPath = null,
    )

    // MARK: - T2-1 create

    @Test
    fun `E_create Ok 落三件_slug 格式_花名册合流`() = runBlocking {
        val r = service.create(draft(), day0)
        assertTrue(r is CreateResult.Ok)
        val slug = (r as CreateResult.Ok).slug
        assertTrue("slug=$slug 应为 resident_ + 8 hex", slug.matches(Regex("^resident_[0-9a-f]{8}$")))
        val nativeId = WorldIds.nativeId(slug)

        // ① def 行。
        val e = db.worldUserResidentDao().get(slug)!!
        assertEquals("江晚棠", e.name); assertEquals("city_yunye", e.cityId); assertEquals(26, e.age)
        assertEquals(listOf("温吞", "毒舌"), StringListJson.decode(e.traitsJson))

        // ② 眼缘播种行（未发现·双燃料 0·当前城=出生城·未招募）。
        val st = db.worldNativeDao().get(nativeId)!!
        assertFalse(st.discovered); assertEquals(0, st.narrativeFuel); assertEquals(0, st.giftFuel)
        assertEquals("city_yunye", st.currentCityId); assertNull(st.recruitedCharacterUuid)

        // ③ 落户事件（kind/uuid 派生式/summary/involved 含 nativeId）。
        val ev = db.worldDao().getAllEvents().first { it.kindRaw == WorldResidentService.ARRIVE_KIND }
        assertEquals(
            UUID.nameUUIDFromBytes("world:resident:arrive:$slug".toByteArray()).toString(), ev.uuid,
        )
        val cityName = WorldNativeDef.cityNameOf("city_yunye", seed)
        assertEquals("江晚棠 搬来了${cityName}，住进了新家", ev.summary)
        assertEquals("city_yunye", ev.cityId)
        assertTrue(ev.involvedIdsJson.contains(nativeId))

        // 花名册合流：create 后 bySlug 可查（消费点零改动自动生效的地基）。
        assertNotNull(WorldNativeRoster.bySlug(slug))
        assertEquals("江晚棠", WorldNativeRoster.byNativeId(nativeId)!!.name)
    }

    @Test
    fun `E1 满 50 位 CapReached_不落库不落事件`() = runBlocking {
        repeat(WorldResidentService.MAX_RESIDENTS) { i ->
            db.worldUserResidentDao().upsert(
                WorldUserResidentEntity(
                    slug = "resident_seed%04d".format(i), name = "居民$i", gender = "female", age = 26,
                    cityId = "city_yunye", occupation = "", personaBrief = "", traitsJson = "",
                    freeformLore = "", initialRelationText = "", fuelBias = "balanced", avatarPath = null, createdAt = 0L,
                ),
            )
        }
        val before = db.worldDao().getAllEvents().size
        val r = service.create(draft("新人"), day0)
        assertTrue(r is CreateResult.CapReached)
        assertEquals("不落第 51 行", WorldResidentService.MAX_RESIDENTS, db.worldUserResidentDao().count())
        assertEquals("不落事件", before, db.worldDao().getAllEvents().size)
    }

    @Test
    fun `E2 空白名 InvalidName_零落库`() = runBlocking {
        assertTrue(service.create(draft(name = "   "), day0) is CreateResult.InvalidName)
        assertEquals(0, db.worldUserResidentDao().count())
        assertTrue(db.worldDao().getAllEvents().isEmpty())
    }

    // MARK: - T2-2 deleteUnrecruited

    @Test
    fun `E_deleteUnrecruited 三清_def 行_state 行_落户事件`() = runBlocking {
        val slug = (service.create(draft(), day0) as CreateResult.Ok).slug
        val nativeId = WorldIds.nativeId(slug)
        assertTrue(service.deleteUnrecruited(slug))
        assertNull("def 行清", db.worldUserResidentDao().get(slug))
        assertNull("state 行清", db.worldNativeDao().get(nativeId))
        assertTrue("落户事件连坐清", db.worldDao().getAllEvents().none { it.involvedIdsJson.contains(nativeId) })
        assertNull("花名册移除", WorldNativeRoster.bySlug(slug))
    }

    @Test
    fun `E3 已招募居民 deleteUnrecruited 返 false_零删除`() = runBlocking {
        val slug = (service.create(draft(), day0) as CreateResult.Ok).slug
        val nativeId = WorldIds.nativeId(slug)
        // 模拟已招募：state 行写招募指针。
        val st = db.worldNativeDao().get(nativeId)!!
        db.worldNativeDao().upsert(st.copy(recruitedCharacterUuid = "some-char-uuid"))
        assertFalse("已招募 → 拒删", service.deleteUnrecruited(slug))
        assertNotNull("def 行仍在", db.worldUserResidentDao().get(slug))
        assertNotNull("state 行仍在", db.worldNativeDao().get(nativeId))
    }

    @Test
    fun `E_loadIntoRoster 装载已存居民入花名册`() = runBlocking {
        db.worldUserResidentDao().upsert(
            WorldUserResidentEntity(
                slug = "resident_cafe0001", name = "旧友", gender = "male", age = 40, cityId = "city_yunye",
                occupation = "木匠", personaBrief = "话少", traitsJson = StringListJson.encode(listOf("稳重")),
                freeformLore = "", initialRelationText = "", fuelBias = "narrative", avatarPath = null, createdAt = 0L,
            ),
        )
        WorldNativeRoster.registerUserDefs(emptyList()) // 先清·证明 loadIntoRoster 真装载
        assertNull(WorldNativeRoster.bySlug("resident_cafe0001"))
        service.loadIntoRoster()
        assertEquals("旧友", WorldNativeRoster.bySlug("resident_cafe0001")!!.name)
        assertEquals(1.2, WorldNativeRoster.bySlug("resident_cafe0001")!!.narrativeWeight, 0.0)
    }
}
