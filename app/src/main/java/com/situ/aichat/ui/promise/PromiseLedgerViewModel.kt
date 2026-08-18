package com.situ.aichat.ui.promise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.repository.PromiseRepository
import com.situ.aichat.promise.PromiseInjectionRenderer
import com.situ.aichat.promise.PromiseLedgerService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「我们的约定」账本子页 VM（记忆改造三期·图纸 §3.4）。进行中排序取 [PromiseInjectionRenderer.sortedOpen]
 * 单源（与注入 / 资料页卡同序·D-7）；已了结取全部历史（子页价值在翻旧账·注入仍只带 7 天窗，两者用途不同·D-3）。
 * 手动兜底只经 [PromiseLedgerService.resolveManually]（对账第四道闸）——UI 绝不直碰 DAO。
 */
@HiltViewModel
class PromiseLedgerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    promiseRepository: PromiseRepository,
    private val ledgerService: PromiseLedgerService,
) : ViewModel() {

    val characterUuid: String = savedStateHandle.get<String>(ARG_CHARACTER_UUID).orEmpty()

    val openPromises: StateFlow<List<PromiseEntity>> =
        promiseRepository.observeOpenByCharacter(characterUuid)
            .map(PromiseInjectionRenderer::sortedOpen)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val resolvedPromises: StateFlow<List<PromiseEntity>> =
        promiseRepository.observeResolvedByCharacter(characterUuid)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 选中 uuid 存 SavedStateHandle（进程死亡后 sheet 随重建恢复）；detail 从两列表 Flow 派生 =
     * 背景对账改状态时 sheet 内容实时跟变（E1）。
     */
    val selectedUuid: StateFlow<String> = savedStateHandle.getStateFlow(KEY_SELECTED, "")

    val detail: StateFlow<PromiseEntity?> =
        combine(openPromises, resolvedPromises, selectedUuid) { open, resolved, id ->
            if (id.isEmpty()) null else (open + resolved).firstOrNull { it.uuid == id }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(uuid: String) { savedStateHandle[KEY_SELECTED] = uuid }

    fun dismissDetail() { savedStateHandle[KEY_SELECTED] = "" }

    /** 手动兜底（确认框确认后调用）：先收 sheet，写库交给 Flow 刷新列表（行移入已了结节）。 */
    fun markResolved(uuid: String, statusRaw: String) {
        savedStateHandle[KEY_SELECTED] = ""
        viewModelScope.launch { ledgerService.resolveManually(uuid, statusRaw, System.currentTimeMillis()) }
    }

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
        private const val KEY_SELECTED = "selectedPromiseUuid"
    }
}
