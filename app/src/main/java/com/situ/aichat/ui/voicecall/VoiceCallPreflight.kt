package com.situ.aichat.ui.voicecall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsService
import com.situ.aichat.tts.TtsVoiceProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * VU1 拨号前置：角色无可用音色时该去哪配（J2 确定性分支·不猜用户意图）。
 */
enum class VoiceSetupNeed {
    /** 角色一个音色都没挑（voiceIdentifier & remoteVoiceID 皆空）→ 去角色编辑·语音设置先挑声音。 */
    CHARACTER_VOICE,

    /** 角色选了远端音色但全局 config/apiKey 缺 → 去全局语音服务设置。 */
    GLOBAL_CONFIG,
}

/**
 * 拨号前置判定的完整素材：需求分支 + 角色名/头像（供「暖夜门厅」拦截 sheet 呈现）。
 * `check` 返回 null 表「有可用音色 = 直接拨」；非空即需要配置、按 [need] 深链。
 */
data class VoicePreflightInfo(
    val need: VoiceSetupNeed,
    val characterName: String,
    val avatarPath: String?,
)

/**
 * VU1/VU3 的判定单源：拨号门（[check]·suspend·门函数用）+ 通话卡尾巴自愈显示（[setupNeed]/[refresh]·VU3 用）。
 * 只读查询 `CharacterRepository` + `TtsConfigurationRepository`，判据委托纯函数 [TtsService.hasAvailableVoice]
 * （chat 语音回复计划在用的同一口径）。挂 chat 屏（`hiltViewModel`）——绝不加进 ChatViewModel（J5·已越红线大户）。
 */
@HiltViewModel
class VoiceCallPreflightViewModel @Inject constructor(
    private val characterRepo: CharacterRepository,
    private val ttsConfigRepo: TtsConfigurationRepository,
) : ViewModel() {

    private val _setupNeed = MutableStateFlow<VoiceSetupNeed?>(null)

    /** VU3 自愈显示：当前角色是否仍缺可用音色（null=不缺/未刷）。由 [refresh] 在 chat 屏 ON_START 驱动。 */
    val setupNeed: StateFlow<VoiceSetupNeed?> = _setupNeed.asStateFlow()

    /**
     * 判定角色能否语音通话（本地只读·J3 fail-open）：有可用音色 → null（直接拨）；否则按 J2 分支返回带角色名/头像的
     * [VoicePreflightInfo]。任何异常（含角色查无）→ null——宁可让既有 C4/C5 兜，绝不因一次 IO 抖动把电话打不出去。
     */
    suspend fun check(characterUuid: String): VoicePreflightInfo? = runCatching {
        val character = characterRepo.get(characterUuid) ?: return@runCatching null
        val profile = TtsVoiceProfile(
            voiceIdentifier = character.voiceIdentifier,
            remoteVoiceID = character.remoteVoiceID,
            ttsEmotionRaw = character.ttsEmotionRaw,
            ttsSpeed = character.ttsSpeed,
            ttsPitch = character.ttsPitch,
        )
        val hasVoice = TtsService.hasAvailableVoice(profile, ttsConfigRepo.getConfiguration(), ttsConfigRepo.getApiKey())
        if (hasVoice) return@runCatching null
        // J2：双空 voice → 先挑声音（系统音色无需任何全局配置）；否则 → 全局 config/key 缺。
        val need = if (character.voiceIdentifier.isBlank() && character.remoteVoiceID.isBlank()) {
            VoiceSetupNeed.CHARACTER_VOICE
        } else {
            VoiceSetupNeed.GLOBAL_CONFIG
        }
        VoicePreflightInfo(need, character.name, character.avatarPath)
    }.getOrNull()

    /** 供 VU3 自愈显示：从设置页返回聊天必经 ON_START → 重判当前音色是否已修好，写入 [setupNeed]。 */
    fun refresh(characterUuid: String) {
        viewModelScope.launch { _setupNeed.value = check(characterUuid)?.need }
    }
}
