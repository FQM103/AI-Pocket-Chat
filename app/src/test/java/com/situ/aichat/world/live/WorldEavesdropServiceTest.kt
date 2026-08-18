package com.situ.aichat.world.live

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.bulletin.WorldLlmBudget
import com.situ.aichat.world.cast.WorldNativeRoster
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * [WorldEavesdropService] T2-1（Robolectric 真 Room + 真 [WorldLlmBudget] + MockK 假 LLM·图纸 §7·E9/E10/E11）：
 * 首扣一次+记一条 / 冷却拒放零扣零事件 / 冷却过重生成 / 省档零 LLM / 异常退模板额度不退 / 解析失败退模板 / 选对确定性。
 * 断言从图纸 §3/§9 独立反推。UTC 令 epochDay 无歧义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldEavesdropServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var contextLog: ContextLogService
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var service: WorldEavesdropService

    private val zone = ZoneOffset.UTC
    private val seed = 42L
    private val epochDay = 100L
    private val cityId = WorldIds.HOME_CITY_ID
    private val placeId = "cafe"

    private val defSu = WorldNativeRoster.bySlug("su_wan")!!
    private val defLin = WorldNativeRoster.bySlug("lin_moyu")!!
    private val pool = listOf(
        EavesdropEntity(WorldIds.nativeId("su_wan"), defSu.name, characterUuid = null, nativeSlug = "su_wan"),
        EavesdropEntity(WorldIds.nativeId("lin_moyu"), defLin.name, characterUuid = null, nativeSlug = "lin_moyu"),
    )

    private fun noon(day: Long) = LocalDate.ofEpochDay(day).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        apiConfigRepo = mockk()
        contextLog = mockk()
        settingsRepo = mockk()
        service = WorldEavesdropService(
            db.worldDao(), db.worldSocialDao(), db.characterDao(), WorldLlmBudget(db), apiConfigRepo, contextLog, settingsRepo,
        )
        runBlocking { db.worldDao().upsertState(WorldStateEntity(seed = seed, userTimezoneId = "UTC", createdAt = 0L)) }
    }

    @After
    fun tearDown() = db.close()

    private fun tier(t: String) {
        every { settingsRepo.appSettings } returns flowOf(AppSettings(worldVividnessTier = t))
    }

    /** 让 LLM 返回一段对当前确定性对合法的对话（说话人名取真实所选对）。 */
    private fun stubValidLlm(): Pair<String, String> {
        val (pa, pb) = service.pickPair(pool, seed, placeId, epochDay)
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) } returns mockk<ApiConfigValues>(relaxed = true)
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            "${pa.name}：好久没见\n${pb.name}：可不是嘛\n${pa.name}：坐会儿吧\n【动静】两人叙了会儿旧"
        return pa.name to pb.name
    }

    private fun spend() = runBlocking { db.worldBulletinDao().spendCount(epochDay, WorldVividnessPools.EAVES) }
    private fun eavesEvents() = runBlocking { db.worldDao().getAllEvents().filter { it.kindRaw == WorldEavesdropService.EAVESDROP_KIND } }

    @Test
    fun `E_首次生成_扣一次_记一条_uuid金标`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        stubValidLlm()
        val out = service.eavesdrop(pool, cityId, placeId, "云野镇", "拾光咖啡馆", noon(epochDay))
        assertTrue("现场生成", out is EavesdropOutcome.Live)
        assertEquals("扣一次 eaves 额度", 1, spend())
        val events = eavesEvents()
        assertEquals("恰一条世界事件", 1, events.size)
        val (pa, pb) = service.pickPair(pool, seed, placeId, epochDay)
        val expectedUuid = UUID.nameUUIDFromBytes(
            "world:eaves:${WorldIds.pairKey(pa.id, pb.id)}:$epochDay".toByteArray(),
        ).toString()
        assertEquals("uuid = world:eaves:<pairKey>:<epochDay> 派生", expectedUuid, events[0].uuid)
        assertEquals("两人叙了会儿旧", events[0].summary)
        assertEquals(cityId, events[0].cityId)
    }

    /**
     * 非流式 completion 不剥内联 <think>——思考里的对话草稿行恰好是「名字：台词」格式：
     * 未剥净时「思路：」行触发说话人白名单整稿弃稿（退模板），剥净后正稿照常解析成 Live。
     */
    @Test
    fun `think标签剥净后照常解析成Live`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        val (pa, pb) = service.pickPair(pool, seed, placeId, epochDay)
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) } returns mockk<ApiConfigValues>(relaxed = true)
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            "<think>\n思路：先寒暄\n草稿 ${pa.name}：随便写写\n</think>\n${pa.name}：好久没见\n${pb.name}：可不是嘛\n【动静】两人叙了会儿旧"
        val out = service.eavesdrop(pool, cityId, placeId, "云野镇", "拾光咖啡馆", noon(epochDay))
        assertTrue("剥净后正稿解析成功", out is EavesdropOutcome.Live)
        assertEquals("摘要取正稿【动静】行", "两人叙了会儿旧", eavesEvents()[0].summary)
    }

    @Test
    fun `E10_冷却窗内二访_拒放_零扣零事件`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        stubValidLlm()
        service.eavesdrop(pool, cityId, placeId, "云野镇", "拾光咖啡馆", noon(epochDay))
        val out2 = service.eavesdrop(pool, cityId, placeId, "云野镇", "拾光咖啡馆", noon(epochDay) + 1000L)
        assertTrue("窗内 → 冷却 whisper", out2 is EavesdropOutcome.Cooldown)
        assertEquals("零扣（仍是首次的 1）", 1, spend())
        assertEquals("零新事件", 1, eavesEvents().size)
    }

    @Test
    fun `E10_冷却过后重生成_事件幂等不新增`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        stubValidLlm()
        service.eavesdrop(pool, cityId, placeId, "云野镇", "拾光咖啡馆", noon(epochDay))
        // +31min（仍同一本地日 → 同 epochDay·同 uuid）：冷却已过 → 重生成（再扣），事件 uuid 幂等 → 不新增。
        val out2 = service.eavesdrop(pool, cityId, placeId, "云野镇", "拾光咖啡馆", noon(epochDay) + 31 * 60_000L)
        assertTrue("冷却过 → 重生成", out2 is EavesdropOutcome.Live)
        assertEquals("再扣一次 → 2", 2, spend())
        assertEquals("同日同对事件不新增", 1, eavesEvents().size)
    }

    @Test
    fun `E9_省档_零LLM纯模板`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_LITE)
        val out = service.eavesdrop(pool, cityId, placeId, "云野镇", "拾光咖啡馆", noon(epochDay))
        assertTrue("省档 → 模板台词", out is EavesdropOutcome.Template)
        assertEquals("模板台词两句", 2, (out as EavesdropOutcome.Template).lines.size)
        assertNull("省档零扣（cap0 不写台账）", spend())
        assertEquals("省档不记世界事件", 0, eavesEvents().size)
        coVerify(exactly = 0) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `E11_LLM异常_退模板_额度不退`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) } returns mockk<ApiConfigValues>(relaxed = true)
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("断网")
        val out = service.eavesdrop(pool, cityId, placeId, "云野镇", "拾光咖啡馆", noon(epochDay))
        assertTrue("异常 → 模板", out is EavesdropOutcome.Template)
        assertEquals("先扣后调·额度已扣不退", 1, spend())
        assertEquals("失败不记事件", 0, eavesEvents().size)
    }

    @Test
    fun `E11_解析失败_退模板_额度不退`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) } returns mockk<ApiConfigValues>(relaxed = true)
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns "一段没有格式的乱七八糟输出"
        val out = service.eavesdrop(pool, cityId, placeId, "云野镇", "拾光咖啡馆", noon(epochDay))
        assertTrue("解析弃稿 → 模板", out is EavesdropOutcome.Template)
        assertEquals("额度已扣不退", 1, spend())
        assertEquals(0, eavesEvents().size)
    }

    @Test
    fun `选对种子确定性_同日同室恒同对_与池序无关`() {
        val three = pool + EavesdropEntity(WorldIds.nativeId("ming_qian"), WorldNativeRoster.bySlug("ming_qian")!!.name, null, "ming_qian")
        val first = service.pickPair(three, seed, placeId, epochDay)
        val again = service.pickPair(three, seed, placeId, epochDay)
        val shuffled = service.pickPair(three.reversed(), seed, placeId, epochDay)
        assertEquals("同输入恒同对", first.first.id to first.second.id, again.first.id to again.second.id)
        assertEquals("与池序无关（先排序）", first.first.id to first.second.id, shuffled.first.id to shuffled.second.id)
        assertTrue("对内按 id 升序", first.first.id <= first.second.id)
    }
}
