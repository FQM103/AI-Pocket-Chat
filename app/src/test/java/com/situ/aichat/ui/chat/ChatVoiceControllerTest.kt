package com.situ.aichat.ui.chat

import android.content.Context
import com.situ.aichat.stt.RecordedVoiceClip
import com.situ.aichat.stt.SttEngine
import com.situ.aichat.stt.VoiceMessageRecorder
import com.situ.aichat.tts.TtsAudioPlayer
import com.situ.aichat.tts.TtsPlaybackState
import com.situ.aichat.util.AudioStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ChatVoiceController 行为测试——验证刀5 语音录制状态机协作者「真的能用」（不止编译过）。
 *
 * 手法：MockK 假掉 voiceRecorder/sttEngine/ttsAudioPlayer/appContext；voiceRecorder 的 isRecording 用真
 * MutableStateFlow 控录音态；ttsAudioPlayer.state 用真 MutableStateFlow 控试听态；Unconfined 让 fire-and-forget 同步跑完。
 * 覆盖：上滑取消阈值、开始录音成功/失败、结束录音三态（未录/取消/太短）、取消录音、重识别守卫、发送清草稿试听判定、有效录音建草稿。
 * 转写结果分流纯函数（classifyTranscriptOutcome/applyingTranscriptResult）另由 VoiceTranscriptResultTest（T1）覆盖；
 * 草稿后台 STT 更新走 Dispatchers.Default（真后台线程，单测里非确定性）→ 留真机/T4，本测只断同步落定的稳定字段。
 */
class ChatVoiceControllerTest {

    private lateinit var voiceRecorder: VoiceMessageRecorder
    private lateinit var sttEngine: SttEngine
    private lateinit var ttsAudioPlayer: TtsAudioPlayer
    private lateinit var appContext: Context
    private lateinit var errorFlow: MutableStateFlow<String?>
    private lateinit var infoToastFlow: MutableStateFlow<String?>
    private lateinit var isRecordingFlow: MutableStateFlow<Boolean>
    private lateinit var ttsStateFlow: MutableStateFlow<TtsPlaybackState>
    private lateinit var controller: ChatVoiceController

