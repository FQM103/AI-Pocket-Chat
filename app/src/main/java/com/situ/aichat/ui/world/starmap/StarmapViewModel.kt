package com.situ.aichat.ui.world.starmap

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldIds
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject

/**
 * 关系星图私有 VM（W10 图纸 §3.2）：四路 Room **活数据** combine → [StarmapUiState]。写侧（W4 引擎结算 /
 * W6 招募 / 聊天成长）落 Room → Flow 重发 → VM 于 [computeDispatcher] 重算排布 → UI 弹簧过渡——**这就是「实时变化」
 * 的全部机制**（§3.2）。选中态**只存 id**（[StarmapSelection]），卡由 id 从最新数据重 derive（目标消失→收卡·E4）。
 *
 * 供血只走三张表的全局 Flow（无 start/stop 轮询·不碰 cast/presence·9d 供血死角教训）；无写路径（星图纯读面）。
 */
@HiltViewModel
class StarmapViewModel @Inject constructor(
    private val worldSocialDao: WorldSocialDao,
    private val characterRepo: CharacterRepository,
    private val worldNativeDao: WorldNativeDao,
    private val worldDao: WorldDao,
    @ApplicationContext appContext: Context,
) : ViewModel() {

    private val userMadeLabel = appContext.getString(R.string.world_starmap_user_made)

    /** 排布重算派生所用调度器（生产 = Default·测试可换确定性调度器）。 */
    internal var computeDispatcher: CoroutineDispatcher = Dispatchers.Default

    private val _selection = MutableStateFlow<StarmapSelection>(StarmapSelection.None)
    private val _listMode = MutableStateFlow(false)

    // seed/时区：bootstrap 后不变（一次性取·null=世界未建→空图）。
    private var cachedState: WorldStateEntity? = null
    private suspend fun ensureState(): WorldStateEntity? = cachedState ?: worldDao.getState()?.also { cachedState = it }

    private data class Inputs(
        val edges: List<WorldRelationshipEntity>,
        val chars: List<CharacterEntity>,
        val milestones: List<MilestoneEntity>,
        val natives: List<WorldNativeStateEntity>,
    )

    private data class Bundle(val graph: StarmapGraph, val zone: ZoneId)

    // 排布只在**数据变**时重算（stateIn 缓存·选中/列表切换不触发重排·§3.5）。
    @OptIn(ExperimentalCoroutinesApi::class)
    private val graphFlow: StateFlow<Bundle?> =
        combine(
            worldSocialDao.observeEdges(),
            characterRepo.observeAll(),
            characterRepo.observeAllMilestones(),
            worldNativeDao.observeAll(),
        ) { edges, chars, milestones, natives -> Inputs(edges, chars, milestones, natives) }
            .mapLatest { inp ->
                val state = ensureState() ?: return@mapLatest null
                val zone = WorldClock.resolveZone(state.userTimezoneId)
                // 近事批：非休眠边各 pairKey 拉一次流水 → 按 now/zone 折成相对日档（关系卡 + 列表模式共用·§3.2）。
                val now = System.currentTimeMillis()
                val recentByPair = inp.edges.asSequence().filter { !it.dormant }
                    .map { WorldIds.pairKey(it.fromId, it.toId) }.distinct().toList()
                    .associateWith { pk -> StarmapDerive.starRecentOf(worldSocialDao.eventsForPair(pk), now, zone) }
                val graph = withContext(computeDispatcher) {
                    StarmapDerive.buildGraph(inp.edges, inp.chars, inp.milestones, inp.natives, state.seed, userMadeLabel, recentByPair)
                }
                Bundle(graph, zone)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<StarmapUiState> =
        combine(graphFlow, _selection, _listMode) { bundle, sel, list -> Triple(bundle, sel, list) }
            .mapLatest { (bundle, sel, list) ->
                if (bundle == null) return@mapLatest StarmapUiState(ready = false, listMode = list)
                val norm = StarmapDerive.normalizeSelection(sel, bundle.graph)
                StarmapUiState(
                    ready = true,
                    graph = bundle.graph,
                    listMode = list,
                    selection = norm,
                    selectionCard = buildCard(norm, bundle),
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StarmapUiState())

    /** 选中卡：由 id 从最新 graph 重 derive；边卡近事按需从 DAO 拉（每次上游重发重拉·§3.2）。 */
    private suspend fun buildCard(sel: StarmapSelection, bundle: Bundle): StarmapCard? {
        val graph = bundle.graph
        return when (sel) {
            StarmapSelection.None -> null
            StarmapSelection.You -> StarmapCard.You(graph.nodes.size, graph.pendings.size)
            is StarmapSelection.Node ->
                graph.nodes.firstOrNull { it.characterUuid == sel.characterUuid }?.let { StarmapDerive.nodeCard(it, graph) }
            is StarmapSelection.Edge ->
                graph.edges.firstOrNull { it.pairKey == sel.pairKey }?.let { StarmapCard.Edge(it) } // 近事已在 edge.recent
            is StarmapSelection.Pending ->
                graph.pendings.firstOrNull { it.nativeId == sel.nativeId }?.let { StarmapCard.Pending(it) }
        }
    }

    fun select(selection: StarmapSelection) { _selection.value = selection }

    fun clearSelection() { _selection.value = StarmapSelection.None }

    /** 图 ↔ 列表切换（开列表自动收卡·§4.6）。 */
    fun toggleListMode() {
        val on = !_listMode.value
        _listMode.value = on
        if (on) _selection.value = StarmapSelection.None
    }
}
