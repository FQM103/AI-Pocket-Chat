package com.situ.aichat.notification

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知点击 → 会话跳转的信使（P6.1d）。1:1 对齐 iOS `NotificationService.navigationTarget`：
 * [StreakNotificationBridgeService.materializeFromClick] 物化后把目标会话 uuid 放进来，
 * Compose 侧（[com.situ.aichat.ui.AIChatApp]）观察到后导航到 `chat/{uuid}` 并 [consume]。
 *
 * 用 [StateFlow] 而非一次性事件：冷启动时点击处理（协程）可能早于 NavHost 挂载，晚到的收集者仍能读到目标。
 */
@Singleton
class NotificationNavigator @Inject constructor() {
    private val _pendingConversation = MutableStateFlow<String?>(null)
    val pendingConversation: StateFlow<String?> = _pendingConversation.asStateFlow()

    /** 朋友圈互动通知点击 → 帖子详情 uuid（决策①，P7.2.8）。与会话跳转分开，避免误进物化路径。 */
    private val _pendingMoment = MutableStateFlow<String?>(null)
    val pendingMoment: StateFlow<String?> = _pendingMoment.asStateFlow()

    /** 朋友圈「N 位好友」合并通知点击 → 打开朋友圈 feed（13.7e；无单帖 uuid，故用布尔信号）。 */
    private val _pendingMomentsFeed = MutableStateFlow(false)
    val pendingMomentsFeed: StateFlow<Boolean> = _pendingMomentsFeed.asStateFlow()

    /** 宠物小组件点击 → 宠物详情 characterUuid（P11.3）。复用同一深链信使（虽名 Notification，实为 App 深链路由）。 */
    private val _pendingPet = MutableStateFlow<String?>(null)
    val pendingPet: StateFlow<String?> = _pendingPet.asStateFlow()

    /**
     * 跳「联系人」Tab 的**一次性导航信号**（布尔，导航后即 consume）。两个来源共用：
     * - 分享给角色（Direct Share · C3，13.10a）通用分享 → 跳联系人「点选收件角色」（选择条数据另由
     *   [com.situ.aichat.share.ShareTargetCoordinator.pendingPickerText] 持有，与导航触发解耦）。
     * - 快捷设置磁贴（QS Tile · C7，13.10c）「找角色」→ 跳联系人。
     * 把「导航触发」与「界面数据」分开：导航只发生一次（不残留信号致意外重导航）。
     */
    private val _pendingContacts = MutableStateFlow(false)
    val pendingContacts: StateFlow<Boolean> = _pendingContacts.asStateFlow()

    /**
     * 自动备份通知点击 → 跳备份设置的**一次性信号**（P15·P0-19）。值 = focusFolder：
     * `false`=成功通知（仅跳备份页）、`true`=失败/目录丢失通知（跳备份页并自动打开目录选择器重选）；`null`=无。
     */
    private val _pendingBackup = MutableStateFlow<Boolean?>(null)
    val pendingBackup: StateFlow<Boolean?> = _pendingBackup.asStateFlow()

    fun request(conversationUuid: String) {
        _pendingConversation.value = conversationUuid
    }

    fun consume() {
        _pendingConversation.value = null
    }

    /**
     * 到点赴约通知点击（Phase 10）：携带 (会话 uuid, 约定 uuid)。[requestMeetupArrival] **同时复用既有会话导航通道**
     * （[request]）→ [com.situ.aichat.ui.AIChatApp] 照常导航到该会话；赴约动作（进线下见面沉浸）由该会话的
     * [com.situ.aichat.ui.chat.ChatViewModel] 观察本信号触发后 [consumeMeetupArrival]。两信号分担「导航」与「赴约」，
     * 故各自独立 consume（导航走 [consume]、赴约走 [consumeMeetupArrival]）。
     */
    private val _pendingMeetupArrival = MutableStateFlow<MeetupArrivalTarget?>(null)
    val pendingMeetupArrival: StateFlow<MeetupArrivalTarget?> = _pendingMeetupArrival.asStateFlow()

    fun requestMeetupArrival(conversationUuid: String, appointmentUuid: String) {
        _pendingMeetupArrival.value = MeetupArrivalTarget(conversationUuid, appointmentUuid)
        _pendingConversation.value = conversationUuid // 复用会话导航通道：AIChatApp 导航到该会话（VM 随后赴约）
    }

    fun consumeMeetupArrival() {
        _pendingMeetupArrival.value = null
    }

    fun requestMoment(postUuid: String) {
        _pendingMoment.value = postUuid
    }

    fun consumeMoment() {
        _pendingMoment.value = null
    }

    fun requestMomentsFeed() {
        _pendingMomentsFeed.value = true
    }

    fun consumeMomentsFeed() {
        _pendingMomentsFeed.value = false
    }

    fun requestPet(characterUuid: String) {
        _pendingPet.value = characterUuid
    }

    fun consumePet() {
        _pendingPet.value = null
    }

    fun requestContacts() {
        _pendingContacts.value = true
    }

    fun consumeContacts() {
        _pendingContacts.value = false
    }

    /** 世界通知点击（[com.situ.aichat.notification.NotifierWorld.ACTION_OPEN_WORLD]·W9a）→ 压栈世界屏的一次性信号。 */
    private val _pendingWorld = MutableStateFlow(false)
    val pendingWorld: StateFlow<Boolean> = _pendingWorld.asStateFlow()

    fun requestWorld() {
        _pendingWorld.value = true
    }

    fun consumeWorld() {
        _pendingWorld.value = false
    }

    /** 里程碑庆祝通知点击 → 角色资料页 characterUuid（P1-33）。纯导航不物化。 */
    private val _pendingCharacterProfile = MutableStateFlow<String?>(null)
    val pendingCharacterProfile: StateFlow<String?> = _pendingCharacterProfile.asStateFlow()

    fun requestCharacterProfile(characterUuid: String) {
        _pendingCharacterProfile.value = characterUuid
    }

    fun consumeCharacterProfile() {
        _pendingCharacterProfile.value = null
    }

    fun requestBackup(focusFolder: Boolean) {
        _pendingBackup.value = focusFolder
    }

    fun consumeBackup() {
        _pendingBackup.value = null
    }

    /** 故事章节解锁/完成/失败通知点击 → 故事详情 storyId（U4·11.1g 深链此前无消费方=死链）。纯导航不物化。 */
    private val _pendingStory = MutableStateFlow<String?>(null)
    val pendingStory: StateFlow<String?> = _pendingStory.asStateFlow()

    fun requestStory(storyId: String) {
        _pendingStory.value = storyId
    }

    fun consumeStory() {
        _pendingStory.value = null
    }
}

/** 到点赴约导航目标（Phase 10）：[conversationUuid] 导航目标会话，[appointmentUuid] 赴约的约定真理源 uuid。 */
data class MeetupArrivalTarget(
    val conversationUuid: String,
    val appointmentUuid: String,
)
