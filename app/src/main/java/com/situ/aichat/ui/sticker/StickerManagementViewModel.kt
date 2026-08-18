package com.situ.aichat.ui.sticker

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StickerRepository
import com.situ.aichat.sticker.DisabledBuiltInStickerStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 表情包管理（1:1 iOS `StickerManagementView` + `HiddenBuiltInStickersView`）。隐藏内置走
 * [DisabledBuiltInStickerStore]（SharedPreferences）；iOS 用 NotificationCenter 广播刷新，这里在每次
 * 隐藏/恢复后重读集合更新 [disabledIds]（StateFlow）驱动重组。
 */
@HiltViewModel
class StickerManagementViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stickerRepo: StickerRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    /** 自定义贴纸（createdAt 降序，新导入在前）。 */
    val customStickers: StateFlow<List<CustomStickerEntity>> =
        stickerRepo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // K7（2026-07-12）：构造器不再于主线程做该 prefs 的首读——emptySet 初值 + init 协程 IO 加载。
    // 首读后 SharedPreferences 进程内缓存，下方 mutator 的同步重读均为纯内存操作（保留原样）。
    private val _disabledIds = MutableStateFlow<Set<String>>(emptySet())
    val disabledIds: StateFlow<Set<String>> = _disabledIds.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _disabledIds.value = DisabledBuiltInStickerStore.disabledIds(context)
        }
    }

    /** 「角色发送表情包」总开关（Android 把该设置并入管理页；写 DataStore）。 */
    val stickersEnabled: StateFlow<Boolean> =
        settingsRepo.appSettings.map { it.characterCanSendStickersEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setStickersEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setCharacterCanSendStickersEnabled(enabled)
    }

    fun deleteCustom(sticker: CustomStickerEntity) = viewModelScope.launch {
        stickerRepo.delete(sticker)
    }

    fun hideBuiltIn(id: String) {
        DisabledBuiltInStickerStore.disable(context, id)
        _disabledIds.value = DisabledBuiltInStickerStore.disabledIds(context)
    }

    fun enableBuiltIn(id: String) {
        DisabledBuiltInStickerStore.enable(context, id)
        _disabledIds.value = DisabledBuiltInStickerStore.disabledIds(context)
    }

    fun restoreAllBuiltIn() {
        DisabledBuiltInStickerStore.restoreAll(context)
        _disabledIds.value = DisabledBuiltInStickerStore.disabledIds(context)
    }
}
