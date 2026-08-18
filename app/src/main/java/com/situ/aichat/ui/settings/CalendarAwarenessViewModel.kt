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

/** 日历集成开关 + 操作确认开关的 UI 状态（1:1 iOS CalendarSettingsView 的两个 Toggle）。 */
data class CalendarSettingsState(
    val integrationEnabled: Boolean = true,
    val actionConfirmation: Boolean = true,
)

/**
 * 日历与提醒设置 VM（P12.1c）：日历集成总开关 + 操作确认（受集成门控）。这两字段早被 PromptBuilder /
 * ChatViewModel 读取，本屏接上读写 UI；开启集成时由屏幕联动请求 READ/WRITE_CALENDAR 权限（对齐 iOS
 * `requestAllAccess`）。
 */
@HiltViewModel
class CalendarAwarenessViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<CalendarSettingsState> = settings.appSettings
        .map { CalendarSettingsState(it.calendarIntegrationEnabled, it.calendarActionConfirmation) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            // 默认对齐 AppSettings（两者皆 true），避免加载瞬间闪烁。
            CalendarSettingsState(AppSettings().calendarIntegrationEnabled, AppSettings().calendarActionConfirmation),
        )

    fun setIntegrationEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setCalendarIntegrationEnabled(enabled)
    }

    fun setActionConfirmation(enabled: Boolean) = viewModelScope.launch {
        settings.setCalendarActionConfirmation(enabled)
    }
}
