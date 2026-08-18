package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「立即整理」T2（记忆护栏第二层 MG-U3·MockK·微图纸 §5）。断言从契约 §5 独立反推：
 * ① 成功路：消化班车恰一次 + 遇阻会话与锚点会话全记 success；② 失败路：遇阻会话记 failure、返 false；
 * ③ 空采集路：不烧 LLM（班车 0 次）、遇阻会话记 success 清旗标；④ busy 守卫：进行中重入直接返 false。
 * 手动路径**不做**冷却/下限判定——本测试不 stub 任何触发判定依赖即为证明（缺依赖会 no answer found 炸）。
 */
class ManualMemoryOrganizeServiceTest {

    private val characterRepo = mockk<CharacterRepository>()
    private val conversationRepo = mockk<ConversationRepository>(relaxed = true)
    private val memoryService = mockk<MemoryService>()
    private val digestCoordinator = mockk<MemoryDigestCoordinator>()
    private val apiConfigRepo = mockk<ApiConfigRepository>()
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val userProfileDao = mockk<UserProfileDao>()

    private val service = ManualMemoryOrganizeService(
        characterRepo, conversationRepo, memoryService, digestCoordinator,
        apiConfigRepo, settingsRepo, userProfileDao,
    )

    private val character = CharacterEntity(uuid = "c1", name = "角色", creationDate = 0L)
    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "m",
    )

    private fun conv(uuid: String) = ConversationEntity(
        uuid = uuid, title = "t", characterUuid = "c1", creationDate = 0L,
        lastMemorySummaryFailureDate = 1_000L,
    )

    private val messages = listOf(
        MessageEntity(messageUUID = "m1", conversationUuid = "convC", roleRaw = "user", content = "你好", timestamp = 1L),
    )

    private fun stubHappyDeps(collected: List<MessageEntity> = messages) {
        coEvery { characterRepo.get("c1") } returns character
        coEvery { apiConfigRepo.resolveConfigValues(any()) } returns config
        coEvery { userProfileDao.get() } returns null
        coEvery { conversationRepo.memorySummaryBlockedByCharacter("c1") } returns listOf(conv("convA"), conv("convB"))
        coEvery { conversationRepo.latestActiveForCharacter("c1") } returns conv("convC")
        coEvery { memoryService.collectMessagesOutsideWindow("c1", "convC", any()) } returns collected
    }

    @Test
    fun `成功路_班车恰一次且遇阻与锚点会话全记success`() = runBlocking {
        stubHappyDeps()
        coEvery { digestCoordinator.digestAndReconcile(any(), any(), any(), any(), any(), any()) } returns "新记忆"

        assertTrue(service.organizeNow("c1"))

        coVerify(exactly = 1) { digestCoordinator.digestAndReconcile(character, "convC", messages, config, any(), "") }
        coVerify(exactly = 1) { conversationRepo.recordMemorySummaryResult("convA", true, any()) }
        coVerify(exactly = 1) { conversationRepo.recordMemorySummaryResult("convB", true, any()) }
        coVerify(exactly = 1) { conversationRepo.recordMemorySummaryResult("convC", true, any()) }
        assertTrue("收尾必撤忙态", service.organizing.value.isEmpty())
    }

    @Test
    fun `失败路_遇阻会话记failure返false`() = runBlocking {
        stubHappyDeps()
        coEvery { digestCoordinator.digestAndReconcile(any(), any(), any(), any(), any(), any()) } throws MemorySummaryError.TooLong

        assertFalse(service.organizeNow("c1"))

        coVerify(exactly = 1) { conversationRepo.recordMemorySummaryResult("convA", false, any()) }
        coVerify(exactly = 1) { conversationRepo.recordMemorySummaryResult("convB", false, any()) }
        coVerify(exactly = 0) { conversationRepo.recordMemorySummaryResult(any(), true, any()) }
        assertTrue(service.organizing.value.isEmpty())
    }

    @Test
    fun `空采集路_不烧LLM且遇阻会话记success清旗标`() = runBlocking {
        stubHappyDeps(collected = emptyList())

        assertTrue(service.organizeNow("c1"))

        coVerify(exactly = 0) { digestCoordinator.digestAndReconcile(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { conversationRepo.recordMemorySummaryResult("convA", true, any()) }
        coVerify(exactly = 1) { conversationRepo.recordMemorySummaryResult("convB", true, any()) }
    }

    @Test
    fun `busy守卫_进行中重入直接返false`() = runBlocking {
        stubHappyDeps()
        val gate = CompletableDeferred<Unit>()
        coEvery { digestCoordinator.digestAndReconcile(any(), any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            "新记忆"
        }

        val first = async(Dispatchers.Default) { service.organizeNow("c1") }
        withTimeout(2_000) { service.organizing.first { "c1" in it } } // 证据：忙态确已置位（PITFALLS 1e）
        assertFalse("进行中重入必须被拒", service.organizeNow("c1"))
        gate.complete(Unit)
        assertTrue(first.await())
        coVerify(exactly = 1) { digestCoordinator.digestAndReconcile(any(), any(), any(), any(), any(), any()) }
    }
}
