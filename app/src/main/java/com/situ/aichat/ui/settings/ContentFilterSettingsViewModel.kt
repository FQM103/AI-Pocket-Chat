package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.content.ContentFilterRule
import com.situ.aichat.content.ContentFilterService
import com.situ.aichat.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 内容过滤设置（14.3c）：预设规则开关 + 自定义正则规则增删改。规则列表整体以 JSON 存进
 * `AppSettings.contentFilterRulesJSON`（经 [SettingsRepository]），被 ChatViewModel/BusyReplyService 经
 * [ContentFilterService.applyFilters] 消费。
 *
 * **1:1 iOS ContentFilterSettingsView**：iOS `@State rules` onAppear 一次性 [ContentFilterService.loadRules]、
 * 任意变更即 saveRules。本 VM 同构：init 一次性加载本地态、每次变更写回 + 持久化（本屏是规则唯一编辑者，
 * 故用本地态而非持续观察 Flow，避免编辑中被回灌覆盖）。**安卓显式落库 iOS 的「空 JSON → 写回默认预设」副作用**
 * （loadRules 保持纯）。
 */
@HiltViewModel
class ContentFilterSettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _rules = MutableStateFlow<List<ContentFilterRule>>(emptyList())
    val rules: StateFlow<List<ContentFilterRule>> = _rules.asStateFlow()

    init {
        viewModelScope.launch {
            val settings = settingsRepo.getAppSettings()
            val loaded = ContentFilterService.loadRules(settings.contentFilterRulesJSON)
            _rules.value = loaded
            // 首次使用（JSON 空）：把默认预设落库，使后续编辑稳定持久（对齐 iOS loadRules 空→saveRules 写回）。
            if (settings.contentFilterRulesJSON.isEmpty()) {
                settingsRepo.setContentFilterRulesJSON(ContentFilterService.encodeRules(loaded))
            }
        }
    }

    /** 切换某规则（预设或自定义）启用状态。 */
    fun setRuleEnabled(id: String, enabled: Boolean) = mutate { list ->
        list.map { if (it.id == id) it.copy(isEnabled = enabled) else it }
    }

    /** 删除自定义规则（预设不可删，UI 不暴露）。 */
    fun deleteCustomRule(id: String) = mutate { list -> list.filterNot { it.id == id } }

    /** 新增或更新自定义规则（按 id 命中则替换、否则追加到末尾，1:1 iOS append / firstIndex 替换）。 */
    fun upsertCustomRule(rule: ContentFilterRule) = mutate { list ->
        if (list.any { it.id == rule.id }) list.map { if (it.id == rule.id) rule else it } else list + rule
    }

    private fun mutate(transform: (List<ContentFilterRule>) -> List<ContentFilterRule>) {
        val next = transform(_rules.value)
        _rules.value = next
        viewModelScope.launch {
            settingsRepo.setContentFilterRulesJSON(ContentFilterService.encodeRules(next))
        }
    }
}
