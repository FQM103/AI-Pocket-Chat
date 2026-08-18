package com.situ.aichat.ui.gift

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.gift.AffinitySenseService
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.gift.GiftItem
import com.situ.aichat.gift.GiftReactionService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 礼物店反应页 VM（9.2d d-2，1:1 iOS `GiftReactionView.generateIfNeeded`）。
 *
 * 两种入口共用（按 recordUuid 加载）：
 * - **送礼流程**（spendAndCreateRecord 后）：record 反应为空 → [GiftReactionService.generateReaction] 调 LLM 生成。
 * - **收礼盒回放**（d-5）：record 已有反应 → **不重调 LLM**，只重取一条 sense 文案（回放不至于每次同一句话）。
 */
@HiltViewModel
class GiftReactionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val giftDao: GiftDao,
    private val characterRepo: CharacterRepository,
    private val reactionService: GiftReactionService,
    private val affinitySenseService: AffinitySenseService,
    private val apiConfigRepo: ApiConfigRepository,
) : ViewModel() {

    enum class Phase { LOADING, REVEALED, ERROR }

    data class UiState(
        val phase: Phase = Phase.LOADING,
        val characterName: String = "",
        val avatarPath: String? = null,
        val item: GiftItem? = null,
        val outcome: GiftReactionService.ReactionOutcome? = null,
    )

    private val recordUuid: String = savedStateHandle.get<String>(ARG_RECORD_UUID).orEmpty()

    /**
     * true=送礼流程（首次，可调 LLM 生成反应）；false=收礼盒回放。
     *
     * **回放永不调 LLM**（遵 9.2d「回放不重调 LLM」+ 守钱）：iOS 把 reactionText 为空的 user→character 记录也送进
     * GiftReactionView 会误触发 generateReaction 二次写 affinity（聊天送礼记录无 reactionText）；安卓回放只读已存反应，
     * 空则静态展示，**绝不重生成、绝不二次入账**（有意优于 iOS 的 money-safe 偏差）。
     */
    private val isSendFlow: Boolean = savedStateHandle.get<Boolean>(ARG_SEND) ?: true

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            val record = giftDao.getByUuid(recordUuid)
            val character = record?.let { characterRepo.get(it.receiverCharacterUUID) }
            val item = record?.let { GiftCatalog.find(it.giftItemId) }
            if (record == null || character == null || item == null) {
                _state.value = _state.value.copy(phase = Phase.ERROR)
                return@launch
            }
            _state.value = UiState(
                phase = Phase.LOADING,
                characterName = character.name,
                avatarPath = character.avatarPath,
                item = item,
            )

            // 回放：record 已有反应 → 不重调 LLM，只重取一条拟人文案
            if (record.reactionText.isNotEmpty()) {
                val sense = AffinitySenseService.currentSenseText(
                    character.affinitySensePackageJSON, record.affinityGain, item.isHandmade,
                )
                _state.value = _state.value.copy(
                    phase = Phase.REVEALED,
                    outcome = GiftReactionService.ReactionOutcome(
                        reactionText = record.reactionText,
                        moodEmoji = record.reactionMoodEmoji,
                        affinityGain = record.affinityGain,
                        usedLLM = true,
                        senseText = sense.text,
                        handmadeBadge = sense.handmadeBadge,
                    ),
                )
                return@launch
            }

            // 回放（收礼盒，isSendFlow=false）且无已存反应（如聊天送礼记录）：静态展示，**不调 LLM、不二次入账**
            if (!isSendFlow) {
                _state.value = _state.value.copy(
                    phase = Phase.REVEALED,
                    outcome = GiftReactionService.ReactionOutcome(
                        reactionText = "", moodEmoji = "", affinityGain = 0,
                        usedLLM = false, senseText = "", handmadeBadge = null,
                    ),
                )
                return@launch
            }

            // 首次送礼流程：生成反应（CHAT 路由解析 config；null → 服务内部走本地兜底）
            val config = apiConfigRepo.resolveConfigValues(ApiFunction.CHAT)
            val outcome = reactionService.generateReaction(record, item, character, config)
            _state.value = _state.value.copy(phase = Phase.REVEALED, outcome = outcome)

            // 后台无声补充心意文案包（1:1 iOS fire-and-forget；config 非空才调，失败静默）
            if (config != null) {
                viewModelScope.launch {
                    runCatching { affinitySenseService.generatePackageIfNeeded(character, config) }
                }
            }
        }
    }

    companion object {
        const val ARG_RECORD_UUID = "recordUuid"
        const val ARG_SEND = "send"
    }
}
