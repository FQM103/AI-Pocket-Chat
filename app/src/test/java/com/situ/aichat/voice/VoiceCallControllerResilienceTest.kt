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
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration

/**
 * T2 行为测试（C1 通话体验加固 2026-07-12·「任何状态都能推进、任何失败都有出口」卷）：
 * 真 [VoiceCallController] + 真 [VoiceCallStt] / [VoiceCallTtsPipeline]，具体依赖 MockK 假掉。钉两个修复：
 *
 *  ① 状态机潜伏 bug：首句合成失败、次句成功时，first-ready 必须在「第一次真正开播」点火——修复前钉死在
 *    index==0 的合成成功上，该场景 controller 停在 PROCESSING 却在出声（字幕/打断/状态全错位）。
 *  ② 假听自愈：回听路径（本测试走 LLM 失败路）麦克风启动失败必须自动重试；重试耗尽必须以 RESUME_FAILED
 *    可见收场——绝不允许「显示在听、实际没启麦」的死听。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceCallControllerResilienceTest {

    private val playGate = CompletableDeferred<Unit>()

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
        coEvery { play(any()) } coAnswers { playGate.await(); true }
    }
    private val turnService = mockk<VoiceCallTurnService>()
    private val persistence = mockk<VoiceCallPersistence>(relaxed = true)
    private val fallbackVoice = mockk<VoiceCallFallbackVoice>(relaxUnitFun = true) {
        coEvery { bakedClipOrNull(any()) } returns null // 默认无兜底成品 → 静默回听（各测试按需覆写）
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

    private fun driveToListening() {
        controller.startCall("convo-1", "char-1")
        idle()
        advance(1500)
        assertEquals(CallState.LISTENING, controller.state.value)
    }

    @Test
    fun `first sentence synth fails, second succeeds - state still reaches AI_SPEAKING and reply is saved`() {
        // 两句回复：第一句合成恒失败（DISCARDED），第二句成功。
        coEvery { turnService.resolveSynthesizer(any()) } returns { text: String ->
            if (text.contains("第一句")) null else byteArrayOf(1, 2, 3)
        }
        coEvery { turnService.streamResponse(any(), any(), any(), any()) } coAnswers {
            arg<suspend (String) -> Unit>(3).invoke("第一句。第二句。")
            "第一句。第二句。"
        }

        driveToListening()
        controller.onFinalResult("在吗")
        idle()

        // 修复①的钉子：次句开播时必须进入 AI_SPEAKING（修复前永远停在 PROCESSING、无法打断）。
        assertEquals(CallState.AI_SPEAKING, controller.state.value)

        playGate.complete(Unit) // 放行播放 → 播完 → 落库已说出的第二句 → 回到监听
        idle()
        assertEquals(CallState.LISTENING, controller.state.value)
        coVerify(exactly = 1) { persistence.saveAiMessage("convo-1", "char-1", "第二句。") }
    }

    @Test
    fun `mic restart fails once after llm failure - self-heal retries and recovers listening`() {
        // start 序列：初始监听成功 → LLM 失败后的回听失败一次 → 400ms 重试成功。
        every { recorder.start(any(), any(), any()) } returnsMany listOf(true, false, true)
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> null }
        coEvery { turnService.streamResponse(any(), any(), any(), any()) } throws IOException("网络断了")

        driveToListening()
        controller.onFinalResult("你在干什么")
        idle()

        advance(400) // RESUME_RETRY_MS 后自动重试
        assertEquals(CallState.LISTENING, controller.state.value)
        assertNull("重试成功后错误必须清空", controller.error.value)
        io.mockk.verify(exactly = 3) { recorder.start(any(), any(), any()) }
    }

    @Test
    fun `speaking while AI thinks cancels the stale turn - the new utterance drives a fresh one`() {
        // C2 思考中可打断：LLM 回合挂起中（永不返回），用户开口 → 旧轮作废、新话开新轮。
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> null }
        coEvery { turnService.streamResponse(any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }

        driveToListening()
        controller.onFinalResult("给我讲个长故事")
        idle()
        assertEquals(CallState.PROCESSING, controller.state.value)
        coVerify(exactly = 1) { turnService.streamResponse("convo-1", "char-1", "给我讲个长故事", any()) }

        controller.onThinkingInterruption() // = 思考监听识别到非附和语音 ≥0.3s
        idle()
        assertEquals("话语权立即交还用户", CallState.USER_SPEAKING, controller.state.value)

        controller.onFinalResult("算了，先说说你在干嘛")
        idle()
        assertEquals(CallState.PROCESSING, controller.state.value)
        coVerify(exactly = 1) { turnService.streamResponse("convo-1", "char-1", "算了，先说说你在干嘛", any()) }
        // 两句话都持久化（旧话已落库、新话照常落库）——模型历史两条 user 连续，一起回应。
        coVerify(exactly = 1) { persistence.saveUserMessage("convo-1", "给我讲个长故事") }
        coVerify(exactly = 1) { persistence.saveUserMessage("convo-1", "算了，先说说你在干嘛") }
    }

    @Test
    fun `all sentences fail to synthesize - full reply still reaches subtitles, call card and history`() {
        // C4 TTS 全失败不丢话：合成器恒失败（没配音色/key 坏/音色错配都长这样），LLM 文本已到手。
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> null }
        coEvery { turnService.streamResponse(any(), any(), any(), any()) } coAnswers {
            arg<suspend (String) -> Unit>(3).invoke("好呀。现在就出发。")
            "好呀。现在就出发。"
        }

        driveToListening()
        controller.onFinalResult("要不要去散步")
        idle()

        // 回落整段回复：字幕面板可见 + 落库（通话卡/模型历史同源），且回到监听可继续说话。
        assertEquals(CallState.LISTENING, controller.state.value)
        val lastLine = controller.recentTranscript.value.last()
        assertEquals("assistant", lastLine.role)
        assertEquals("好呀。现在就出发。", lastLine.text)
        coVerify(exactly = 1) { persistence.saveAiMessage("convo-1", "char-1", "好呀。现在就出发。") }
    }

    @Test
    fun `silent call hang-up triggers the in-chat follow-up`() {
        // C6：用户说了话、AI 一句都没能说出口（本例=TTS 全哑）→ 挂断后必须触发聊天圆场。
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> null }
        coEvery { turnService.streamResponse(any(), any(), any(), any()) } coAnswers {
            arg<suspend (String) -> Unit>(3).invoke("我在呀。")
            "我在呀。"
        }
        driveToListening()
        controller.onFinalResult("你在干什么")
        idle()

        controller.endCall()
        idle()
        coVerify(exactly = 1) { followUpService.followUpAfterSilentCall("convo-1", any()) }
    }

    @Test
    fun `hang-up after AI actually spoke does not trigger follow-up`() {
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> byteArrayOf(1) }
        coEvery { turnService.streamResponse(any(), any(), any(), any()) } coAnswers {
            arg<suspend (String) -> Unit>(3).invoke("我在呀。")
            "我在呀。"
        }
        coEvery { callPlayer.play(any()) } returns true // 真出声（committed 置旗）

        driveToListening()
        controller.onFinalResult("你在干什么")
        idle()
        assertEquals(CallState.LISTENING, controller.state.value) // 播完回听

        controller.endCall()
        idle()
        coVerify(exactly = 0) { followUpService.followUpAfterSilentCall(any(), any()) }
    }

    @Test
    fun `hang-up with no user speech does not trigger follow-up`() {
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> null }
        driveToListening() // 拨通即挂，一句没说

        controller.endCall()
        idle()
        coVerify(exactly = 0) { followUpService.followUpAfterSilentCall(any(), any()) }
    }

    @Test
    fun `thinking interruption outside PROCESSING is a no-op`() {
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> null }
        driveToListening()

        controller.onThinkingInterruption()
        idle()
        assertEquals(CallState.LISTENING, controller.state.value)
        coVerify(exactly = 0) { turnService.streamResponse(any(), any(), any(), any()) }
    }

    @Test
    fun `llm failure with baked clip - plays the in-character fallback then listens again`() {
        // C5 预烘焙兜底：LLM 回合失败 → 播本地「没听清」短句（角色音色）→ 回听。系统代言不进字幕/落库。
        val clip = byteArrayOf(9, 9, 9)
        coEvery { fallbackVoice.bakedClipOrNull("char-1") } returns clip
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> null }
        coEvery { turnService.streamResponse(any(), any(), any(), any()) } throws IOException("网络断了")

        driveToListening()
        controller.onFinalResult("在吗")
        idle()
        // 播放挂在 playGate 上 → 此刻正以 AI_SPEAKING 播兜底短句。
        assertEquals(CallState.AI_SPEAKING, controller.state.value)

        playGate.complete(Unit)
        idle()
        assertEquals(CallState.LISTENING, controller.state.value)
        coVerify(exactly = 1) { callPlayer.play(clip) }
        // 兜底句是系统代言：transcript 里只有用户那句，绝无 assistant 行；也绝不落库。
        assertTrue(controller.recentTranscript.value.none { it.role == "assistant" })
        coVerify(exactly = 0) { persistence.saveAiMessage(any(), any(), any()) }
    }

    @Test
    fun `fallback clip is capped at two plays per call - third failure goes straight back to listening`() {
        coEvery { fallbackVoice.bakedClipOrNull("char-1") } returns byteArrayOf(1)
        coEvery { callPlayer.play(any()) } returns true // 不挂 gate，播完即回听
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> null }
        coEvery { turnService.streamResponse(any(), any(), any(), any()) } throws IOException("持续故障")

        driveToListening()
        repeat(3) { round ->
            controller.onFinalResult("第${round}句")
            idle()
            assertEquals(CallState.LISTENING, controller.state.value)
        }
        // 第 1、2 轮播兜底，第 3 轮触顶静默回听——绝不当复读机。
        coVerify(exactly = 2) { callPlayer.play(any()) }
    }

    @Test
    fun `mic restart keeps failing - retries exhaust into visible RESUME_FAILED hang-up, never a deaf call`() {
        every { recorder.start(any(), any(), any()) } returnsMany listOf(true, false, false, false)
        coEvery { turnService.resolveSynthesizer(any()) } returns { _: String -> null }
        coEvery { turnService.streamResponse(any(), any(), any(), any()) } throws IOException("网络断了")

        driveToListening()
        controller.onFinalResult("喂喂")
        idle()
        advance(400) // attempt 1
        advance(400) // attempt 2 → 耗尽 → RESUME_FAILED + endCall

        assertEquals(VoiceCallError.RESUME_FAILED, controller.error.value)
        assertEquals(CallState.ENDING, controller.state.value)
    }
}
