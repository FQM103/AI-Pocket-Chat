package com.situ.aichat.ui.world.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.world.WorldBootstrap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

/**
 * 首启轻三步状态机（W13 图纸 §3.5/§4.5）：步进 1→2→3、时区钉库、done 标记。首启只在 `!worldOnboardingDone`
 * 时露脸（决策 34③ 静默建世不重建·世界本体照常渲染）。时区落库均先静默建世（幂等·E14）。
 */
@HiltViewModel
class WorldOnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val worldDao: WorldDao,
    private val bootstrap: WorldBootstrap,
) : ViewModel() {

    /** 三步是否该露脸（= 尚未走过·done 后永久 false）。初始 false 避免 appSettings 未载时误闪。 */
    val visible: StateFlow<Boolean> = settingsRepository.appSettings
        .map { !it.worldOnboardingDone }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _step = MutableStateFlow(1)
    val step: StateFlow<Int> = _step.asStateFlow()

    private val _pinnedZoneId = MutableStateFlow<String?>(null)
    val pinnedZoneId: StateFlow<String?> = _pinnedZoneId.asStateFlow()

    // 用户经「改一下」显式选过时区 → 「就这个」不再覆盖为设备时区（保住其选择·图纸 §3.5 confirmZone 默认档）。
    private var explicitlyPicked = false

    /** 设备当前时区 id（步一时区栏「来自设备」展示 + confirmZone 钉住的默认值）。 */
    val deviceZoneId: String get() = ZoneId.systemDefault().id

    /**
     * 主钮（图纸 §4.5「就这个/去看看/开始逛逛」）：step1 钉时区并进 step2；step2 进 step3；step3 完成。
     */
    fun primaryAction() {
        when (_step.value) {
            1 -> {
                confirmZone()
                _step.value = 2
            }
            2 -> _step.value = 3
            else -> finish()
        }
    }

    /** 「就这个」默认档：未经「改一下」显式选过 → 钉住设备当前时区（决策 7·本地锚不跟设备漂）。 */
    private fun confirmZone() {
        if (explicitlyPicked) return
        writeTimezone(deviceZoneId)
    }

    /** 「改一下」sheet 选择（zoneId=null 即显式「跟随设备」）：即时写库并记为已显式选。 */
    fun pickZone(zoneId: String?) {
        explicitlyPicked = true
        writeTimezone(zoneId)
    }

    private fun writeTimezone(zoneId: String?) {
        _pinnedZoneId.value = zoneId
        viewModelScope.launch {
            bootstrap.ensureCreated(System.currentTimeMillis())
            worldDao.updateUserTimezone(zoneId)
        }
    }

    /** 「稍后再说」：时区不写（保持 null=跟设备）+ done。 */
    fun skip() {
        markDone()
    }

    /** 第 3 步完成：done。 */
    fun finish() {
        markDone()
    }

    private fun markDone() {
        viewModelScope.launch { settingsRepository.setWorldOnboardingDone(true) }
    }
}
