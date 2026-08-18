package com.situ.aichat.ui.diary

import android.content.Context
import com.situ.aichat.stt.RecordedVoiceClip
import com.situ.aichat.stt.SttEngine
import com.situ.aichat.stt.VoiceMessageRecorder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ComposeDiaryVoiceController] J6 T2（图纸 §4-J6）——「说一段」松手落笔状态机「真的能用」。
 * MockK 假 recorder/engine/appContext；Unconfined 让转写 fire-and-forget 同步落定（转写走注入的 Unconfined dispatcher）。
 * finishVoice 四路：成功追加换行拼接 / 太短丢弃 / 取消丢弃 / 引擎不可用→错误事件；另覆盖空正文不加换行 + 未录音无操作。
 */
class ComposeDiaryVoiceControllerTest {

    private lateinit var voiceRecorder: VoiceMessageRecorder
    private lateinit var sttEngine: SttEngine
    private lateinit var appContext: Context
    private lateinit var isRecordingFlow: MutableStateFlow<Boolean>
    private lateinit var controller: ComposeDiaryVoiceController

    private var content = ""
    private var lastMessage: String? = null

    @Before
    fun setUp() {
        voiceRecorder = mockk(relaxed = true)
        sttEngine = mockk(relaxed = true)
        appContext = mockk(relaxed = true)
        isRecordingFlow = MutableStateFlow(false)
        every { voiceRecorder.isRecording } returns isRecordingFlow
        every { voiceRecorder.level } returns MutableStateFlow(0f)
        every { voiceRecorder.durationMs } returns MutableStateFlow(0L)
        every { appContext.getString(any<Int>()) } returns "提示文案"
        content = ""
        lastMessage = null
        controller = ComposeDiaryVoiceController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            appContext = appContext,
            voiceRecorder = voiceRecorder,
            sttEngine = sttEngine,
            currentContent = { content },
            setContent = { content = it },
            emitMessage = { lastMessage = it },
            transcribeDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun clip(sec: Double) = RecordedVoiceClip(shortArrayOf(1, 2, 3, 4), sec)

    @Test
    fun `成功_转写文换行追加到现有正文末尾`() {
        content = "已有正文"
        isRecordingFlow.value = true
        every { voiceRecorder.stop() } returns clip(2.0)
        every { sttEngine.isAvailable } returns true
        coEvery { sttEngine.transcribe(any()) } returns "转写文"
        controller.finishVoice()
        assertEquals("已有正文\n转写文", content)
        assertFalse(controller.isTranscribing.value)
    }

    @Test
    fun `成功_空正文直接置入不加前导换行`() {
        content = ""
        isRecordingFlow.value = true
        every { voiceRecorder.stop() } returns clip(2.0)
        every { sttEngine.isAvailable } returns true
        coEvery { sttEngine.transcribe(any()) } returns "转写文"
        controller.finishVoice()
        assertEquals("转写文", content)
    }

    @Test
    fun `太短_丢弃并提示_不追加`() {
        isRecordingFlow.value = true
        every { voiceRecorder.stop() } returns clip(0.1) // < 0.3s
        controller.finishVoice()
        assertEquals("", content)
        assertNotNull(lastMessage) // 太短提示
    }

    @Test
    fun `取消态_丢弃_不追加不转写`() {
        content = "已有正文"
        isRecordingFlow.value = true
        controller.updateVoiceDrag(100f) // > 80 → cancelling
        every { voiceRecorder.stop() } returns clip(2.0)
        controller.finishVoice()
        assertEquals("已有正文", content) // 未改
        coVerify(exactly = 0) { sttEngine.transcribe(any()) }
    }

    @Test
    fun `引擎不可用_发错误事件_不追加`() {
        isRecordingFlow.value = true
        every { voiceRecorder.stop() } returns clip(2.0)
        every { sttEngine.isAvailable } returns false
        controller.finishVoice()
        assertEquals("", content)
        assertNotNull(lastMessage) // 不可用提示
    }

    @Test
    fun `转写为空_发错误事件_不追加`() {
        isRecordingFlow.value = true
        every { voiceRecorder.stop() } returns clip(2.0)
        every { sttEngine.isAvailable } returns true
        coEvery { sttEngine.transcribe(any()) } returns "  " // 空白 → empty
        controller.finishVoice()
        assertEquals("", content)
        assertNotNull(lastMessage)
    }

    @Test
    fun `未录音_finishVoice 无操作`() {
        isRecordingFlow.value = false
        controller.finishVoice()
        assertEquals("", content)
        assertNull(lastMessage)
    }

    @Test
    fun `上滑越阈值置将取消_未越不取消`() {
        isRecordingFlow.value = true
        controller.updateVoiceDrag(100f)
        assertTrue(controller.voiceCancelling.value)
        controller.updateVoiceDrag(50f)
        assertFalse(controller.voiceCancelling.value)
    }

    @Test
    fun `开始录音失败_发错误事件`() {
        every { voiceRecorder.start(any()) } returns false
        controller.startVoice()
        assertNotNull(lastMessage)
    }
}
