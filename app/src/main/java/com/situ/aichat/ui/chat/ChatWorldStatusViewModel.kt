package com.situ.aichat.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.world.stage.WorldPresenceLine
import com.situ.aichat.world.stage.WorldStageResolver.PlaceType
import com.situ.aichat.world.stage.WorldStageService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 聊天状态行胶囊数据（W13 图纸 §3.6）：emoji + 文案 + 点击跳世界的 focusSpec。 */
data class ChatWorldPill(val emoji: String, val text: String, val focusSpec: String)

/**
 * 聊天状态行 VM（W13 图纸 §3.6）：refresh 驱动（无轮询·位置分钟级变化·返回聊天页即刷新已够）。
 * 五态 → (emoji/text/focusSpec) 映射。文案经 [Context] 取资源（VM 层无 UI Context 依赖冲突·同项目 Toast 惯例）。
 * **绝不建世·零写库**——[WorldStageService.presenceLineFor] 内部 state null 直接 null。
 */
@HiltViewModel
class ChatWorldStatusViewModel @Inject constructor(
    private val stageService: WorldStageService,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _pill = MutableStateFlow<ChatWorldPill?>(null)
    val pill: StateFlow<ChatWorldPill?> = _pill.asStateFlow()

    /** 由 ChatScreen 的 LaunchedEffect(characterUuid) + ON_RESUME 各调一次（图纸 §3.6·无轮询）。 */
    fun refresh(characterUuid: String) {
        viewModelScope.launch {
            _pill.value = stageService.presenceLineFor(characterUuid, System.currentTimeMillis())?.toPill()
        }
    }

    private fun WorldPresenceLine.toPill(): ChatWorldPill = when (kind) {
        WorldPresenceLine.Kind.AT_PLACE -> ChatWorldPill(
            emoji = placeType?.let(::emojiFor) ?: EMOJI_IN_TOWN,
            text = appContext.getString(R.string.world_chat_pill_at_place, placeName.orEmpty()),
            focusSpec = "interior:$cityId:$placeId",
        )
        WorldPresenceLine.Kind.AT_HOME -> ChatWorldPill(
            EMOJI_AT_HOME, appContext.getString(R.string.world_chat_pill_at_home), "town:$cityId",
        )
        WorldPresenceLine.Kind.SLEEPING -> ChatWorldPill(
            EMOJI_SLEEPING, appContext.getString(R.string.world_chat_pill_sleeping), "town:$cityId",
        )
        WorldPresenceLine.Kind.IN_TOWN -> ChatWorldPill(
            EMOJI_IN_TOWN, appContext.getString(R.string.world_chat_pill_in_town, cityName), "town:$cityId",
        )
        WorldPresenceLine.Kind.TRAVELING -> ChatWorldPill(
            EMOJI_TRAVELING, appContext.getString(R.string.world_chat_pill_traveling, destCityName.orEmpty()), "town:$destCityId",
        )
    }

    /** PlaceType → emoji（图纸 §4.6·锁死 §9）。 */
    private fun emojiFor(t: PlaceType): String = when (t) {
        PlaceType.CAFE -> "☕"
        PlaceType.RESTAURANT -> "🍜"
        PlaceType.BOOKSTORE -> "📚"
        PlaceType.PARK -> "🌳"
        PlaceType.SQUARE -> "⛲"
        PlaceType.DOCK -> "⛵"
        PlaceType.MARKET -> "🧺"
        PlaceType.TEAHOUSE -> "🍵"
        PlaceType.KILN -> "🏺"
        PlaceType.WORKSHOP -> "🛠️"
        PlaceType.LOOKOUT -> "🌄"
        PlaceType.BEACH -> "🐚"
        PlaceType.BOARDWALK -> "🌊"
        PlaceType.HALL -> "🏛️"
    }

    private companion object {
        // Kind → emoji（图纸 §4.6·锁死 §9）。
        const val EMOJI_AT_HOME = "🏠"
        const val EMOJI_SLEEPING = "💤"
        const val EMOJI_IN_TOWN = "🏘️"
        const val EMOJI_TRAVELING = "🚌"
    }
}
