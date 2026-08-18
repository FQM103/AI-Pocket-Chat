package com.situ.aichat.world.bulletin

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.world.SettlementDay
import com.situ.aichat.world.SettlementWindow
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.atlas.WorldAtlas
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
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
import java.time.ZoneOffset

/**
 * [WorldBulletinService] T2-7（Robolectric 真 Room + 真 [WorldLlmBudget] + MockK 假 LLM·图纸 §7·E13/E14/E20）：
 * 模板金标 / 预算三档 / 失败退模板 / 超长弃稿 / 断网。断言从图纸 §4.4/§4.5 + §3.3 独立反推。UTC 令 epochDay 无歧义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBulletinServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var contextLog: ContextLogService
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var service: WorldBulletinService
    private val zone = ZoneOffset.UTC
    private val seed = 42L
    private val cityName by lazy { WorldAtlas.of(seed).cityById(WorldIds.HOME_CITY_ID)?.name ?: "小城" }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        apiConfigRepo = mockk()
        contextLog = mockk()
        settingsRepo = mockk()
        service = WorldBulletinService(db.worldDao(), db.worldBulletinDao(), WorldLlmBudget(db), apiConfigRepo, contextLog, settingsRepo)
    }

    @After
    fun tearDown() = db.close()

    private fun tier(t: String) {
        every { settingsRepo.appSettings } returns flowOf(AppSettings(worldVividnessTier = t))
    }

    private fun stubLlm(returns: String) {
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) } returns mockk<ApiConfigValues>(relaxed = true)
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns returns
    }

    private fun dayStart(epochDay: Long) = LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun noon(epochDay: Long) = LocalDate.ofEpochDay(epochDay).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun state() = WorldStateEntity(seed = seed, userTimezoneId = "UTC", userHomeCityId = WorldIds.HOME_CITY_ID, createdAt = 0L)

    private fun window(days: List<Long>, absenceMs: Long) =
        SettlementWindow(days = days.map { SettlementDay(LocalDate.ofEpochDay(it), it, 0L) }, truncatedDays = 0, absenceMs = absenceMs, firstRun = false)

    private fun seedEvent(uuid: String, summary: String, happenedAt: Long) = runBlocking {
        db.worldDao().upsertEvent(WorldEventEntity(uuid = uuid, kindRaw = "relationship", involvedIdsJson = "[]", cityId = null, summary = summary, happenedAt = happenedAt))
    }

    // MARK: - E13 模板金标

    @Test
    fun `E13 有事模板金标_省档零LLM`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_LITE)
        // 缺席 3 天·4 事件（happenedAt 递增 → 降序展示 e4,e3,e2,e1）。
        seedEvent("e1", "事件一", noon(100) - 3)
        seedEvent("e2", "事件二", noon(100) - 2)
        seedEvent("e3", "事件三", noon(100) - 1)
        seedEvent("e4", "事件四", noon(100))

        service.refresh(state(), window(listOf(98L, 99L, 100L), absenceMs = 3L * 86_400_000L), zone, noon(100) + 1000L)

        val expected = "你不在的这段时间，${cityName}发生了这些事：\n· 事件四\n· 事件三\n· 事件二\n· 事件一"
        assertEquals(expected, db.worldBulletinDao().getByDay(100L)!!.templateText)
        coVerify(exactly = 0) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } // 省档零 LLM
    }

    @Test
    fun `E13 超5条追加小结行`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_LITE)
        for (i in 1..6) seedEvent("e$i", "事$i", noon(100) - (6 - i)) // e6 最新
        service.refresh(state(), window(listOf(100L), absenceMs = 86_400_000L), zone, noon(100) + 1000L)
        val text = db.worldBulletinDao().getByDay(100L)!!.templateText
        assertTrue("恰 5 条展示", text.lines().count { it.startsWith("· ") } == 5)
        assertTrue("超出追加小结", text.contains("……还有 1 件小事"))
    }

    @Test
    fun `静好模板_零事件缺席满24h`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_LITE)
        service.refresh(state(), window(listOf(100L), absenceMs = 86_400_000L), zone, noon(100) + 1000L)
        assertEquals("这几天${cityName}安安静静的，大家各自过着日子。", db.worldBulletinDao().getByDay(100L)!!.templateText)
    }

    @Test
    fun `短暂离开不出报_零事件缺席不足24h`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        service.refresh(state(), window(listOf(100L), absenceMs = 3_600_000L), zone, noon(100) + 1000L) // 1h
        assertNull("短暂离开不写小报行", db.worldBulletinDao().getByDay(100L))
    }

    // MARK: - E14 预算 / 失败 / 超长

    @Test
    fun `E14 标准档润色_hash未变不重润`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        stubLlm("云野镇这几天热热闹闹，大家都过得挺好。")
        seedEvent("e1", "事件一", noon(100))

        service.refresh(state(), window(listOf(100L), absenceMs = 86_400_000L), zone, noon(100) + 1000L)
        assertEquals("云野镇这几天热热闹闹，大家都过得挺好。", db.worldBulletinDao().getByDay(100L)!!.polishedText)
        // 同事件重结算：hash 未变 + 已润色 → 早返，LLM 不再调。
        service.refresh(state(), window(listOf(100L), absenceMs = 86_400_000L), zone, noon(100) + 2000L)
        coVerify(exactly = 1) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    /** 非流式 completion 不剥内联 <think>——润色稿落库前须剥净，否则思考文本随小报持久化并上世界卡。 */
    @Test
    fun `润色剥净think标签后落库`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        stubLlm("<think>该写得热闹些。</think>云野镇这几天热热闹闹，大家都过得挺好。")
        seedEvent("e1", "事件一", noon(100))
        service.refresh(state(), window(listOf(100L), absenceMs = 86_400_000L), zone, noon(100) + 1000L)
        assertEquals("云野镇这几天热热闹闹，大家都过得挺好。", db.worldBulletinDao().getByDay(100L)!!.polishedText)
    }

    @Test
    fun `E14 超预算退模板_预算耗尽后不调LLM`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        stubLlm("润色稿")
        // 预先把 day100 的 bulletin 额度耗尽（cap=3·标准档）。
        val budget = WorldLlmBudget(db)
        repeat(3) { budget.tryConsume("bulletin", 100L, 3) }
        seedEvent("e1", "事件一", noon(100))

        service.refresh(state(), window(listOf(100L), absenceMs = 86_400_000L), zone, noon(100) + 1000L)
        assertNull("超预算 → 保模板（polishedText null）", db.worldBulletinDao().getByDay(100L)!!.polishedText)
        coVerify(exactly = 0) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `E20 LLM异常_保模板`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) } returns mockk<ApiConfigValues>(relaxed = true)
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("断网")
        seedEvent("e1", "事件一", noon(100))
        service.refresh(state(), window(listOf(100L), absenceMs = 86_400_000L), zone, noon(100) + 1000L)
        val row = db.worldBulletinDao().getByDay(100L)!!
        assertNull("LLM 异常 → 保模板", row.polishedText)
        assertTrue("模板恒有值", row.templateText.contains("事件一"))
    }

    @Test
    fun `E14 超400字弃稿_保模板`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        stubLlm("字".repeat(401)) // 超弃稿线 400
        seedEvent("e1", "事件一", noon(100))
        service.refresh(state(), window(listOf(100L), absenceMs = 86_400_000L), zone, noon(100) + 1000L)
        assertNull("超 400 字 → 弃、保模板", db.worldBulletinDao().getByDay(100L)!!.polishedText)
    }

    @Test
    fun `无有效配置_跳过润色保模板`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) } returns null
        seedEvent("e1", "事件一", noon(100))
        service.refresh(state(), window(listOf(100L), absenceMs = 86_400_000L), zone, noon(100) + 1000L)
        assertNull(db.worldBulletinDao().getByDay(100L)!!.polishedText)
    }

    // MARK: - E17/E18 W8 §3.6 小报两处修订（窗尾截断 + markSeen·作者核准）

    private fun windowEndOf(day: Long) = LocalDate.ofEpochDay(day + 1).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `E17 窗尾截断_窗末前1ms进本期_窗末整点进下期恰一次`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_LITE)
        val day = 100L
        val endMs = windowEndOf(day) // = day101 起点
        seedEvent("eIn", "窗内事件", endMs - 1)  // 窗末前 1ms → 进 day100 期
        seedEvent("eEdge", "窗末事件", endMs)     // 窗末整点 → 不进 day100 期

        service.refresh(state(), window(listOf(day), absenceMs = 86_400_000L), zone, endMs + 1000L)
        val d100 = db.worldBulletinDao().getByDay(day)!!.templateText
        assertTrue("窗末前1ms进本期", d100.contains("窗内事件"))
        assertFalse("窗末整点不进本期", d100.contains("窗末事件"))

        // 下一期（day101·窗 [day101起点, day102起点)）：窗末事件恰进，窗内事件不再出现。
        service.refresh(state(), window(listOf(day + 1), absenceMs = 86_400_000L), zone, endMs + 86_400_000L + 1000L)
        val d101 = db.worldBulletinDao().getByDay(day + 1)!!.templateText
        assertTrue("窗末事件进下期恰一次", d101.contains("窗末事件"))
        assertFalse("下期不含窗内事件", d101.contains("窗内事件"))
    }

    @Test
    fun `E18 markSeen_窗内置windowEndMs_重刷不改首值_窗尾保持null`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_LITE)
        val day = 100L
        val endMs = windowEndOf(day)
        seedEvent("eIn", "窗内事件", endMs - 1)
        seedEvent("eEdge", "窗末事件", endMs)

        service.refresh(state(), window(listOf(day), absenceMs = 86_400_000L), zone, endMs + 1000L)
        assertEquals("窗内事件 seenAt=windowEndMs", endMs, db.worldDao().getEvent("eIn")!!.seenAt)
        assertNull("窗末事件 seenAt 仍 null（未进本期）", db.worldDao().getEvent("eEdge")!!.seenAt)

        // 重刷（更宽窗 [100,101]·windowEndMs 变 day102 起点）：NULL 守卫 → eIn.seenAt 保持首次值 endMs。
        service.refresh(state(), window(listOf(day, day + 1), absenceMs = 86_400_000L), zone, windowEndOf(day + 1) + 1000L)
        assertEquals("重刷不改首次值", endMs, db.worldDao().getEvent("eIn")!!.seenAt)
    }
}
