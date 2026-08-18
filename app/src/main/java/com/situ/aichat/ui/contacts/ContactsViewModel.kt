package com.situ.aichat.ui.contacts

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.repository.CharacterDeletionService
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.prompt.growth.composeRelationshipDisplay
import com.situ.aichat.share.ShareTargetCoordinator
import com.situ.aichat.ui.chat.orderCharactersForPicker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Contacts tab (iOS `ContactListView`): the full character list, searchable by name +
 * personality, with open-chat / edit / delete. iOS has NO per-character pin or archive (archive
 * lives on conversations), so neither is modelled here.
 */
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val characterRepo: CharacterRepository,
    private val conversationRepo: ConversationRepository,
    private val meetingMemoryRepo: OfflineMeetingMemoryRepository,
    private val deletionService: CharacterDeletionService,
    private val shareCoordinator: ShareTargetCoordinator,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * 防抖后的搜索词（空词立即生效、非空词等 300ms；逐字照抄 [com.situ.aichat.ui.chat.ChatListViewModel] searchTerm 范式）。
     * 空态/无结果态 UI 分支仍用 raw [query] 判定，仅列表过滤时序上加防抖。
     */
    @OptIn(FlowPreview::class)
    private val searchTerm: StateFlow<String> =
        _query
            .debounce { if (it.isEmpty()) 0L else 300L }
            .map { it.trim() }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * 分享给角色（Direct Share · C3，13.10a）通用分享待选收件角色的文本（非空 = 进「点选收件角色」模式，显示选择条、
     * 点行即发而非进会话）。命中具体角色的分享不经此路径。
     */
    val shareText: StateFlow<String?> = shareCoordinator.pendingPickerText

    /**
     * 联系人行视图模型：角色 + 关系称谓串（最新里程碑「关系名 · 时期」，复用 [composeRelationshipDisplay]；
     * 无里程碑 → null，UI 显「初识」）。火花 / 职业由 UI 直接读 [character]。
     */
    data class Row(
        val character: CharacterEntity,
        val relationshipDisplay: String?,
        /** 14 天窗内最近纪事（关系里程碑/见面·图纸一 #5）；无 → null，UI 回落职业/神秘占位。 */
        val recentEvent: RecentEvent?,
    )

    /** 纪事窗粗筛下界（VM init 算一次·J1 粗筛；精筛以 [pickRecentEvent] 当刻 now 为准）。 */
    private val recentEventSince: Long = System.currentTimeMillis() - RECENT_EVENT_WINDOW_MILLIS

    /**
     * 联系人列表行：按搜索词（防抖）过滤 + 并入每角色最新关系称谓（iOS filteredCharacters 超集）。
     * 排序（#6③「最近互动优先」）：有活跃会话的角色按最近消息倒序在前、无会话者保持 observeAll 的 newest-first
     * 殿后——复用聊天选择器纯函数 [orderCharactersForPicker]（跨包 import，不复制不搬迁）。
     */
    val rows: StateFlow<List<Row>> =
        combine(
            characterRepo.observeAll(),
            characterRepo.observeAllMilestones(),
            conversationRepo.observeActive(),
            meetingMemoryRepo.observeMeetingsSince(recentEventSince),
            searchTerm,
        ) { list, milestones, convs, meetings, term ->
            val latestMilestoneByCharacter: Map<String, MilestoneEntity> =
                milestones.groupBy { it.characterUuid }
                    .mapValues { (_, ms) -> ms.maxByOrNull { it.establishedDate }!! }
            val latestMeetingByCharacter: Map<String, OfflineMeetingMemoryEntity> =
                meetings.filter { it.kindRaw == "meeting" }
                    .groupBy { it.characterUuid }
                    .mapValues { (_, ms) -> ms.maxByOrNull { it.startedAtMillis }!! }
            // 有过消息的会话的最近消息时刻（照 ChatListViewModel:104-107 逐字）：驱动「最近互动优先」排序。
            val lastByCharacter: Map<String, Long> = convs
                .filter { it.lastMessageDate != null }
                .associate { it.characterUuid to it.lastMessageDate!! }
            val filtered = if (term.isEmpty()) {
                list
            } else {
                list.filter {
                    it.name.contains(term, ignoreCase = true) ||
                        it.personalityDescription.contains(term, ignoreCase = true)
                }
            }
            orderCharactersForPicker(filtered) { lastByCharacter[it.uuid] }.map { c ->
                val latest = latestMilestoneByCharacter[c.uuid]
                Row(
                    character = c,
                    relationshipDisplay = latest?.let { composeRelationshipDisplay(it.relationshipName, it.phase) },
                    // J1 精筛：当刻 now 在纯函数内判窗（粗筛已少搬数据），口径以精筛为准。
                    recentEvent = pickRecentEvent(latest, latestMeetingByCharacter[c.uuid], System.currentTimeMillis()),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 有「简版」见面摘要兜底的角色 uuid 集（联系人头像红点·1:1 iOS hasFallbackSummaries），实时刷新。 */
    val fallbackCharacterUuids: StateFlow<Set<String>> =
        conversationRepo.observeCharacterUuidsWithOfflineFallback()
            .map { it.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setQuery(value: String) {
        _query.value = value
    }

    /** Resolve (get-or-create) the character's single conversation, then open it. */
    fun openChat(character: CharacterEntity, onReady: (conversationUuid: String) -> Unit) {
        if (shareCoordinator.pendingPickerText.value != null) {
            // 观测点：待选文本非空本该走 shareTo（UI 分享模式），走到这=UI 态与单源态脱节（13.10a 排查 2026-07-02）。
            Log.w("ShareTarget", "异常：待选文本非空却走普通开聊 char=${character.uuid}")
        }
        viewModelScope.launch {
            onReady(conversationRepo.getOrCreateForCharacter(character.uuid, character.name.trim()))
        }
    }

    /**
     * 13.10a 分享给角色：在「点选收件角色」模式下点某角色 → 解析其会话 → 把暂存的分享文本投到该会话（后台落消息
     * + 跑一轮 LLM 回复，复用 B5 管线）→ 清掉待选 → 进该会话看角色回复。待选文本已空（竞态/已被别处处理）则忽略。
     */
    fun shareTo(character: CharacterEntity, onReady: (conversationUuid: String) -> Unit) {
        val text = shareCoordinator.pendingPickerText.value
        if (text == null) {
            // 观测点：分享模式点行却无待选文本 = 竞态/状态错乱信号，绝不该静默（13.10a 排查 2026-07-02）。
            Log.w("ShareTarget", "点选收件角色但待选文本已空 char=${character.uuid}")
            return
        }
        viewModelScope.launch {
            val uuid = conversationRepo.getOrCreateForCharacter(character.uuid, character.name.trim())
            shareCoordinator.deliverToConversation(uuid, text)
            shareCoordinator.consumePicker()
            onReady(uuid)
        }
    }

    /** 13.10a：取消本次「分享给角色」点选（退出选择模式，不发送）。 */
    fun cancelShare() = shareCoordinator.consumePicker()

    /**
     * 删角色：走 app 级 [CharacterDeletionService]（先经 `CharacterDeletionCleaner` 清散落关联数据 → 删角色本体
     * → 删头像文件，1:1 iOS 删序），**不随本页离屏中断**。
     *
     * 为何不在 `viewModelScope` 跑：本 VM 随导航离开 Contacts 标签销毁、scope 即取消；删除链若在 cleanup 半途、
     * `characterRepo.delete` 之前被取消，会留下「部分清理但仍存在」的半残角色（孤儿朋友圈帖永留 feed），故委托
     * 给独占 `SupervisorJob` scope 的 [CharacterDeletionService] 保证跑完（同会话删除走 [ConversationDeletionService]）。
     */
    fun delete(character: CharacterEntity) {
        deletionService.delete(character)
    }
}
