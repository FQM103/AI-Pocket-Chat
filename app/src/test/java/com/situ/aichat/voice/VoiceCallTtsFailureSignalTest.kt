package com.situ.aichat.voice

import android.os.Looper
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.stt.SttEngine
import com.situ.aichat.stt.SttRecorder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration

/**
 * T2 行为测试（VU2 失声两级表达 2026-07-12）：真 [VoiceCallController] + 真 [VoiceCallTtsPipeline]，具体
 * 依赖 MockK 假掉。钉失声信号单源 —— `ttsTurnFailures` 计数与 `subtitleFallbackActive`（字幕通话模式）的
 * 进入（≥2 轮）/ 出声自愈（onSentenceCommitted）/ 归零（新通话），且 C5 兜底路径绝不误计入。
 *
 * 「一整轮零句说出口」用「合成器恒失败 + LLM 文本已到手」复现（= 没配音色 / key 坏 / 音色错配的真实形态，
 * 与 [VoiceCallControllerResilienceTest] 的 C4 回落用例同一驱动）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceCallTtsFailureSignalTest {

    private val engine = mockk<SttEngine> {
        every { isAvailable } returns true
        every { openStream() } returns mockk(relaxed = true)
    }
    private val recorder = mockk<SttRecorder>(relaxUnitFun = true) {
        every { start(any(), any(), any()) } returns true
        every { stop() } just runs
    }
    private val audioFocus = mockk<AudioFocusController>(relaxed = true) {
        every { acquire() } returns true
    }
    private val settingsRepository = mockk<SettingsRepository> {
        coEvery { getAppSettings() } returns AppSettings()
    }
    private val callPlayer = mockk<CallTtsPlayer>(relaxed = true) {
        coEvery { play(any()) } returns true // 播放不挂 gate：可用音色时一句即播完即提交
    }
    private val turnService = mockk<VoiceCallTurnService>()
    private val persistence = mockk<VoiceCallPersistence>(relaxed = true)
    private val fallbackVoice = mockk<VoiceCallFallbackVoice>(relaxUnitFun = true) {
        coEvery { bakedClipOrNull(any()) } returns null
    }
    private val followUpService = mockk<VoiceCallFollowUpService>(relaxUnitFun = true)

    private val controller = VoiceCallController(
        engine, recorder, audioFocus, settingsRepository, callPlayer, turnService, persistence,
        fallbackVoice, followUpService,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun driveToListening() {
        controller.startCall("convo-1", "char-1")
        idle()
        advance(1500)
        assertEquals(CallState.LISTENING, controller.state.value)
    }

    /** 合成器恒失败 → 每一轮都「零句说出口」，整段回复回落文字（= 一次失声）。 */
    private fun armAllSynthFail() {
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> null }
        coEvery { turnService.streamResponse(any(), any(), any(), any()) } coAnswers {
            arg<suspend (String) -> Unit>(3).invoke("好呀。现在就出发。")
            "好呀。现在就出发。"
        }
    }

    private fun runSilentRound(userText: String) {
        controller.onFinalResult(userText)
        idle()
        assertEquals(CallState.LISTENING, controller.state.value)
    }

    @Test
    fun `single silent turn - count is 1 and fallback mode not yet active`() {
        armAllSynthFail()
        driveToListening()
        runSilentRound("在吗")

        assertEquals(1, controller.ttsTurnFailures.value)
        assertFalse(controller.subtitleFallbackActive.value)
    }

    @Test
    fun `second silent turn - count reaches 2 and fallback mode activates`() {
        armAllSynthFail()
        driveToListening()
        runSilentRound("在吗")
        runSilentRound("还在吗")

        assertEquals(2, controller.ttsTurnFailures.value)
        assertTrue(controller.subtitleFallbackActive.value)
    }

    @Test
    fun `voice recovers after fallback mode - active clears but count is kept`() {
        armAllSynthFail()
        driveToListening()
        runSilentRound("在吗")
        runSilentRound("还在吗")
        assertTrue(controller.subtitleFallbackActive.value)

        // 下一轮 TTS 真出声 → onSentenceCommitted → 模式即撤；计数不清零（再失声立即重进）。
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> byteArrayOf(1, 2, 3) }
        coEvery { turnService.streamResponse(any(), any(), any(), any()) } coAnswers {
            arg<suspend (String) -> Unit>(3).invoke("我在。")
            "我在。"
        }
        controller.onFinalResult("你终于说话了")
        idle()

        assertFalse(controller.subtitleFallbackActive.value)
        assertEquals(2, controller.ttsTurnFailures.value)
    }

    @Test
    fun `llm failure fallback path is not counted as a silent turn`() {
        // C5：LLM 回合抛异常 → failTurnToListening（走兜底短句/回听），不经 finishAiTurn → 计数不动。
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> null }
        coEvery { turnService.streamResponse(any(), any(), any(), any()) } throws IOException("网络断了")
        driveToListening()
        runSilentRound("在吗")

        assertEquals(0, controller.ttsTurnFailures.value)
        assertFalse(controller.subtitleFallbackActive.value)
    }

    @Test
    fun `new call resets the failure signal`() {
        armAllSynthFail()
        driveToListening()
        runSilentRound("在吗")
        assertEquals(1, controller.ttsTurnFailures.value)

        controller.endCall()
        idle()
        advance(800) // ENDING_DISMISS_MS → IDLE + resetCallState 归零
        assertEquals(CallState.IDLE, controller.state.value)
        assertEquals(0, controller.ttsTurnFailures.value)
        assertFalse(controller.subtitleFallbackActive.value)
    }
}
