package com.situ.aichat.voice

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.tts.TtsConfiguration
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsProviderType
import com.situ.aichat.tts.TtsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T2（C5 预烘焙兜底语音·Robolectric 真文件系统 + MockK TTS）：
 *  - 烘焙成功 → 成品可读；指纹命中 → 二次调用零合成（不烧额度）；
 *  - 音色变化 → 整组重烘；全部合成失败 → 不留半成品、读取得 null（播放侧静默退化）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceCallFallbackVoiceTest {

    private var character = CharacterEntity(
        uuid = "char-1", name = "角色", creationDate = 0L, remoteVoiceID = "voice-a",
    )
    private val characterRepo = mockk<CharacterRepository>()

    init {
        coEvery { characterRepo.get("char-1") } coAnswers { character }
    }
    private val ttsConfigRepo = mockk<TtsConfigurationRepository> {
        coEvery { getConfiguration() } returns TtsConfiguration(
            providerType = TtsProviderType.VOLINK, baseURL = "https://tts.example", modelName = "m1",
        )
        coEvery { getApiKey() } returns "key-1"
    }
    private val ttsService = mockk<TtsService> {
        coEvery { synthesize(any(), any(), any(), any(), any()) } returns byteArrayOf(1, 2, 3)
    }

    private val service = VoiceCallFallbackVoice(
        RuntimeEnvironment.getApplication(), characterRepo, ttsConfigRepo, ttsService,
    )

    @Test
    fun `bake writes clips readable by bakedClipOrNull`() = runBlocking {
        service.ensureBaked("char-1")
        assertNotNull("烘焙成功后必须能取到成品", service.bakedClipOrNull("char-1"))
        coVerify(exactly = VoiceCallFallbackVoice.FALLBACK_LINE_RES.size) {
            ttsService.synthesize(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `fingerprint hit skips re-synthesis entirely`() = runBlocking {
        service.ensureBaked("char-1")
        service.ensureBaked("char-1") // 音色未变 → 秒退，不再烧合成额度
        coVerify(exactly = VoiceCallFallbackVoice.FALLBACK_LINE_RES.size) {
            ttsService.synthesize(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `voice change invalidates the whole batch and rebakes`() = runBlocking {
        service.ensureBaked("char-1")
        character = character.copy(remoteVoiceID = "voice-b") // 用户给角色换了音色
        service.ensureBaked("char-1")
        coVerify(exactly = VoiceCallFallbackVoice.FALLBACK_LINE_RES.size * 2) {
            ttsService.synthesize(any(), any(), any(), any(), any())
        }
        assertNotNull(service.bakedClipOrNull("char-1"))
    }

    @Test
    fun `all synthesis failing leaves no half-baked cache and reads null`() = runBlocking {
        coEvery { ttsService.synthesize(any(), any(), any(), any(), any()) } returns null
        service.ensureBaked("char-1")
        assertNull("全失败不得留半成品（下通电话整组重试）", service.bakedClipOrNull("char-1"))
    }

    @Test
    fun `character gone is a silent no-op`() = runBlocking {
        coEvery { characterRepo.get("ghost") } returns null
        service.ensureBaked("ghost")
        assertNull(service.bakedClipOrNull("ghost"))
    }
}
