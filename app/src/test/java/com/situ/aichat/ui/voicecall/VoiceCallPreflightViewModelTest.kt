package com.situ.aichat.ui.voicecall

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.tts.TtsConfiguration
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsProviderType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T2 行为测试（VU1 拨号门·J2/J3 分支）：真 [VoiceCallPreflightViewModel] + 真 [com.situ.aichat.tts.TtsService]
 * `hasAvailableVoice` 判定，两个仓库 MockK 假掉。钉 E1（fail-open）/E2（双空→角色音色）/E3（有 remote 无 key→
 * 全局配置）/E4（系统音色→放行）四分支。
 */
class VoiceCallPreflightViewModelTest {

    private val characterRepo = mockk<CharacterRepository>()
    private val ttsConfigRepo = mockk<TtsConfigurationRepository>()
    private fun vm() = VoiceCallPreflightViewModel(characterRepo, ttsConfigRepo)

    private fun character(voiceIdentifier: String = "", remoteVoiceID: String = "") = CharacterEntity(
        uuid = "c1",
        name = "Momo",
        creationDate = 0L,
        voiceIdentifier = voiceIdentifier,
        remoteVoiceID = remoteVoiceID,
    )

    @Test fun `E1 repo throws - fail-open returns null`() = runTest {
        coEvery { characterRepo.get(any()) } throws RuntimeException("db down")
        assertNull(vm().check("c1"))
    }

    @Test fun `E1b character not found - fail-open returns null`() = runTest {
        coEvery { characterRepo.get(any()) } returns null
        assertNull(vm().check("c1"))
    }

    @Test fun `E2 no voice picked at all - CHARACTER_VOICE`() = runTest {
        coEvery { characterRepo.get(any()) } returns character(voiceIdentifier = "", remoteVoiceID = "")
        coEvery { ttsConfigRepo.getConfiguration() } returns TtsConfiguration() // SYSTEM, empty voice
        coEvery { ttsConfigRepo.getApiKey() } returns ""
        val info = vm().check("c1")
        assertEquals(VoiceSetupNeed.CHARACTER_VOICE, info?.need)
        assertEquals("Momo", info?.characterName)
    }

    @Test fun `E3 remote voice picked but key missing - GLOBAL_CONFIG`() = runTest {
        coEvery { characterRepo.get(any()) } returns character(remoteVoiceID = "voice-123")
        // Volink 配置 baseURL/model 都在，唯独 apiKey 缺 → hasAvailableVoice=false，且非双空 → 全局配置分支。
        coEvery { ttsConfigRepo.getConfiguration() } returns TtsConfiguration(
            providerType = TtsProviderType.VOLINK,
            baseURL = "https://api.example.com",
            modelName = "speech-01",
        )
        coEvery { ttsConfigRepo.getApiKey() } returns ""
        assertEquals(VoiceSetupNeed.GLOBAL_CONFIG, vm().check("c1")?.need)
    }

    @Test fun `E4 system voice configured - has voice returns null`() = runTest {
        coEvery { characterRepo.get(any()) } returns character(voiceIdentifier = "zh-CN-Xiaoxiao")
        coEvery { ttsConfigRepo.getConfiguration() } returns TtsConfiguration() // SYSTEM
        coEvery { ttsConfigRepo.getApiKey() } returns ""
        assertNull(vm().check("c1"))
    }
}
