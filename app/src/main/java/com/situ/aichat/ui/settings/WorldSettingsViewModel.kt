package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldUserResidentDao
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.world.WorldBootstrap
import com.situ.aichat.world.cast.WorldResidentService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 世界设置二级页四节状态（图纸 §4.4）·`timezoneId=null` = 跟随设备。 */
data class WorldSettingsUiState(
    val vividnessTier: String = AppSettings.WORLD_VIVIDNESS_STANDARD,
    val notificationTier: String = AppSettings.WORLD_NOTIFICATION_GENTLE,
    val relationshipsEnabled: Boolean = true,
    val romanceEnabled: Boolean = false,
    val timezoneId: String? = null,
    /** 战役 B：已招募之外、库里现存用户自建居民数（设置入口副标「已有 n/50 位」回显）。 */
    val residentCount: Int = 0,
    val residentCap: Int = WorldResidentService.MAX_RESIDENTS,
)

/**
 * 世界设置二级页 VM（图纸 §3.4/§4.4）：读 [SettingsRepository.appSettings] 四项 + [WorldDao] 用户时区；
 * 全部即时写库（无「保存」按钮）。合法值恒 [AppSettings] 常量。时区落库前先静默建世（E14·幂等）。
 */
@HiltViewModel
class WorldSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val worldDao: WorldDao,
    private val bootstrap: WorldBootstrap,
    private val userResidentDao: WorldUserResidentDao,
) : ViewModel() {

    // 时区不在 appSettings（存 world_state 单行）——单独读，写后即时刷新。
    private val _timezoneId = MutableStateFlow<String?>(null)

    val state: StateFlow<WorldSettingsUiState> =
        combine(settingsRepository.appSettings, _timezoneId, userResidentDao.observeCount()) { s, tz, residentCount ->
            WorldSettingsUiState(
                vividnessTier = s.worldVividnessTier,
                notificationTier = s.worldNotificationTier,
                relationshipsEnabled = s.worldRelationshipsEnabled,
                romanceEnabled = s.worldRomanceEnabled,
                timezoneId = tz,
                residentCount = residentCount,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorldSettingsUiState())

    init {
        viewModelScope.launch { _timezoneId.value = worldDao.getState()?.userTimezoneId }
    }

    /** 鲜活度（合法值恒 [AppSettings.WORLD_VIVIDNESS_LITE]/STANDARD/RICH）。 */
    fun setVividness(raw: String) {
        viewModelScope.launch { settingsRepository.setWorldVividnessTier(raw) }
    }

    /** 世界通知（合法值恒 [AppSettings.WORLD_NOTIFICATION_SILENT]/GENTLE/ALL）。 */
    fun setNotification(raw: String) {
        viewModelScope.launch { settingsRepository.setWorldNotificationTier(raw) }
    }

    fun setRelationships(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setWorldRelationshipsEnabled(enabled) }
    }

    fun setRomance(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setWorldRomanceEnabled(enabled) }
    }

    /** 我的时区（null = 跟随设备）：先静默建世（幂等）再定点写 world_state.userTimezoneId（E14）。 */
    fun setTimezone(zoneId: String?) {
        viewModelScope.launch {
            bootstrap.ensureCreated(System.currentTimeMillis())
            worldDao.updateUserTimezone(zoneId)
            _timezoneId.value = zoneId
        }
    }
}
