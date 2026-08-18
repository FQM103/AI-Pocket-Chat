package com.situ.aichat.widget

import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 角色「此刻」状态小组件的响应式同步桥（13.9a，仿 [PetWidgetSync] / [com.situ.aichat.shortcut.ConversationShortcutPublisher]）：
 * App 级一处观察会话+角色流，当**主对话身份**（哪个会话 / 名字 / 头像）变化时 nudge 小组件重渲染。
 *
 * 只观察「身份」变化（换了在聊的角色 / 改名 / 换头像即刷），**不**观察状态串的时间推进——那交给小组件渲染时**现算**
 * + [com.situ.aichat.work.WidgetRefreshWorker] 每 30 分定期兜底（用户拍板的刷新策略：事件驱动 + 现算 + 定期）。
 * [drop] 跳过首帧（开 App 时小组件本就自读最新），[distinctUntilChanged] 去抖（每条新消息不重刷，避免桌面闪烁）。
 * 由 [com.situ.aichat.ui.AppViewModel] 在 init 调 [start] 一次。
 */
@Singleton
class CharacterStatusWidgetSync @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
    private val updater: CharacterStatusWidgetUpdater,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    /** 幂等启动；由 [com.situ.aichat.ui.AppViewModel] 在 init 调用一次。 */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(conversationRepo.observeActive(), characterRepo.observeAll()) { convs, chars ->
                val conv = CharacterStatusWidgetData.pickConversation(convs) ?: return@combine null
                val character = chars.firstOrNull { it.uuid == conv.characterUuid }
                Identity(conv.uuid, character?.name?.takeIf { it.isNotBlank() } ?: conv.title, character?.avatarPath)
            }
                .distinctUntilChanged()
                .drop(1)
                .collect { updater.refresh() }
        }
    }

    /** 主对话「身份指纹」：仅这些变化才重刷（状态串时间推进不在此列）。 */
    private data class Identity(val conversationUuid: String, val name: String, val avatarPath: String?)
}
