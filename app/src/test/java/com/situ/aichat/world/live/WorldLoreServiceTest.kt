package com.situ.aichat.world.live

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.WorldCityLoreEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.atlas.WorldRegions
import com.situ.aichat.world.bulletin.WorldLlmBudget
import com.situ.aichat.world.travel.WorldPresence
import com.situ.aichat.world.travel.WorldTravelService
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
 * [WorldLoreService] T2-2（Robolectric 真 Room + 真 [WorldLlmBudget] + MockK 假 LLM/presence·图纸 §7·E13/E14）：
 * 条件矩阵六项零 LLM + 成功写 `{"text":…}` + IGNORE 不覆盖 + E14 弃稿静默。断言从图纸 §3/§9 独立反推。UTC。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldLoreServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var travel: WorldTravelService
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var contextLog: ContextLogService
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var service: WorldLoreService

    private val seed = 42L
    private val epochDay = 100L
    private val nowMs = LocalDate.ofEpochDay(epochDay).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val atlas = WorldAtlas.of(seed)
    private val genCity = WorldRegions.ALL.firstNotNullOf { r -> atlas.citiesIn(r.id).firstOrNull { !it.curated } }
    private val curatedCityId = "city_yunye"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        travel = mockk()
        apiConfigRepo = mockk()
        contextLog = mockk()
        settingsRepo = mockk()
        service = WorldLoreService(db.worldDao(), travel, WorldLlmBudget(db), apiConfigRepo, contextLog, settingsRepo)
        runBlocking { db.worldDao().upsertState(WorldStateEntity(seed = seed, userTimezoneId = "UTC", createdAt = 0L)) }
    }

    @After
    fun tearDown() = db.close()

    private fun tier(t: String) { every { settingsRepo.appSettings } returns flowOf(AppSettings(worldVividnessTier = t)) }
    private fun present(cityId: String) { coEvery { travel.userPresence(any()) } returns WorldPresence(cityId, null, null) }
    private fun stubLlm(text: String) {
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) } returns mockk<ApiConfigValues>(relaxed = true)
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns text
    }
    private fun spend() = runBlocking { db.worldBulletinDao().spendCount(epochDay, WorldVividnessPools.LORE) }
    private fun noLlm() = coVerify(exactly = 0) { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }

    @Test
    fun `成功点亮_写canon文本_扣一次`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD); present(genCity.id)
        val body = "城".repeat(150)
        stubLlm(body)
        assertTrue(service.tryLightUp(genCity.id, nowMs))
        val row = db.worldDao().getLore(genCity.id)!!
        assertEquals(body, WorldLoreService.loreTextOf(row.loreJson))
        assertEquals("键名 text 锁死", "{\"text\":\"$body\"}", row.loreJson)
        assertEquals(nowMs, row.generatedAt)
        assertEquals(1, spend())
    }

    /** 非流式 completion 不剥内联 <think>——永久 canon 落库前须剥净，且 E14 长度按剥净后正文判（超长思考块不误伤好稿）。 */
    @Test
    fun `think标签剥净后落canon_长度按剥净后判`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD); present(genCity.id)
        val body = "城".repeat(150)
        stubLlm("<think>${"构思".repeat(300)}</think>$body") // 带标签全文超 400，剥净后正文合规
        assertTrue(service.tryLightUp(genCity.id, nowMs))
        assertEquals(body, WorldLoreService.loreTextOf(db.worldDao().getLore(genCity.id)!!.loreJson))
    }

    @Test
    fun `E13_精修城_零LLM`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD); present(curatedCityId)
        assertFalse(service.tryLightUp(curatedCityId, nowMs))
        assertNull(db.worldDao().getLore(curatedCityId)); noLlm()
    }

    @Test
    fun `E13_用户不在场_零LLM`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD); present("city_taoqiu") // 在别处
        assertFalse(service.tryLightUp(genCity.id, nowMs))
        assertNull(db.worldDao().getLore(genCity.id)); noLlm()
    }

    @Test
    fun `E13_在途_零LLM`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD)
        coEvery { travel.userPresence(any()) } returns WorldPresence(genCity.id, "city_taoqiu", nowMs + 1000) // 在途
        assertFalse(service.tryLightUp(genCity.id, nowMs))
        assertNull(db.worldDao().getLore(genCity.id)); noLlm()
    }

    @Test
    fun `E13_已有lore_零LLM_不覆盖`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD); present(genCity.id)
        db.worldDao().insertLore(WorldCityLoreEntity(cityId = genCity.id, loreJson = WorldLoreService.encodeLore("旧稿"), generatedAt = 1L))
        assertFalse(service.tryLightUp(genCity.id, nowMs))
        assertEquals("IGNORE 不覆盖", "旧稿", WorldLoreService.loreTextOf(db.worldDao().getLore(genCity.id)!!.loreJson))
        noLlm()
    }

    @Test
    fun `E13_预算尽_零LLM`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD); present(genCity.id)
        val budget = WorldLlmBudget(db)
        repeat(6) { budget.tryConsume(WorldVividnessPools.LORE, epochDay, 6) } // 标准档 cap=6 耗尽
        assertFalse(service.tryLightUp(genCity.id, nowMs))
        assertNull(db.worldDao().getLore(genCity.id)); noLlm()
    }

    @Test
    fun `E13_省档_零LLM`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_LITE); present(genCity.id)
        assertFalse(service.tryLightUp(genCity.id, nowMs))
        assertNull(db.worldDao().getLore(genCity.id)); assertNull(spend()); noLlm()
    }

    @Test
    fun `E14_产文过短_弃稿静默_额度不退`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD); present(genCity.id)
        stubLlm("太短了") // <40 字
        assertFalse(service.tryLightUp(genCity.id, nowMs))
        assertNull("弃稿不写库", db.worldDao().getLore(genCity.id))
        assertEquals("先扣后调·额度已扣不退", 1, spend())
    }

    @Test
    fun `E14_产文超长_弃稿静默`() = runBlocking {
        tier(AppSettings.WORLD_VIVIDNESS_STANDARD); present(genCity.id)
        stubLlm("字".repeat(401)) // >400 字
        assertFalse(service.tryLightUp(genCity.id, nowMs))
        assertNull(db.worldDao().getLore(genCity.id))
    }
}
