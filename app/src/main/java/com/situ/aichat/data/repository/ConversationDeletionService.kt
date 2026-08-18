package com.situ.aichat.data.repository

import com.situ.aichat.meeting.MeetingAppointmentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 原子删除会话（13.5 chat-ui-11）：先清磁盘媒体、后删库行（1:1 iOS 删序），跑在 **app 级 scope** 上。
 *
 * **为什么不放 ViewModel**：聊天列表 / 归档页的 ViewModel 随其导航目的地销毁、`viewModelScope` 即取消。
 * 删除链「清磁盘媒体（可能数百文件，耗时）→ 删库行」若在 cleanup 后、deleteById 前被取消，会留下
 * 「文件已删、会话行还在」的幽灵会话（点进去媒体全 404）。故用 `@Singleton` + `SupervisorJob` scope
 * 保证删除一旦发起就跑完，不随页面离屏中断（对抗复核 M1）。
 */
@Singleton
class ConversationDeletionService @Inject constructor(
    private val mediaCleaner: ConversationMediaCleaner,
    private val conversationRepo: ConversationRepository,
    private val meetingAppointmentStore: MeetingAppointmentStore,
    private val openLoopRepository: OpenLoopRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 删会话（fire-and-forget；先清磁盘媒体再删库行，FK CASCADE 连带删消息行）。 */
    fun delete(conversationUuid: String) {
        scope.launch {
            mediaCleaner.cleanup(conversationUuid)
            // Phase 10 未来约定见面：约定无 FK 不随会话级联删 → 删库行前先撤其到点通知 + 删约定行(防孤儿到点错喊赴约)。
            meetingAppointmentStore.deleteForConversation(conversationUuid)
            // 活人感一期 P2：惦记的事无 FK 不随会话级联删 → 手动清该会话的 loops（图纸 §3.2·E11）。
            // 未来到期 worker 即使已排，到点见不到 open loop 会静默退出（E4），无需在此撤销。
            openLoopRepository.deleteByConversation(conversationUuid)
            conversationRepo.deleteById(conversationUuid)
        }
    }
}
