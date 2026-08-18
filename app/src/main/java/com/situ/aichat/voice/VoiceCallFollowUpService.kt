package com.situ.aichat.voice

import android.util.Log
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.recovery.RecoveryClaimTracker
import com.situ.aichat.recovery.RecoveryReplyGenerator
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 通话失联圆场（C6 通话体验加固 2026-07-12·模式 1:1 [com.situ.aichat.meeting.MeetingMissedReactionService]）：
 * 一通电话里用户说了话、AI 却一句都没能说出口（LLM 死/TTS 全哑），挂断后让角色**主动在聊天里发一条消息**
 * 圆回来——真人电话断了也会发「刚信号不好，你说啥？」。把最糟的体验（全程无回应）翻转成情感加分。
 *
 * 机制：插一条隐藏旁白（[MessageKind.SYSTEM_HINT]·用户不可见、只喂模型）陈述「通话故障、你的话用户没听到」，
 * 再借无头回复生成器 [RecoveryReplyGenerator] 按角色人设生成圆场消息。通话轮次消息（isPartOfVoiceCall）
 * 已在模型历史里，角色自然能衔接用户在电话里问过的话。
 *
 * 生成失败不丢事件：旁白已在上下文里，用户下次互动的回合角色照样带出。占坑 [RecoveryClaimTracker]
 * 防与未答恢复/爽约反应同会话双答；旁白为**纯括号叙述、不含 DirtyMessageDetector 保留段标题**（§7 坑）。
 */
@Singleton
class VoiceCallFollowUpService @Inject constructor(
    private val messageRepo: MessageRepository,
    private val replyGenerator: RecoveryReplyGenerator,
    private val claimTracker: RecoveryClaimTracker,
    private val userProfileDao: UserProfileDao,
) {

    /** 挂断后调用（调用方已判定「用户说过话且 AI 零句出声」）。旁白必落；生成回复尽力而为。 */
    suspend fun followUpAfterSilentCall(conversationUuid: String, now: Long = System.currentTimeMillis()) {
        val userName = (userProfileDao.get()?.nickname ?: "").ifBlank { "用户" }
        messageRepo.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversationUuid,
                roleRaw = "user",
                content = silentCallHint(userName),
                timestamp = now,
                messageKindRaw = MessageKind.SYSTEM_HINT.raw,
            ),
        )
        if (!claimTracker.tryBegin(conversationUuid)) return
        try {
            withTimeoutOrNull(FOLLOW_UP_TIMEOUT_MS) { replyGenerator.generateAndPersist(conversationUuid) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "通话失联圆场生成失败 conv=$conversationUuid: ${e.message}")
        } finally {
            claimTracker.end(conversationUuid)
        }
    }

    internal companion object {
        private const val TAG = "VoiceCallFollowUp"
        private const val FOLLOW_UP_TIMEOUT_MS = 5L * 60 * 1000 // 对齐爽约反应/未答恢复 deadline

        /** 隐藏旁白：只陈述故障事实 + 要求按人设圆场并回应通话里的话。纯括号、无保留段标题（§7）。[userName]=真实用户名（空由调用方兜底「用户」）。 */
        internal fun silentCallHint(userName: String): String =
            "（刚才你和${userName}打了一通语音电话，但通话出了故障：${userName}在电话里说了话，而你的回应${userName}一句也没有听到，" +
                "最后电话就这样挂断了。请你现在主动发一条消息——像刚挂掉一通信号很差的电话的人那样，自然地圆回" +
                "这件事，并回应${userName}在电话里说过的话；语气符合你的性格与你们当前的关系，不要提「系统」「故障」这类技术词。）"
    }
}
