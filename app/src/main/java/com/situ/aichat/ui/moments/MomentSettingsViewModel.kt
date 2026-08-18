package com.situ.aichat.ui.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 朋友圈设置状态（对齐 iOS `MomentSettingsView` 的 4 控件）。 */
data class MomentSettingsState(
    val autoPostFrequency: Int = 2,
    val autoCommentFrequency: Int = 2,
    val commentDelay: Int = 3,
    val autoLikeEnabled: Boolean = true,
    val newPostNotificationEnabled: Boolean = true,
)

/**
 * 朋友圈设置 VM（M06 7.2.8，对齐 iOS `MomentSettingsView`）：发帖频率 / 评论上限 / 评论延迟 / 自动点赞。
 * 改动即写 DataStore（[SettingsRepository] setter 内钳范围），由 MomentGeneration/Interaction 下次读取生效。
 */
@HiltViewModel
class MomentSettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<MomentSettingsState> =
        settingsRepo.appSettings
            .map {
                MomentSettingsState(
                    autoPostFrequency = it.momentAutoPostFrequency,
                    autoCommentFrequency = it.momentAutoCommentFrequency,
                    commentDelay = it.momentCommentDelay,
                    autoLikeEnabled = it.momentAutoLikeEnabled,
                    newPostNotificationEnabled = it.momentNewPostNotificationEnabled,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MomentSettingsState())

    fun setAutoPostFrequency(value: Int) = viewModelScope.launch { settingsRepo.setMomentAutoPostFrequency(value) }
    fun setAutoCommentFrequency(value: Int) = viewModelScope.launch { settingsRepo.setMomentAutoCommentFrequency(value) }
    fun setCommentDelay(value: Int) = viewModelScope.launch { settingsRepo.setMomentCommentDelay(value) }
    fun setAutoLikeEnabled(enabled: Boolean) = viewModelScope.launch { settingsRepo.setMomentAutoLikeEnabled(enabled) }
    fun setNewPostNotificationEnabled(enabled: Boolean) =
        viewModelScope.launch { settingsRepo.setMomentNewPostNotificationEnabled(enabled) }
}
