package com.situ.aichat.ui.voicecall

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.util.AvatarStore
import com.situ.aichat.util.WallpaperStore
import com.situ.aichat.voice.CallState
import com.situ.aichat.voice.VoiceCallController
import com.situ.aichat.voice.VoiceCallError
import com.situ.aichat.voice.VoiceTranscriptLine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A transcript line resolved for display: speaker name + text + whether it's the user's line. */
data class CallSubtitleLine(val speakerName: String, val text: String, val isUser: Boolean)

/**
 * Thin bridge between the `@Singleton` [VoiceCallController] (which actually runs the call, started by
 * `VoiceCallService`) and the Compose [VoiceCallScreen]. It re-exposes the controller's state flows, loads
 * the character (name + avatar) and the user's name for display, derives the live-subtitle lines, and
 * forwards the hang-up / speaker actions. The call is NOT started here — the service owns its lifecycle;
 * this VM only observes + controls an in-flight call (it survives nothing the controller doesn't).
 */
@HiltViewModel
class VoiceCallViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: VoiceCallController,
    private val characterRepo: CharacterRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val characterUuid: String = savedStateHandle.get<String>(ARG_CHARACTER_UUID).orEmpty()

    val state: StateFlow<CallState> = controller.state
    val audioLevel: StateFlow<Float> = controller.audioLevel
    val isSpeakerEnabled: StateFlow<Boolean> = controller.isSpeakerEnabled
    val callDurationSeconds: StateFlow<Long> = controller.callDurationSeconds
    val error: StateFlow<VoiceCallError?> = controller.error
    // VU2 失声两级表达：本通累计零句轮数 + 字幕通话模式旗（屏级派生瞬时短句/自动展开/钉行·角标）。
    val ttsTurnFailures: StateFlow<Int> = controller.ttsTurnFailures
    val subtitleFallbackActive: StateFlow<Boolean> = controller.subtitleFallbackActive

    private val _characterName = MutableStateFlow("")
    val characterName: StateFlow<String> = _characterName.asStateFlow()

    private val _avatar = MutableStateFlow<Bitmap?>(null)
    val avatar: StateFlow<Bitmap?> = _avatar.asStateFlow()

    /** 通话背景氛围首选：角色聊天壁纸（D-1 拍板·降级链壁纸→头像→光斑由 UI 层判空实现）。 */
    private val _wallpaper = MutableStateFlow<Bitmap?>(null)
    val wallpaper: StateFlow<Bitmap?> = _wallpaper.asStateFlow()

    /** Live-subtitle lines with speaker names resolved (= iOS `recentTranscriptLines`)。voice-1：用户行恒「你」，不取昵称。 */
    val subtitles: StateFlow<List<CallSubtitleLine>> =
        combine(controller.recentTranscript, _characterName) { lines, charName ->
            lines.map { line -> toSubtitle(line, charName) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val character = characterRepo.get(characterUuid)
            _characterName.value = character?.name.orEmpty()
            _avatar.value = character?.avatarPath?.let { AvatarStore.load(it) }
            _wallpaper.value = character?.chatWallpaperPath?.let { WallpaperStore.load(it) }
        }
    }

    private fun toSubtitle(line: VoiceTranscriptLine, charName: String): CallSubtitleLine {
        val isUser = line.role == "user"
        return CallSubtitleLine(
            // voice-1：用户说话人固定「你」(1:1 iOS speakerName 恒为字面量)，不显昵称；AI 行空名回退 app_name。
            speakerName = if (isUser) context.getString(R.string.voice_call_speaker_you) else charName.ifBlank { context.getString(R.string.app_name) },
            text = line.text,
            isUser = isUser,
        )
    }

    fun hangUp() = controller.endCall()

    fun toggleSpeaker() = controller.toggleSpeaker()

    /** App went to background — pause the in-flight turn (= iOS handleAppDidEnterBackground). Driven by the screen. */
    fun onAppBackgrounded() = controller.onAppBackgrounded()

    /** App returned to foreground — resume per the recovery action (= iOS handleAppWillEnterForeground). */
    fun onAppForegrounded() = controller.onAppForegrounded()

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
    }
}
