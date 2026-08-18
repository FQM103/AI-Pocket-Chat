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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 「故事」Tab 记忆星空入口卡 ViewModel（图纸 §3.4·J3）：与星空页同三源的**轻聚合计数**
 * （星数 / 星座段数 / 有无新星），不给 366 行的 `CharacterProfileViewModel` 加职责。
 *
 * 入口卡在资料页 item 内自取 `hiltViewModel()` → SavedStateHandle 取的是
 * `characterProfile/{characterUuid}` 路由的 arg（与 [StarfieldViewModel] 同 ARG 名）。
 * 星数 0 也照常渲染（副行走空态文案·E1）。
 */
@HiltViewModel
class StarfieldEntryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    milestoneDao: MilestoneDao,
    promiseRepository: PromiseRepository,
    offlineMeetingMemoryRepository: OfflineMeetingMemoryRepository,
    lastVisitStore: StarfieldLastVisitStore,
) : ViewModel() {

    private val characterUuid: String =
        savedStateHandle.get<String>(StarfieldViewModel.ARG_CHARACTER_UUID).orEmpty()

    val state: StateFlow<EntryCardState> = combine(
        milestoneDao.observeForCharacter(characterUuid),
        flow { emit(offlineMeetingMemoryRepository.byCharacter(characterUuid)) },
        promiseRepository.observeResolvedByCharacter(characterUuid)
            .map { list -> list.filter { it.statusRaw == PromiseStatus.FULFILLED } },
        flow { emit(lastVisitStore.lastVisited(characterUuid)) },
    ) { milestones, meetings, promises, lastVisit ->
        val nodes = StarNodes.build(milestones, meetings, promises, lastVisit)
        EntryCardState(
            starCount = nodes.size,
            clusterCount = StarfieldLayout.clusterCount(nodes),
            hasNova = nodes.any { it.nova },
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntryCardState())
}
