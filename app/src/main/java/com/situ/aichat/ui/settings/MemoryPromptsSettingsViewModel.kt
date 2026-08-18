package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.PromptBuilder
import com.situ.aichat.prompt.memory.MemoryService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 记忆提示词自定义（14.5b·1:1 iOS `MemoryPromptSettingsView`）：提取提示词 + 注入提示词双编辑器。
 *
 * 字段 `AppSettings.memoryExtractionPrompt`/`memoryInjectionPrompt`（空=用默认模板）早被 ChatViewModel/
 * VoiceCallPostReplyRounds（提取）与 PromptBuilder（注入）消费；本屏只补编辑 UI + 持久化。
 *
 * **本地态而非持续观察 Flow**（同 [ContentFilterSettingsViewModel]）：本屏是唯一编辑者，init 经 [SettingsRepository.getAppSettings]
 * 一次性快照播种（空→展示默认模板），之后每次变更写回 + 持久化，避免编辑中被 DataStore 回灌覆盖。
 * 模板内容保持中文常量（与解析格式紧耦合·同 iOS），UI 文案才本地化。
 */
@HiltViewModel
class MemoryPromptsSettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _extraction = MutableStateFlow(MemoryService.DEFAULT_EXTRACTION_PROMPT)
    val extraction: StateFlow<String> = _extraction.asStateFlow()

    private val _injection = MutableStateFlow(PromptBuilder.DEFAULT_INJECTION_PROMPT)
    val injection: StateFlow<String> = _injection.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settingsRepo.getAppSettings()
            _extraction.value = s.memoryExtractionPrompt.ifEmpty { MemoryService.DEFAULT_EXTRACTION_PROMPT }
            _injection.value = s.memoryInjectionPrompt.ifEmpty { PromptBuilder.DEFAULT_INJECTION_PROMPT }
        }
    }

    fun onExtractionChange(text: String) {
        _extraction.value = text
        viewModelScope.launch {
            settingsRepo.setMemoryExtractionPrompt(normalizeCustomPrompt(text, MemoryService.DEFAULT_EXTRACTION_PROMPT))
        }
    }

    fun resetExtraction() = onExtractionChange(MemoryService.DEFAULT_EXTRACTION_PROMPT)

    fun onInjectionChange(text: String) {
        _injection.value = text
        viewModelScope.launch {
            settingsRepo.setMemoryInjectionPrompt(normalizeCustomPrompt(text, PromptBuilder.DEFAULT_INJECTION_PROMPT))
        }
    }

    fun resetInjection() = onInjectionChange(PromptBuilder.DEFAULT_INJECTION_PROMPT)
}

/**
 * 「等于默认即存空」纯函数（单测反推 iOS onChange：`if newValue == default { settings.x = "" } else { settings.x = newValue }`）。
 * 存空让消费方回落默认模板，且保证「未自定义」状态可被备份/迁移正确识别。
 */
internal fun normalizeCustomPrompt(text: String, default: String): String =
    if (text == default) "" else text
