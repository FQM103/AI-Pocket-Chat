package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Voice-call settings VM (P10.1h-3): exposes the barge-in sensitivity as a Slider position and
 * persists it back as the inverted energy threshold via [SettingsRepository]
 * (= iOS `VoiceCallSettingsView` binding over `AppSettings.voiceInterruptionSensitivity`).
 */
@HiltViewModel
class VoiceCallSettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val sliderPosition: StateFlow<Float> =
        settingsRepo.appSettings
            .map { VoiceCallSensitivity.sliderFromThreshold(it.sanitizedVoiceCallInterruptThreshold) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                VoiceCallSensitivity.sliderFromThreshold(AppSettings.DEFAULT_VOICE_CALL_INTERRUPT_THRESHOLD),
            )

    /** Persist the slider position as the stored energy threshold (0.45 − slider, clamped). */
    fun setSliderPosition(slider: Float) = viewModelScope.launch {
        settingsRepo.setVoiceCallInterruptThreshold(VoiceCallSensitivity.thresholdFromSlider(slider))
    }
}
