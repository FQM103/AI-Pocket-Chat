package com.situ.aichat.ui.promptmodule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.PromptModule
import com.situ.aichat.prompt.PromptModulePosition
import com.situ.aichat.prompt.PromptModulePreset
import com.situ.aichat.prompt.PromptModuleService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Backs the prompt-module editor (iOS `PromptModuleSettingsView`). `characterUuid == null` edits the
 * global modules; non-null edits that character's override (created on first save). Every mutation
 * persists immediately via [SettingsRepository] (iOS saves after each op), so edits flow into
 * [PromptModuleService.effectiveModules] → the chat prompt.
 */
@HiltViewModel
class PromptModuleSettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** null = 全局配置；非空 = 该角色专属配置。 */
    val characterUuid: String? = savedStateHandle["characterUuid"]
    val isCharacterScope: Boolean = characterUuid != null

    private val _modules = MutableStateFlow<List<PromptModule>>(emptyList())
    val modules: StateFlow<List<PromptModule>> = _modules.asStateFlow()

    private val _presets = MutableStateFlow<List<PromptModulePreset>>(emptyList())
    val presets: StateFlow<List<PromptModulePreset>> = _presets.asStateFlow()

    /**
     * 「角色发送表情包」总开关（settings-misc-2）：表情包系统模块行受其 gating——关闭时灰置不可交互，
     * 但保留勾选偏好（1:1 iOS PromptModuleSettingsView.moduleRow isDisabledByParentToggle）。
     */
    val characterCanSendStickersEnabled: StateFlow<Boolean> =
        settingsRepo.appSettings
            .map { it.characterCanSendStickersEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 线下叙事档位 raw（两语境模型 v2·§4-U5）：线下 tab 叙事预设跳转卡的当前值回显（plain/normal/detailed/custom）。 */
    val offlineNarrativeDetailRaw: StateFlow<String> =
        settingsRepo.appSettings
            .map { it.offlineNarrativeDetailRaw }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "plain")

    // Cached source JSONs, refreshed on load and after each write.
    private var globalJson = ""
    private var characterJson = ""
    private var presetsJson = ""

    init {
        viewModelScope.launch {
            val s = settingsRepo.getAppSettings()
            globalJson = s.promptModulesJSON
            characterJson = s.characterPromptModulesJSON
            presetsJson = s.promptModulePresetsJSON
            _modules.value = if (characterUuid != null) {
                PromptModuleService.loadCharacterModules(characterUuid, characterJson)
                    ?: PromptModuleService.loadGlobalModules(globalJson)
            } else {
                PromptModuleService.loadGlobalModules(globalJson)
            }
            _presets.value = PromptModuleService.loadPresets(presetsJson)
        }
    }

    // MARK: - 模块操作（每次写盘）

    fun toggle(id: String) = persist(
        _modules.value.map { if (it.id == id) it.copy(isEnabled = !it.isEnabled) else it },
    )

    fun updateModule(updated: PromptModule) {
        val old = _modules.value.firstOrNull { it.id == updated.id }
        var list = _modules.value.map { if (it.id == updated.id) updated else it }
        if (old != null && old.position != updated.position) list = renumber(list)
        persist(list)
    }

    fun addModule(module: PromptModule) = persist(renumber(_modules.value + module))

    fun deleteModule(id: String) {
        val m = _modules.value.firstOrNull { it.id == id } ?: return
        if (m.isSystemGenerated) return // 系统模块不可删除
        persist(_modules.value.filterNot { it.id == id })
    }

    /** 在同一区（prefix/suffix）内上移/下移（= 调整 sortOrder）。 */
    fun move(id: String, up: Boolean) {
        val m = _modules.value.firstOrNull { it.id == id } ?: return
        val group = _modules.value.filter { it.position == m.position }
            .sortedBy { it.sortOrder }
            .toMutableList()
        val idx = group.indexOfFirst { it.id == id }
        val target = if (up) idx - 1 else idx + 1
        if (idx < 0 || target !in group.indices) return
        val tmp = group[idx]; group[idx] = group[target]; group[target] = tmp
        val orderById = group.mapIndexed { i, mod -> mod.id to i }.toMap()
        persist(_modules.value.map { mod -> orderById[mod.id]?.let { mod.copy(sortOrder = it) } ?: mod })
    }

    /** 新建自定义模块的模板（默认前置区、追加到末尾）。 */
    fun newCustomModuleTemplate(): PromptModule = PromptModule(
        id = UUID.randomUUID().toString(),
        name = "",
        content = "",
        sortOrder = (_modules.value.filter { it.position == PromptModulePosition.PREFIX }
            .maxOfOrNull { it.sortOrder } ?: -1) + 1,
        isEnabled = true,
        isSystemGenerated = false,
        systemModuleType = null,
        position = PromptModulePosition.PREFIX,
    )

    // MARK: - 预设

    fun applyPreset(preset: PromptModulePreset) = persist(renumber(preset.modules))

    fun saveAsPreset(name: String) {
        if (name.isBlank()) return
        val preset = PromptModulePreset(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            modules = _modules.value,
            isBuiltIn = false,
        )
        val all = _presets.value + preset
        _presets.value = all
        viewModelScope.launch {
            presetsJson = PromptModuleService.savePresets(all)
            settingsRepo.setPromptModulePresetsJSON(presetsJson)
        }
    }

    // MARK: - 持久化 + sortOrder 重排

    private fun persist(newModules: List<PromptModule>) {
        _modules.value = newModules
        viewModelScope.launch {
            if (characterUuid != null) {
                characterJson = PromptModuleService.setCharacterModules(characterUuid, newModules, characterJson)
                settingsRepo.setCharacterPromptModulesJSON(characterJson)
            } else {
                globalJson = PromptModuleService.encodeModules(newModules)
                settingsRepo.setPromptModulesJSON(globalJson)
            }
        }
    }

    /** 分别对前置/后置区按现有顺序 0 基重编号（对齐 iOS renumberSortOrders）。 */
    private fun renumber(list: List<PromptModule>): List<PromptModule> {
        val result = list.toMutableList()
        for (position in PromptModulePosition.entries) {
            list.filter { it.position == position }
                .sortedBy { it.sortOrder }
                .forEachIndexed { i, mod ->
                    val idx = result.indexOfFirst { it.id == mod.id }
                    if (idx >= 0) result[idx] = result[idx].copy(sortOrder = i)
                }
        }
        return result
    }
}
