package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.AudioInputMode
import com.situ.aichat.data.model.MaxOutputLength
import com.situ.aichat.data.model.ThinkingBudgetLevel
import com.situ.aichat.data.model.ThinkingModelMode
import com.situ.aichat.data.model.ToolCallingMode
import com.situ.aichat.data.model.VisionMode
import com.situ.aichat.data.remote.llm.ApiBalanceResult
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.modelcatalog.ModelCatalogService
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.ConfigSaveResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 保存反馈（settings-api-5）：成功(新建)清空输入 + 提示；失败弹错误提示且不离屏/不丢 key。 */
sealed interface ApiSaveFeedback {
    data object SavedCreate : ApiSaveFeedback
    data object KeychainFailed : ApiSaveFeedback
    data object DbFailed : ApiSaveFeedback
}

@HiltViewModel
class ApiConfigViewModel @Inject constructor(
    private val repo: ApiConfigRepository,
    private val modelCatalog: ModelCatalogService,
    private val functionRouter: ApiFunctionRouter,
) : ViewModel() {

    val configs: StateFlow<List<ApiConfigEntity>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeConfig: StateFlow<ApiConfigEntity?> =
        repo.observeActive().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 功能→配置 显式分配映射，配置卡「用于：…」承接提示用（settings-api-3）。 */
    val assignments: StateFlow<Map<ApiFunction, String>> =
        functionRouter.assignments.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** UUIDs whose capability detection is currently running (drives the per-card spinner). */
    private val _detecting = MutableStateFlow<Set<String>>(emptySet())
    val detecting: StateFlow<Set<String>> = _detecting.asStateFlow()

    /** UUIDs whose最近一次检测返回「不确定(-1)」（settings-api-6，列表卡显示原因提示）。 */
    private val _undetermined = MutableStateFlow<Set<String>>(emptySet())
    val undetermined: StateFlow<Set<String>> = _undetermined.asStateFlow()

    /** 保存反馈事件（settings-api-5），编辑/新建屏各自观察并弹 snackbar。 */
    private val _feedback = MutableSharedFlow<ApiSaveFeedback>(extraBufferCapacity = 1)
    val feedback = _feedback.asSharedFlow()

    // MARK: - Model catalog (3.3b)

    private val _availableModels = MutableStateFlow<List<APIModelOption>>(emptyList())
    val availableModels: StateFlow<List<APIModelOption>> = _availableModels.asStateFlow()

    private val _modelsLoading = MutableStateFlow(false)
    val modelsLoading: StateFlow<Boolean> = _modelsLoading.asStateFlow()

    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

    /** Fetch the available model list for the (unsaved) form values; updates [availableModels]. */
    fun fetchModels(provider: ApiProviderType, baseUrl: String, apiKey: String) {
        viewModelScope.launch {
            _modelsLoading.value = true
            _modelsError.value = null
            try {
                val values = ApiConfigValues(
                    providerType = provider,
                    apiKey = apiKey.trim(),
                    baseUrl = baseUrl.trim(),
                    modelName = "",
                    thinkingBudgetLevel = ThinkingBudgetLevel.AUTO,
                    isThinkingModel = false,
                    maxOutputLength = MaxOutputLength.AUTO,
                )
                _availableModels.value = modelCatalog.fetchModels(values)
            } catch (e: Exception) {
                _availableModels.value = emptyList()
                _modelsError.value = e.message ?: "拉取失败"
            } finally {
                _modelsLoading.value = false
            }
        }
    }

    /** Clear the fetched model list (e.g. when the provider changes). */
    fun clearModels() {
        _availableModels.value = emptyList()
        _modelsError.value = null
    }

    // MARK: - Account balance (3.3d)

    private val _balances = MutableStateFlow<Map<String, ApiBalanceResult>>(emptyMap())
    val balances: StateFlow<Map<String, ApiBalanceResult>> = _balances.asStateFlow()

    /** Refresh balances for all saved configs in parallel (skips configs without a stored key). */
    fun refreshBalances() {
        viewModelScope.launch {
            configs.value.forEach { cfg ->
                launch {
                    val result = repo.fetchBalance(cfg) ?: return@launch
                    _balances.update { it + (cfg.uuid to result) }
                }
            }
        }
    }

    /** Refresh the balance for a single config. */
    fun refreshBalance(uuid: String) {
        viewModelScope.launch {
            val cfg = configs.value.firstOrNull { it.uuid == uuid } ?: return@launch
            val result = repo.fetchBalance(cfg) ?: return@launch
            _balances.update { it + (uuid to result) }
        }
    }

    fun save(provider: ApiProviderType, baseUrl: String, model: String, apiKey: String) {
        viewModelScope.launch {
            val outcome = repo.addConfig(
                providerType = provider,
                baseUrl = baseUrl.trim(),
                modelName = model.trim(),
                apiKey = apiKey.trim(),
                makeActive = true,
            )
            when (outcome.result) {
                ConfigSaveResult.SUCCESS -> {
                    _feedback.emit(ApiSaveFeedback.SavedCreate)
                    val newUuid = outcome.uuid ?: return@launch
                    runDetection(newUuid) { repo.runCapabilityDetections(newUuid) }
                }
                ConfigSaveResult.KEYCHAIN_FAILED -> _feedback.emit(ApiSaveFeedback.KeychainFailed)
                ConfigSaveResult.DB_FAILED -> _feedback.emit(ApiSaveFeedback.DbFailed)
            }
        }
    }

    fun redetect(uuid: String) {
        viewModelScope.launch {
            runDetection(uuid) { repo.redetectCapabilities(uuid) }
        }
    }

    /**
     * Mark a uuid as detecting, run [block], then clear the flag (errors are swallowed; -1 results persist).
     * settings-api-6：block 返回 anyUndetermined → 更新 [undetermined] 集合驱动列表卡的「检测无法判定」提示。
     */
    private suspend fun runDetection(uuid: String, block: suspend () -> Boolean) {
        _detecting.update { it + uuid }
        try {
            val anyUndetermined = block()
            _undetermined.update { if (anyUndetermined) it + uuid else it - uuid }
        } finally {
            _detecting.update { it - uuid }
        }
    }

    /** Save edits to an existing config; re-runs detection in the background if inputs changed. */
    fun updateConfig(
        uuid: String,
        provider: ApiProviderType,
        baseUrl: String,
        model: String,
        newApiKey: String?,
        thinkingModelMode: ThinkingModelMode,
        toolCallingMode: ToolCallingMode,
        visionMode: VisionMode,
        audioInputMode: AudioInputMode,
        thinkingBudgetLevel: ThinkingBudgetLevel,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            val outcome = repo.updateConfig(
                uuid = uuid,
                providerType = provider,
                baseUrl = baseUrl,
                modelName = model,
                newApiKey = newApiKey,
                thinkingModelMode = thinkingModelMode,
                toolCallingMode = toolCallingMode,
                visionMode = visionMode,
                audioInputMode = audioInputMode,
                thinkingBudgetLevel = thinkingBudgetLevel,
            )
            when (outcome.result) {
                ConfigSaveResult.SUCCESS -> {
                    // 成功才离屏（保留安卓地道的「保存即返回」，无 UX 倒退）；失败留在屏上弹错误。
                    onSaved()
                    if (outcome.needsDetect) runDetection(uuid) { repo.runCapabilityDetections(uuid) }
                }
                ConfigSaveResult.KEYCHAIN_FAILED -> _feedback.emit(ApiSaveFeedback.KeychainFailed)
                ConfigSaveResult.DB_FAILED -> _feedback.emit(ApiSaveFeedback.DbFailed)
            }
        }
    }

    /** 复制配置（settings-api-4）：全字段拷贝 + 新 uuid/apiKeyId + 同 key 值，inactive，不重新检测（对齐 iOS cloneConfiguration）。 */
    fun clone(uuid: String) {
        viewModelScope.launch { repo.cloneConfig(uuid) }
    }

    // MARK: - 扫码导出（13.10b · C7）

    /** 「生成二维码」的负载字符串（非空 = 弹二维码弹窗；含明文密钥）。 */
    private val _exportPayload = MutableStateFlow<String?>(null)
    val exportPayload: StateFlow<String?> = _exportPayload.asStateFlow()

    /** 生成某配置的二维码导出负载（读取明文密钥拼装）；配置不存在则不弹窗。 */
    fun exportQr(uuid: String) {
        viewModelScope.launch { _exportPayload.value = repo.exportConfigPayload(uuid) }
    }

    /** 关闭二维码弹窗。 */
    fun dismissExportQr() {
        _exportPayload.value = null
    }

    fun activate(uuid: String) {
        viewModelScope.launch { repo.setActive(uuid) }
    }

    fun delete(entity: ApiConfigEntity) {
        viewModelScope.launch { repo.delete(entity) }
    }
}
