package com.situ.aichat.voice

import android.os.Looper
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.stt.SttEngine
import com.situ.aichat.stt.SttRecorder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * T2 行为测试（微图纸 2026-07-11-语音通话逻辑修缮）：真 [VoiceCallController] + 真 [VoiceCallStt] /
 * [VoiceCallTtsPipeline]，具体依赖 MockK 假掉，Robolectric 主循环驱动（同 SettingsOverviewViewModelTest 惯例）。
 * 回归钉两个修复：① 焦点丢失打断进行中的 LLM 回合后，焦点回来必须以同一句话重发回合（原先恒回监听、提问被丢）；
 * ② 焦点丢失 + 切后台叠加（真来电）时，恢复动作恰好派发一次、麦克风不重复启动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceCallControllerRecoveryTest {

    private val focusLost = slot<() -> Unit>()
    private val focusGained = slot<() -> Unit>()

    private val engine = mockk<SttEngine> {
        every { isAvailable } returns true
        every { openStream() } returns mockk(relaxed = true)
    }
    private val recorder = mockk<SttRecorder> {
        every { start(any(), any(), any()) } returns true
        every { stop() } just runs
    }
    private val audioFocus = mockk<AudioFocusController>(relaxUnitFun = true) {
        every { onFocusLost = capture(focusLost) } just runs
        every { onFocusRegained = capture(focusGained) } just runs
        every { acquire() } returns true
    }
    private val settingsRepository = mockk<SettingsRepository> {
        coEvery { getAppSettings() } returns AppSettings()
    }
    private val callPlayer = mockk<CallTtsPlayer>(relaxed = true)
    private val turnService = mockk<VoiceCallTurnService> {
        coEvery { resolveSynthesizer(any()) } returns { _: String -> null }
        // 永不返回 = 模拟「LLM 回合进行中」；打断路径靠取消收尾。
        coEvery { streamResponse(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
    }
    private val persistence = mockk<VoiceCallPersistence>(relaxed = true)
    private val fallbackVoice = mockk<VoiceCallFallbackVoice>(relaxUnitFun = true) {
        coEvery { bakedClipOrNull(any()) } returns null // 默认无兜底成品 → 既有行为不变
    }
    private val followUpService = mockk<VoiceCallFollowUpService>(relaxUnitFun = true)

    private val controller = VoiceCallController(
        engine, recorder, audioFocus, settingsRepository, callPlayer, turnService, persistence,
        fallbackVoice, followUpService,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    /** startCall → 拨号 1.5s → LISTENING → 注入识别结果 → PROCESSING（LLM 回合挂起中）。 */
    private fun driveToMidStreamProcessing(userText: String) {
        controller.startCall("convo-1", "char-1")
        idle()
        advance(1500)
        assertEquals(CallState.LISTENING, controller.state.value)
        controller.onFinalResult(userText)
        idle()
        assertEquals(CallState.PROCESSING, controller.state.value)
        coVerify(exactly = 1) { turnService.streamResponse("convo-1", "char-1", userText, any()) }
    }

    @Test
    fun `focus loss during llm turn - focus regain restarts the turn with the same utterance`() {
        driveToMidStreamProcessing("今天想你了")

        focusLost.captured.invoke()
        idle()
        assertEquals(CallState.PROCESSING, controller.state.value)
        assertEquals(VoiceCallError.INTERRUPTED, controller.error.value)

        focusGained.captured.invoke()
        idle()
        // 修复①的钉子：同一句话重发了回合，而不是丢弃提问回监听。
        coVerify(exactly = 2) { turnService.streamResponse("convo-1", "char-1", "今天想你了", any()) }
        assertEquals(CallState.PROCESSING, controller.state.value)
        assertNull(controller.error.value)
        // 恢复的是 LLM 回合，不是回监听。麦克风共启动 3 次：初始 LISTENING + 两段思考窗的思考监听
        // （C2 思考中可打断：PROCESSING 开麦是新常态；此处钉「没有多余的第 4 次」= 恢复没有误回监听）。
        verify(exactly = 3) { recorder.start(any(), any(), any()) }
    }

    @Test
    fun `phone call combo - foreground returns first, self-heal dispatches exactly once`() {
        driveToMidStreamProcessing("周末去哪玩")
        focusLost.captured.invoke()
        controller.onAppBackgrounded()
        idle()

        controller.onAppForegrounded() // FOCUS_LOSS 仍挂起 → 探测 acquire()=true → 自愈并派发
        idle()
        coVerify(exactly = 2) { turnService.streamResponse("convo-1", "char-1", "周末去哪玩", any()) }

        focusGained.captured.invoke() // 迟到的 GAIN 必须是 no-op
        idle()
        coVerify(exactly = 2) { turnService.streamResponse(any(), any(), any(), any()) }
        // 初听 + 两段思考监听 = 3；迟到 GAIN 不许带来第 4 次（派发恰好一次的麦克风侧证据）。
        verify(exactly = 3) { recorder.start(any(), any(), any()) }
    }

    @Test
    fun `phone call combo - focus gain returns first, dispatch waits for foreground`() {
        driveToMidStreamProcessing("讲个故事")
        focusLost.captured.invoke()
        controller.onAppBackgrounded()
        idle()

        focusGained.captured.invoke() // BACKGROUND 仍挂起 → 只清焦点原因，不派发
        idle()
        coVerify(exactly = 1) { turnService.streamResponse(any(), any(), any(), any()) }

        controller.onAppForegrounded()
        idle()
        coVerify(exactly = 2) { turnService.streamResponse("convo-1", "char-1", "讲个故事", any()) }
        // 初听 + 两段思考监听 = 3（提前到的 GAIN 只清原因、不派发 → 不产生额外麦启动）。
        verify(exactly = 3) { recorder.start(any(), any(), any()) }
    }

    /**
     * T5 复核 R-1 回归钉：TA 说话中（第一句已播完）被用户插话打断，之后连续两次被中断（如两通来电）——
     * 第二次恢复绝不许把用户亲手打断的整段回复重播（陈旧 pendingAiResponse 不得结算成
     * RESUME_PENDING_PLAYBACK）。修复 = saveAiMessage 落库即清 pendingAiResponse（iOS 同语义）。
     */
    @Test
    fun `barge-in then two interruptions - never replays the interrupted response`() {
        // 让回合真正到达 AI_SPEAKING：合成器出真音频（类级默认 null 会全句废弃到不了播放），
        // LLM 全文两句到手，第一句播完、第二句播放挂起。
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> ByteArray(1) }
        coEvery { turnService.streamResponse(any(), any(), any(), any()) } coAnswers {
            val onToken = arg<suspend (String) -> Unit>(3)
            onToken("今天想去江边走走。顺便吃碗小面。")
            "今天想去江边走走。顺便吃碗小面。"
        }
        var playCalls = 0
        coEvery { callPlayer.play(any()) } coAnswers {
            playCalls++
            if (playCalls == 1) true else awaitCancellation()
        }

        controller.startCall("convo-1", "char-1")
        idle()
        advance(1500)
        controller.onFinalResult("周末有什么安排")
        idle()
        assertEquals(CallState.AI_SPEAKING, controller.state.value)

        controller.handleInterruption() // 用户插话：第一句已说出 → heardText 非空分支
        idle()
        assertEquals(CallState.USER_SPEAKING, controller.state.value)

        // 第一次中断 + 恢复（heardText 仍非空 → RESUME_LISTENING，顺带清掉了管线残句）。
        focusLost.captured.invoke(); idle()
        focusGained.captured.invoke(); idle()
        assertEquals(CallState.LISTENING, controller.state.value)

        // 第二次中断 + 恢复：管线已空、无在流回合——修复前陈旧 pendingAiResponse 在此结算成整段重播。
        focusLost.captured.invoke(); idle()
        focusGained.captured.invoke(); idle()
        assertEquals(CallState.LISTENING, controller.state.value)
        // 重播路径会再次解析合成器并转 PROCESSING——两者都不许发生。
        coVerify(exactly = 1) { turnService.resolveSynthesizer(any()) }
        coVerify(exactly = 1) { turnService.streamResponse(any(), any(), any(), any()) }
    }

    @Test
    fun `hang up - persists the call record then returns to IDLE and releases the player`() {
        driveToMidStreamProcessing("晚安")

        controller.endCall()
        idle()
        assertEquals(CallState.ENDING, controller.state.value)
        coVerify(exactly = 1) { persistence.saveCallRecord("convo-1", any(), any(), any()) }

        advance(800)
        assertEquals(CallState.IDLE, controller.state.value)
        verify(exactly = 1) { callPlayer.release() }
    }
}
