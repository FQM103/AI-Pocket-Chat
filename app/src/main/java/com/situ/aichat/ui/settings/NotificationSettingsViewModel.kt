package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.SettingsPreferences
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.CalendarNotificationScheduler
import com.situ.aichat.notification.CalendarReminderMode
import com.situ.aichat.notification.EconomyNotificationTier
import com.situ.aichat.notification.NotificationScheduler
import com.situ.aichat.work.ReliabilityPromptController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 通知设置页 VM（P6.1c-ii）。读写全局开关 / 夜间免打扰 / 每角色开关（经 [SettingsRepository] 持久化），
 * 改动后立即让 [NotificationScheduler] 重排，使设置即时生效。
 */
@HiltViewModel
class NotificationSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val characterRepository: CharacterRepository,
    private val notificationScheduler: NotificationScheduler,
    private val calendarNotificationScheduler: CalendarNotificationScheduler,
    private val reliabilityPromptController: ReliabilityPromptController,
    settingsPreferences: SettingsPreferences,
) : ViewModel() {

    /** 高级模式（P1-24 设备本地 gate）：经济动态三档 section 仅高级模式显示（P1-40·放进被 gate 组的拍板）。 */
    val advancedModeEnabled: StateFlow<Boolean> = settingsPreferences.advancedModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val globalEnabled: StateFlow<Boolean> = settingsRepository.appSettings
        .map { it.notificationsEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // 夜间免打扰（主动通知真实感改造）：窗内到点的主动消息一律作废，不顺延补发。
    val quietHoursEnabled: StateFlow<Boolean> = settingsRepository.appSettings
        .map { it.quietHoursEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val quietHoursStartMinute: StateFlow<Int> = settingsRepository.appSettings
        .map { it.quietHoursStartMinute }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1380)

    val quietHoursEndMinute: StateFlow<Int> = settingsRepository.appSettings
        .map { it.quietHoursEndMinute }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 450)

    val characters: StateFlow<List<CharacterEntity>> = characterRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val disabledCharacterIds: StateFlow<Set<String>> = settingsRepository.disabledNotificationCharacterIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // P6.2 忙碌时延迟回复已整体删除（2026-07-11 用户拍板）。

    // P6.3 日历提醒方式
    val calendarReminderMode: StateFlow<CalendarReminderMode> = settingsRepository.appSettings
        .map { CalendarReminderMode.fromRaw(it.calendarReminderMode) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarReminderMode.BOTH)

    // P1-33 关系里程碑庆祝通知（安卓超越 iOS）
    val milestoneEnabled: StateFlow<Boolean> = settingsRepository.appSettings
        .map { it.milestoneNotificationEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 里程碑庆祝开关（纯偏好写入；下次升级判定生效）。 */
    fun setMilestoneEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setMilestoneNotificationEnabled(enabled) }
    }

    // P1-40 角色经济动态通知三档（安卓超越 iOS）
    val economyTier: StateFlow<EconomyNotificationTier> = settingsRepository.appSettings
        .map { EconomyNotificationTier.fromRaw(it.economyNotificationTier) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EconomyNotificationTier.BRIEF)

    /** 切经济动态档位（纯偏好写入；下次维护循环生效，无需重排任何调度）。 */
    fun setEconomyTier(tier: EconomyNotificationTier) {
        viewModelScope.launch { settingsRepository.setEconomyNotificationTier(tier.raw) }
    }

    /** 切换日历提醒方式：写入后立即刷新——切到「仅系统提醒」撤掉 app 通知，切到含角色则重排（无角色入参，内部选最高火花角色）。 */
    fun setCalendarReminderMode(mode: CalendarReminderMode) {
        viewModelScope.launch {
            settingsRepository.setCalendarReminderMode(mode.raw)
            calendarNotificationScheduler.refreshForForeground()
        }
    }

    /** 全局开关：开 → 重排全部；关 → 撤销全部（scheduleAll 内部据开关处理）。 */
    fun setGlobalEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotificationsEnabled(enabled)
            notificationScheduler.scheduleAll()
            // 13.7a：开启定时提醒 = 依赖后台，首次开时主动引导 HyperOS 白名单（一次性）。
            if (enabled) reliabilityPromptController.onBackgroundFeatureEnabled()
        }
    }

    /** 免打扰开关：写入后强制重排——已排在窗内的旧闹钟要撤掉，关闭时窗内时段要重新排得上。 */
    fun setQuietHoursEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setQuietHoursEnabled(enabled)
            rescheduleAll()
        }
    }

    /** 免打扰起点（当日分钟数）：窗口变了 → 重排。 */
    fun setQuietHoursStartMinute(minuteOfDay: Int) {
        viewModelScope.launch {
            settingsRepository.setQuietHoursStartMinute(minuteOfDay)
            rescheduleAll()
        }
    }

    /** 免打扰终点（当日分钟数）：窗口变了 → 重排。 */
    fun setQuietHoursEndMinute(minuteOfDay: Int) {
        viewModelScope.launch {
            settingsRepository.setQuietHoursEndMinute(minuteOfDay)
            rescheduleAll()
        }
    }

    /** 先撤旧再全量重建（照原切文案模式的制式）：免打扰窗是排程期预过滤条件，不撤旧则窗内旧闹钟仍在。 */
    private suspend fun rescheduleAll() {
        notificationScheduler.cancelAll()
        notificationScheduler.scheduleAll()
    }

    /** 每角色开关：开 → 重排该角色；关 → 撤销该角色。 */
    fun setCharacterEnabled(characterId: String, enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCharacterNotificationEnabled(characterId, enabled)
            characterRepository.get(characterId)?.let { notificationScheduler.handlePreferenceChange(it, enabled) }
        }
    }
}
