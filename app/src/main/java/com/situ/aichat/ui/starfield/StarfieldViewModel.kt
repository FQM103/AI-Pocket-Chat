package com.situ.aichat.ui.starfield

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.PromiseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 记忆星空页 ViewModel（图纸 2026-07-16-记忆星空 §3.1/§3.3/§3.5）：三源**只读**投影 → [StarNode] →
 * [StarfieldLayout] → [StarfieldUiState]；nova / 流星判定；退出记访问时刻。
 *
 * 三数据源全程零写入——本卷唯一的写 = [StarfieldLastVisitStore] 的 lastVisit key（图纸 §2.3）。
 * 见面走**实体表快照**（进入时一次·停留期间新见面下次进入才上屏·图纸 J6/§3.5 设计说明）。
 */
@HiltViewModel
class StarfieldViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    milestoneDao: MilestoneDao,
    promiseRepository: PromiseRepository,
    offlineMeetingMemoryRepository: OfflineMeetingMemoryRepository,
    private val lastVisitStore: StarfieldLastVisitStore,
) : ViewModel() {

    val characterUuid: String = savedStateHandle.get<String>(ARG_CHARACTER_UUID).orEmpty()

    /** 视口尺寸（dp）——由 Screen 测得后回灌；未知时 [StarfieldUiState.loading] 恒 true（§4.10 加载态）。 */
    private val viewport = MutableStateFlow(ViewportDp.EMPTY)

    /** 选中态**只存 id**，渲染时按 id 从当前流解析（查无则自然收起·PITFALLS 1b）。 */
    private val selectedId = MutableStateFlow<String?>(null)

    /** 流星单发闩：播完置起，本次停留不再播（§4.5）。 */
    private val meteorPlayed = MutableStateFlow(false)

    /** 进入时读一次；退出写 now → 下次进入才有新的 nova（图纸 J4）。 */
    private val lastVisitFlow: Flow<Long> = flow { emit(lastVisitStore.lastVisited(characterUuid)) }

    /** 见面 = suspend 快照转 flow（图纸 §3.1）。 */
    private val meetingsFlow = flow { emit(offlineMeetingMemoryRepository.byCharacter(characterUuid)) }

    /** 约定：已了结流再过滤 FULFILLED——**CANCELLED 绝不成星**（图纸 §3.1 锁定·E5）。 */
    private val promisesFlow = promiseRepository.observeResolvedByCharacter(characterUuid)
        .map { list -> list.filter { it.statusRaw == PromiseStatus.FULFILLED } }

    private val nodesFlow: Flow<List<StarNode>> = combine(
        milestoneDao.observeForCharacter(characterUuid),
        meetingsFlow,
        promisesFlow,
        lastVisitFlow,
    ) { milestones, meetings, promises, lastVisit ->
        StarNodes.build(milestones, meetings, promises, lastVisit)
    }

    val state: StateFlow<StarfieldUiState> =
        combine(nodesFlow, viewport, selectedId, meteorPlayed) { nodes, vp, selectedId, meteorPlayed ->
            if (vp.isEmpty) {
                // 视口未测出：只画夜幕+尘星，无星无标注（§4.10·夜空本身即加载态）。
                StarfieldUiState(loading = true)
            } else {
                val layout = StarfieldLayout.layout(
                    nodes = nodes,
                    characterUuid = characterUuid,
                    viewportWidthDp = vp.widthDp,
                    viewportHeightDp = vp.heightDp,
                    nowMillis = System.currentTimeMillis(),
                )
                StarfieldUiState(
                    clusters = layout.clusters,
                    canvasHeightDp = layout.canvasHeightDp,
                    starCount = nodes.size,
                    // 流星 = 本次进入存在「新的里程碑」（图纸 J4）。
                    showMeteor = !meteorPlayed && nodes.any { it.nova && it.type == StarType.MILESTONE },
                    selected = nodes.firstOrNull { it.id == selectedId },
                    loading = false,
                )
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StarfieldUiState())

    /** Screen 测得视口后回灌（布局是视口相对的·图纸 §3.2）。 */
    fun onViewportChanged(widthDp: Float, heightDp: Float) {
        viewport.value = ViewportDp(widthDp, heightDp)
    }

    fun onStarSelected(star: StarNode?) {
        selectedId.value = star?.id
    }

    /** 流星播完（§4.5·Canvas 回调）。 */
    fun onMeteorPlayed() {
        meteorPlayed.value = true
    }

    /**
     * 退出星空页记「已看到 now」（Screen 的 `DisposableEffect onDispose` 调用·图纸 §3.3）。
     * [NonCancellable]（照 `CharacterProfileViewModel.saveSalary` 纹路）：onDispose 与 VM 清理同期发生，
     * 裸 launch 会被 viewModelScope 的取消掐掉这笔写 → nova 永远清不掉。
     */
    fun markVisited() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                lastVisitStore.markVisited(characterUuid, System.currentTimeMillis())
            }
        }
    }

    private data class ViewportDp(val widthDp: Float, val heightDp: Float) {
        val isEmpty: Boolean get() = widthDp <= 0f || heightDp <= 0f

        companion object {
            val EMPTY = ViewportDp(0f, 0f)
        }
    }

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
    }
}
