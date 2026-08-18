package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.tts.SystemVoiceOption
import com.situ.aichat.tts.TtsConfiguration
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsPreviewer
import com.situ.aichat.tts.TtsProviderType
import com.situ.aichat.tts.TtsResponseFormat
import com.situ.aichat.tts.TtsService
import com.situ.aichat.tts.pricing.TtsCostEstimate
import com.situ.aichat.tts.provider.TtsRemoteConfigValues
import com.situ.aichat.tts.provider.TtsRemoteModelOption
import com.situ.aichat.tts.provider.TtsRemoteVoiceOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TtsConfigViewModel @Inject constructor(
    private val repo: TtsConfigurationRepository,
    private val ttsService: TtsService,
    private val previewer: TtsPreviewer,
) : ViewModel() {

    /** The persisted config (null until first load; the screen seeds the form on the first non-null). */
    val configuration: StateFlow<TtsConfiguration?> =
        repo.configuration.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // 初值 false + init 协程回填（repo.hasApiKey 为 suspend·IO）：构造器不再于主线程做加密库首读（K3）。
    private val _hasApiKey = MutableStateFlow(false)
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    init {
        viewModelScope.launch { _hasApiKey.value = repo.hasApiKey() }
    }

    // MARK: - Live model / voice catalogs

    private val _models = MutableStateFlow<List<TtsRemoteModelOption>>(emptyList())
    val models: StateFlow<List<TtsRemoteModelOption>> = _models.asStateFlow()
    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()
    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

    private val _voices = MutableStateFlow<List<TtsRemoteVoiceOption>>(emptyList())
    val voices: StateFlow<List<TtsRemoteVoiceOption>> = _voices.asStateFlow()
    private val _voicesLoading = MutableStateFlow(false)
    val voicesLoading: StateFlow<Boolean> = _voicesLoading.asStateFlow()
    private val _voicesError = MutableStateFlow<String?>(null)
    val voicesError: StateFlow<String?> = _voicesError.asStateFlow()

    // MARK: - Usage / cost (MiniMax only)

    private val _cost = MutableStateFlow<TtsCostEstimate?>(null)
    val cost: StateFlow<TtsCostEstimate?> = _cost.asStateFlow()

    // MARK: - System voices + preview (P10.1c)

    private val _systemVoices = MutableStateFlow<List<SystemVoiceOption>>(emptyList())
    val systemVoices: StateFlow<List<SystemVoiceOption>> = _systemVoices.asStateFlow()
    private val _systemVoicesLoading = MutableStateFlow(false)
    val systemVoicesLoading: StateFlow<Boolean> = _systemVoicesLoading.asStateFlow()

    private val _previewBusy = MutableStateFlow(false)
    val previewBusy: StateFlow<Boolean> = _previewBusy.asStateFlow()
    private val _previewError = MutableStateFlow<String?>(null)
    val previewError: StateFlow<String?> = _previewError.asStateFlow()

    /** Enumerate installed system voices for the picker (system provider only). */
    fun loadSystemVoices() {
        if (_systemVoices.value.isNotEmpty() || _systemVoicesLoading.value) return
        viewModelScope.launch {
            _systemVoicesLoading.value = true
            _systemVoices.value = previewer.systemVoices()
            _systemVoicesLoading.value = false
        }
    }

    /** 试听：合成一句样本并播放（用当前表单值，未保存也可）。 */
    fun preview(
        provider: TtsProviderType,
        providerName: String,
        baseUrl: String,
        modelName: String,
        remoteVoice: String,
        systemVoice: String,
        responseFormat: TtsResponseFormat,
        apiKeyInput: String,
    ) {
        viewModelScope.launch {
            _previewBusy.value = true
            _previewError.value = null
            try {
                val remoteConfig = if (provider == TtsProviderType.SYSTEM) {
                    null
                } else {
                    TtsRemoteConfigValues(
                        providerType = provider,
                        providerName = providerName,
                        apiKey = apiKeyInput.trim().ifEmpty { repo.getApiKey() },
                        baseUrl = baseUrl.trim().ifEmpty { provider.defaultBaseUrl },
                        modelName = modelName.trim(),
                        responseFormat = responseFormat,
                    )
                }
                _previewError.value = previewer.preview(provider, systemVoice, remoteVoice, remoteConfig)
            } finally {
                _previewBusy.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        previewer.stop()
    }

    /** Fetch models for the (unsaved) form values. Uses the typed key, falling back to the saved key. */
    fun fetchModels(provider: TtsProviderType, providerName: String, baseUrl: String, apiKeyInput: String) {
        viewModelScope.launch {
            _modelsLoading.value = true
            _modelsError.value = null
            try {
                _models.value = ttsService.fetchRemoteModels(
                    buildConfig(provider, providerName, baseUrl, modelName = "", apiKeyInput = apiKeyInput),
                )
            } catch (e: Exception) {
                _models.value = emptyList()
                _modelsError.value = e.message ?: "拉取失败"
            } finally {
                _modelsLoading.value = false
            }
        }
    }

    /** Fetch voices for the (unsaved) form values (model name included — some providers filter by it). */
    fun fetchVoices(provider: TtsProviderType, providerName: String, baseUrl: String, modelName: String, apiKeyInput: String) {
        viewModelScope.launch {
            _voicesLoading.value = true
            _voicesError.value = null
            try {
                _voices.value = ttsService.fetchRemoteVoices(
                    buildConfig(provider, providerName, baseUrl, modelName, apiKeyInput),
                )
            } catch (e: Exception) {
                _voices.value = emptyList()
                _voicesError.value = e.message ?: "拉取失败"
            } finally {
                _voicesLoading.value = false
            }
        }
    }

    /** Clear fetched catalogs (e.g. when the provider changes). */
    fun clearCatalogs() {
        _models.value = emptyList(); _modelsError.value = null
        clearVoices()
    }

    /** Clear only the voice catalog (e.g. when the model changes and the voice list is per-model). */
    fun clearVoices() {
        _voices.value = emptyList(); _voicesError.value = null
    }

    /** Refresh the MiniMax usage/cost snapshot for the given model. */
    fun refreshUsage(modelName: String) {
        viewModelScope.launch {
            _cost.value = withContext(Dispatchers.IO) { ttsService.miniMaxUsageEstimate(modelName) }
        }
    }

    fun save(
        provider: TtsProviderType,
        providerName: String,
        baseUrl: String,
        modelName: String,
        remoteVoice: String,
        systemVoice: String,
        responseFormat: TtsResponseFormat,
        apiKeyInput: String,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            repo.update(
                TtsConfiguration(
                    providerType = provider,
                    providerName = providerName.trim().ifEmpty { provider.displayName },
                    baseURL = baseUrl.trim(),
                    modelName = modelName.trim(),
                    defaultSystemVoiceIdentifier = systemVoice.trim(),
                    defaultRemoteVoiceID = remoteVoice.trim(),
                    responseFormat = responseFormat,
                ),
            )
            if (apiKeyInput.isNotBlank()) repo.setApiKey(apiKeyInput.trim())
            _hasApiKey.value = repo.hasApiKey()
            onSaved()
        }
    }

    private suspend fun buildConfig(
        provider: TtsProviderType,
        providerName: String,
        baseUrl: String,
        modelName: String,
        apiKeyInput: String,
    ): TtsRemoteConfigValues {
        val effectiveKey = apiKeyInput.trim().ifEmpty { repo.getApiKey() }
        val effectiveBaseUrl = baseUrl.trim().ifEmpty { provider.defaultBaseUrl }
        return TtsRemoteConfigValues(
            providerType = provider,
            providerName = providerName,
            apiKey = effectiveKey,
            baseUrl = effectiveBaseUrl,
            modelName = modelName.trim(),
            responseFormat = TtsResponseFormat.MP3,
        )
    }
}
