package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 线下见面设置页 VM（10.2f）。线下偏好经 [SettingsRepository] 持久化，沉浸输入 / 背景样式 / 叙事档位即改即生效。
 */
@HiltViewModel
class ImmersiveSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.appSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setCharacterCanInitiate(enabled: Boolean) =
        edit { settingsRepository.setCharacterCanInitiateOfflineMeeting(enabled) }

    fun setImmersiveInputEnabled(enabled: Boolean) =
        edit { settingsRepository.setOfflineImmersiveInputEnabled(enabled) }

    fun setNarrativeDetail(raw: String) =
        edit { settingsRepository.setOfflineNarrativeDetailRaw(raw) }

    fun setCustomStyle(text: String) = edit { settingsRepository.setOfflineCustomStylePrompt(text) }

    fun setCustomDirective(text: String) = edit { settingsRepository.setOfflineCustomDirectivePrompt(text) }

    fun setCustomEmotion(text: String) = edit { settingsRepository.setOfflineCustomEmotionPrompt(text) }

    fun setMeetingMemoryInjectCount(count: Int) = edit { settingsRepository.setMeetingMemoryInjectCount(count) }

    fun setOfflineAfterglowEnabled(enabled: Boolean) = edit { settingsRepository.setOfflineAfterglowEnabled(enabled) }

    fun setMeetingMemoryMaxLength(chars: Int) = edit { settingsRepository.setMeetingMemoryMaxLength(chars) }

    fun setBackgroundStyle(raw: String) = edit { settingsRepository.setOfflineBackgroundStyleRaw(raw) }

    fun setParticleStyle(raw: String) = edit { settingsRepository.setOfflineParticleStyleRaw(raw) }

    fun setBackgroundColor(hex: String) = edit { settingsRepository.setOfflineBackgroundColor(hex) }

    private inline fun edit(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
