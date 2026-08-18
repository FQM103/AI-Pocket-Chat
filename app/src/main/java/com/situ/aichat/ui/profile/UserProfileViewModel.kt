package com.situ.aichat.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.SettingsPreferences
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Profile tab 首屏的轻量 VM：单例用户资料 + 设置首屏要用的两个可见性 gate。 */
@HiltViewModel
class UserProfileViewModel @Inject constructor(
    dao: UserProfileDao,
    private val settings: SettingsRepository,
    private val prefs: SettingsPreferences,
) : ViewModel() {
    val profile: StateFlow<UserProfileEntity?> =
        dao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 货币系统总开关——对齐 iOS ProfileView 把「我的金币 / 收礼盒」整组卡片 gate 在
     * `settings.currencySystemEnabled` 之内（ProfileView.swift:81/105）。关 → Profile 隐藏
     * 钱包/礼物店/收礼盒入口。初值 true = 货币系统默认开（与 [SettingsRepository] 默认一致），
     * 避免加载瞬间闪烁隐藏。
     */
    val currencySystemEnabled: StateFlow<Boolean> =
        settings.appSettings
            .map { it.currencySystemEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * 高级模式（15.2-P1 批0 / P1-24·1:1 iOS SettingsView.swift:22 @AppStorage 默认 false）：
     * 关→设置首屏隐藏高级分组（「角色与聊天」「语音」两整组 + 通用组的日历感知行 + 自动化组的
     * 日记/朋友圈两行，=iOS :43-48 所藏四区的安卓映射）。初值 false = 默认关（绝大多数用户态），
     * 高级用户开屏瞬间分组浮现可接受（DataStore 读取近即时）。
     */
    val advancedModeEnabled: StateFlow<Boolean> =
        prefs.advancedModeEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAdvancedMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setAdvancedModeEnabled(enabled) }
    }

    /**
     * 消息情绪动画开关（P1-5·1:1 iOS AppSettings.swift:122 默认 true）：iOS 在「角色与聊天」区
     * 的「聊天动效」子页里只有这一个开关（ChatEffectSettingsView.swift），安卓平铺为同 gate 组
     * 内的开关行=同等感知。初值 true 防加载瞬间闪烁。
     */
    val emotionAnimationEnabled: StateFlow<Boolean> =
        settings.appSettings
            .map { it.emotionAnimationEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setEmotionAnimation(enabled: Boolean) {
        viewModelScope.launch { settings.setEmotionAnimationEnabled(enabled) }
    }

    /**
     * 自然短句口吻开关（活人感一期 P1）：开 → 角色回复风格块追加「像手机打字那样说话」全局规则；
     * 关 → 风格块与旧值逐字节一致（书面风角色适用）。初值 true 防加载瞬间闪烁，与 [SettingsRepository] 默认一致。
     */
    val textingToneEnabled: StateFlow<Boolean> =
        settings.appSettings
            .map { it.textingToneEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setTextingTone(enabled: Boolean) {
        viewModelScope.launch { settings.setTextingToneEnabled(enabled) }
    }
}
