package com.situ.aichat.world.cast

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.world.WorldIds
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [WorldFirstMeetService] T2-5（Robolectric 真 Room + 真 recruit/会话/消息 + MockK 假 LLM·图纸 §7·E5/E6/E7/E8·上限）：
 * 确认单事务全落/全无（注错回滚实证）· 早退零副作用 · 确认愿意复核（双击）· 开场失败退模板+确认可用 · 回应硬上限 2。UTC。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldFirstMeetServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var contextLog: ContextLogService
    private lateinit var apiConfigRepo: ApiConfigRepository
    private lateinit var recruit: WorldRecruitService
    private lateinit var service: WorldFirstMeetService
    private val seed = 1L
    private val day0 = 1_000_000_000_000L
    private val suWanId = WorldIds.nativeId("su_wan")

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        contextLog = mockk()
        apiConfigRepo = mockk()
        val affinity = WorldAffinityService(db.worldNativeDao(), db.worldDao())
        recruit = WorldRecruitService(
            db.worldNativeDao(), db.worldSocialDao(), db.worldDao(),
            CharacterRepository(
                db.characterDao(), db.milestoneDao(), db,
                WorldResidentService(db.worldUserResidentDao(), db.worldNativeDao(), db.worldDao(), db),
                io.mockk.mockk(relaxed = true),
            ),
            db, affinity, db.worldUserResidentDao(),
        )
        service = WorldFirstMeetService(contextLog, apiConfigRepo, db.worldDao(), recruit, ConversationRepository(db.conversationDao()), MessageRepository(db.messageDao()), db)
        db.worldDao().upsertState(WorldStateEntity(seed = seed, userTimezoneId = "UTC", createdAt = 0L))
        affinity.ensureSeeded()
        db.worldNativeDao().upsert(WorldNativeStateEntity(nativeId = suWanId, discovered = true, discoveredAt = day0, narrativeFuel = 1000, currentCityId = WorldNativeRoster.bySlug("su_wan")!!.cityId))
    }

    @After
    fun tearDown() = db.close()

    private fun noLlm() { coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) } returns null }
    private fun stubLlm(text: String) {
        coEvery { apiConfigRepo.resolveConfigValues(ApiFunction.WORLD) } returns mockk<ApiConfigValues>(relaxed = true)
        coEvery { contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns text
    }

    @Test
    fun `E5 开场失败退兜底_确认照常可用`() = runBlocking {
        noLlm()
        val opening = service.startMeet(suWanId, "苏晚", "拾光咖啡馆", day0)
        assertEquals(WorldFirstMeetService.fallbackOpener("苏晚"), opening)
        val result = service.confirmMeet(suWanId, "苏晚", day0)!!
        assertNotNull("招募不被 LLM 阻塞", db.characterDao().getByUuid(result.characterUuid))
        assertTrue("开场落进会话", MessageRepository(db.messageDao()).recentChronological(result.conversationUuid, 50).any { it.content == opening && it.roleRaw == "assistant" })
    }

    @Test
    fun `E6 早退零副作用`() = runBlocking {
        noLlm()
        service.startMeet(suWanId, "苏晚", "拾光", day0)
        service.abandon(suWanId)
        assertEquals("零角色", 0, db.characterDao().count())
        assertNull("未招募", db.worldNativeDao().get(suWanId)!!.recruitedCharacterUuid)
    }

    @Test
    fun `E7 确认双击_第二次null_仍一角色`() = runBlocking {
        noLlm()
        service.startMeet(suWanId, "苏晚", "拾光", day0)
        assertNotNull(service.confirmMeet(suWanId, "苏晚", day0))
        assertNull("双击第二次幂等返 null", service.confirmMeet(suWanId, "苏晚", day0))
        assertEquals(1, db.characterDao().count())
    }

    @Test
    fun `E8 确认事务flush注错_全回滚无角色`() = runBlocking {
        noLlm()
        val spyMsgDao = spyk(db.messageDao())
        coEvery { spyMsgDao.upsert(any()) } throws RuntimeException("boom")
        val svc = WorldFirstMeetService(contextLog, apiConfigRepo, db.worldDao(), recruit, ConversationRepository(db.conversationDao()), MessageRepository(spyMsgDao), db)
        svc.startMeet(suWanId, "苏晚", "拾光", day0)
        var threw = false
        try { svc.confirmMeet(suWanId, "苏晚", day0) } catch (e: Exception) { threw = true }
        assertTrue("flush 注错应抛", threw)
        assertEquals("角色全回滚", 0, db.characterDao().count())
        assertNull("指针回滚", db.worldNativeDao().get(suWanId)!!.recruitedCharacterUuid)
    }

    @Test
    fun `回应硬上限2`() = runBlocking {
        stubLlm("好的呀")
        service.startMeet(suWanId, "苏晚", "拾光", day0)
        assertNotNull("回应1", service.respond(suWanId, "你好"))
        assertNotNull("回应2", service.respond(suWanId, "再聊两句"))
        assertNull("达上限2 → 只出确认卡", service.respond(suWanId, "还想聊"))
    }

    /** 非流式 completion 不剥内联 <think>——开场/回应落消息表前必须剥净，否则思考文本固化进 MessageEntity。 */
    @Test
    fun `开场与回应剥净think标签`() = runBlocking {
        stubLlm("<think>要热情一点。</think>晚风正好，坐会儿再走？")
        assertEquals("晚风正好，坐会儿再走？", service.startMeet(suWanId, "苏晚", "拾光", day0))
        assertEquals("晚风正好，坐会儿再走？", service.respond(suWanId, "你好"))
    }
}
