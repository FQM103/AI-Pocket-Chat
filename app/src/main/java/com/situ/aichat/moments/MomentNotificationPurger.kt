package com.situ.aichat.moments

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.situ.aichat.data.model.MomentNotificationType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 删角色撤朋友圈族已弹通知专线（P1-44；镜像 PetReminderScheduler.purgeForCharacter /
 * RedPacketExpirationScanService.purgeForConversations 的「key 归属模块」模式）。
 * 通知 id=帖×角色复合、不落投递台账，[com.situ.aichat.notification.NotificationScheduler] 三路枚举够不着，
 * 须按删行前预捕获的帖 uuid 前向枚举。撤不存在的 id=系统 no-op。
 */
@Singleton
class MomentNotificationPurger @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 删角色撤已弹（P1-44）。两组 uuid 须在 CharacterDeletionCleaner ① 删 likes/comments/posts 之前预捕获。 */
    fun purgeForCharacter(characterUuid: String, ownPostUuids: List<String>, interactedPostUuids: List<String>) {
        val nm = NotificationManagerCompat.from(context)
        purgeNotificationIds(characterUuid, ownPostUuids, interactedPostUuids).forEach { nm.cancel(it) }
    }

    companion object {
        /**
         * 纯函数（internal 供单测）：新帖（角色自有帖）+ 互动（角色赞/评过的帖 × 类型闭集 × 本角色）。
         * 类型用 entries 枚举=新增类型自动覆盖；角色对他角色帖的赞/评会产生从未发出过的候选 id → cancel no-op
         * 的过近似，安全。**有意豁免**合并新动态 moment_newpost_merged（文案无角色名+深链 feed 永活+
         * 盲撤会吞他角色提示）。接受残影面：用户手删角色评论行/帖 30 天硬删 CASCADE 后枚举源消失（均为陈旧通知）。
         */
        internal fun purgeNotificationIds(
            characterUuid: String,
            ownPostUuids: List<String>,
            interactedPostUuids: List<String>,
        ): Set<Int> = buildSet {
            ownPostUuids.forEach { add(MomentNewPostNotifier.singleNotificationId(it)) }
            interactedPostUuids.forEach { post ->
                MomentNotificationType.entries.forEach { type ->
                    add(MomentInteractionService.interactionNotificationId(type, post, characterUuid))
                }
            }
        }
    }
}