    @Before
    fun setUp() {
        voiceRecorder = mockk(relaxed = true)
        sttEngine = mockk(relaxed = true)
        ttsAudioPlayer = mockk(relaxed = true)
        appContext = mockk(relaxed = true)
        errorFlow = MutableStateFlow(null)
        infoToastFlow = MutableStateFlow(null)
        isRecordingFlow = MutableStateFlow(false)
        ttsStateFlow = MutableStateFlow(TtsPlaybackState())
        every { voiceRecorder.isRecording } returns isRecordingFlow
        every { voiceRecorder.durationMs } returns MutableStateFlow(0L)
        every { voiceRecorder.level } returns MutableStateFlow(0f)
        every { ttsAudioPlayer.state } returns ttsStateFlow
        every { appContext.getString(any<Int>()) } returns "提示文案"
        controller = ChatVoiceController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            appContext = appContext,
            errorFlow = errorFlow,
            infoToastFlow = infoToastFlow,
            voiceRecorder = voiceRecorder,
            sttEngine = sttEngine,
            ttsAudioPlayer = ttsAudioPlayer,
        )
    }

    @After
    fun tearDown() {
        // 只清本类 mock 过的 AudioStore object（仅「有效录音建草稿」一例 mockkObject 了它）；
        // 绝不用 unmockkAll()——它会全局重置 MockK 状态，污染同 JVM 后续测试类（实测会让 ChatOfflineControllerTest 偶发失败）。
        unmockkObject(AudioStore)
    }

    // ---- 上滑取消阈值 ----

    @Test
    fun 录音中上滑越阈值_置将取消() {
        isRecordingFlow.value = true
        controller.updateVoiceRecordingDrag(100f) // > 80
        assertTrue(controller.voiceRecordingCancelling.value)
    }

    @Test
    fun 录音中上滑未越阈值_不取消() {
        isRecordingFlow.value = true
        controller.updateVoiceRecordingDrag(50f) // < 80
        assertFalse(controller.voiceRecordingCancelling.value)
    }

    @Test
    fun 未录音_上滑无效() {
        isRecordingFlow.value = false
        controller.updateVoiceRecordingDrag(100f)
        assertFalse(controller.voiceRecordingCancelling.value)
    }

    // ---- 开始录音 ----

    @Test
    fun 开始录音_启动失败_报错() {
        every { voiceRecorder.start(any()) } returns false
        controller.startVoiceRecording()
        verify { ttsAudioPlayer.stop() } // 先停 TTS 再启麦
        assertNotNull(errorFlow.value)
    }

    @Test
    fun 开始录音_启动成功_停TTS无报错() {
        every { voiceRecorder.start(any()) } returns true
        controller.startVoiceRecording()
        verify { ttsAudioPlayer.stop() }
        assertNull(errorFlow.value)
    }

    // ---- 结束录音三态 ----

    @Test
    fun 结束录音_未录音_无操作() {
        isRecordingFlow.value = false
        controller.finishVoiceRecording()
        verify(exactly = 0) { voiceRecorder.stop() }
        assertNull(controller.voiceDraft.value)
    }

    @Test
    fun 结束录音_取消态_丢弃不建草稿() {
        isRecordingFlow.value = true
        controller.updateVoiceRecordingDrag(100f) // 置 cancelling
        every { voiceRecorder.stop() } returns RecordedVoiceClip(shortArrayOf(1, 2, 3), 1.0)
        controller.finishVoiceRecording()
        assertNull(controller.voiceDraft.value) // 取消态 → 丢弃
    }

    @Test
    fun 结束录音_太短_提示不建草稿() {
        isRecordingFlow.value = true
        every { voiceRecorder.stop() } returns RecordedVoiceClip(shortArrayOf(1, 2, 3), 0.1) // < 0.3s
        controller.finishVoiceRecording()
        assertNotNull(infoToastFlow.value) // 太短提示
        assertNull(controller.voiceDraft.value)
    }

    // ---- 取消录音 ----

    @Test
    fun 取消录音_录音中_停录音() {
        isRecordingFlow.value = true
        controller.cancelVoiceRecordingIfActive()
        verify { voiceRecorder.cancel() }
    }

    @Test
    fun 取消录音_未录音_无操作() {
        isRecordingFlow.value = false
        controller.cancelVoiceRecordingIfActive()
        verify(exactly = 0) { voiceRecorder.cancel() }
    }

    // ---- 重新识别守卫 ----

    @Test
    fun 重新识别_无草稿_无操作() {
        controller.retryVoiceTranscription() // _voiceDraft 为 null → 立即返回
        coVerify(exactly = 0) { sttEngine.transcribe(any()) }
        assertNull(controller.voiceDraft.value)
    }

    // ---- 发送清草稿：试听判定 ----

    @Test
    fun 发送清草稿_正在试听本草稿_停播放() {
        val draft = VoiceDraftState("voice_draft_x", "/p.wav", 1.0, "你好", isTranscriptPending = false)
        ttsStateFlow.value = TtsPlaybackState(playingId = "voice_draft_x", isPlaying = true)
        controller.consumeDraftOnSend(draft)
        verify { ttsAudioPlayer.stop() }
    }

    @Test
    fun 发送清草稿_试听的是别条_不停播放() {
        val draft = VoiceDraftState("voice_draft_x", "/p.wav", 1.0, "你好", isTranscriptPending = false)
        ttsStateFlow.value = TtsPlaybackState(playingId = "其它id", isPlaying = true)
        controller.consumeDraftOnSend(draft)
        verify(exactly = 0) { ttsAudioPlayer.stop() }
    }

    // ---- 有效录音 → 建草稿（占位待转写） ----

    @Test
    fun 结束录音_有效_建草稿落占位待转写() {
        mockkObject(AudioStore)
        coEvery { AudioStore.saveBytes(any(), any(), any()) } returns "/saved.wav"
        // sttEngine.isAvailable relaxed=false → 后台 STT job 走 UNAVAILABLE 分支快速收尾（只动 transcript/pending，
        // 不动 audioPath/durationSec）；断言只看同步落定且永不变的稳定字段。
        isRecordingFlow.value = true
        every { voiceRecorder.stop() } returns RecordedVoiceClip(shortArrayOf(1, 2, 3, 4), 2.0)
        controller.finishVoiceRecording()
        val draft = controller.voiceDraft.value
        assertNotNull(draft)
        assertEquals("/saved.wav", draft!!.audioPath)
        assertEquals(2.0, draft.durationSec, 0.0)
    }
}
