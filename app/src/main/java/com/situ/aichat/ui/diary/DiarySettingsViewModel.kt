package com.situ.aichat.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.work.ReliabilityPromptController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 一个角色 + 是否被勾选可评论。 */
data class DiaryCharacterChoice(val uuid: String, val name: String, val selected: Boolean)

data class DiarySettingsState(
    val autoGenerateEnabled: Boolean = false,
    val autoGenerateTime: String = "21:00",
    /** 自动生成的日记直接发布（R3 评论区活化·默认关）。 */
    val autoPublishEnabled: Boolean = false,
    /** 交换日记固定笔友 uuid（R4·空 = 自动轮换「当天聊得最多」）。 */
    val exchangePartnerUuid: String = "",
    /** 宠物日记每日自动生成（独立于用户日记自动生成，1:1 iOS Auto Generation 段）。 */
    val petAutoGenerateEnabled: Boolean = false,
    val commentEnabled: Boolean = true,
    val commentDelay: Int = 5,
    /** 允许评论的角色（空选中集 = 全部，对齐 iOS）。 */
    val characters: List<DiaryCharacterChoice> = emptyList(),
)

/**
 * 日记设置 VM（M07 7.1.5）。`combine(appSettings, observeAll)` → 状态；setter 写 SettingsRepository
 * （对齐 NotificationSettingsViewModel 模式）。角色多选用 CSV 增删（空 = 全部）。
 */
@HiltViewModel
class DiarySettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val characterRepository: CharacterRepository,
    private val reliabilityPromptController: ReliabilityPromptController,
) : ViewModel() {

    val state: StateFlow<DiarySettingsState> =
        combine(settingsRepo.appSettings, characterRepository.observeAll()) { settings, characters ->
            val selectedSet = parseCsv(settings.diaryInteractingCharacterUUIDs)
            DiarySettingsState(
                autoGenerateEnabled = settings.diaryAutoGenerateEnabled,
                autoGenerateTime = settings.diaryAutoGenerateTime,
                autoPublishEnabled = settings.diaryAutoPublishEnabled,
                exchangePartnerUuid = settings.diaryExchangePartnerUuid,
                petAutoGenerateEnabled = settings.petDiaryAutoGenerateEnabled,
                commentEnabled = settings.diaryCharacterInteractionEnabled,
                commentDelay = settings.diaryCommentDelay,
                characters = characters.map {
                    // 空集 = 全部 → 全部视为选中（与 iOS「不选=全部」语义一致地呈现勾选态）。
                    DiaryCharacterChoice(it.uuid, it.name, selected = selectedSet.isEmpty() || it.uuid in selectedSet)
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiarySettingsState())

    fun setAutoGenerateEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setDiaryAutoGenerateEnabled(enabled)
        // 13.7a：日记自动生成靠后台周期 worker，首次开启时主动引导 HyperOS 白名单（一次性）。
        if (enabled) reliabilityPromptController.onBackgroundFeatureEnabled()
    }

    fun setAutoGenerateTime(time: String) = viewModelScope.launch {
        settingsRepo.setDiaryAutoGenerateTime(time)
    }

    fun setAutoPublishEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setDiaryAutoPublishEnabled(enabled)
    }

    /** 交换日记笔友（R4）：空串 = 自动。 */
    fun setExchangePartner(uuid: String) = viewModelScope.launch {
        settingsRepo.setDiaryExchangePartnerUuid(uuid)
    }

    fun setPetAutoGenerateEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setPetDiaryAutoGenerateEnabled(enabled)
    }

    fun setCommentEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setDiaryCharacterInteractionEnabled(enabled)
    }

    fun setCommentDelay(minutes: Int) = viewModelScope.launch {
        settingsRepo.setDiaryCommentDelay(minutes)
    }

    /**
     * 切换某角色。当前为「全部」(空集) 时首次取消勾选 → 需把其余全部显式写入再去掉该角色（否则空集=全部不变）。
     */
    fun toggleCharacter(uuid: String) = viewModelScope.launch {
        val current = parseCsv(settingsRepo.getAppSettings().diaryInteractingCharacterUUIDs)
        val all = characterRepository.getAll().map { it.uuid }
        val next = when {
            current.isEmpty() -> all.toMutableSet().apply { remove(uuid) } // 全部 → 去掉这一个
            uuid in current -> current.toMutableSet().apply { remove(uuid) }
            else -> current.toMutableSet().apply { add(uuid) }
        }
        // 若结果包含所有角色，归一化回「空集 = 全部」。
        val normalized = if (all.isNotEmpty() && next.containsAll(all)) emptySet() else next
        settingsRepo.setDiaryInteractingCharacterUUIDs(normalized.sorted().joinToString(","))
    }

    private fun parseCsv(csv: String): Set<String> =
        csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
