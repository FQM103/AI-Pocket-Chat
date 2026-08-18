package com.situ.aichat.notification

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.situ.aichat.R
import com.situ.aichat.util.AvatarStore

private const val TAG = "Notifier"

/**
 * [Notifier] 的通知「组装」辅助函数族——从 [Notifier] 原样拆出（文件瘦身，见 FILE_SIZE_REFACTOR_BACKLOG.md）。
 * 原是 [Notifier] 的 `private` 成员，搬成同包顶层 `internal` 函数后，[Notifier] 各 `post*` 方法仍免限定照常调用；
 * 行为逐字节不变（仅去缩进 + `private`→`internal` + 引用 [Notifier] 常量加限定）。
 *
 * 职责单一 =「把一条通知长什么样组装出来」：锁屏隐私占位 + BigText/BigPicture 样式底座（[privacyBuilder]）、
 * 各渠道样式（[companionBuilder]/[storyBuilder]）、角色消息头像气泡样式（applyCompanionMessagingStyle 扩展）、
 * 通知栏动作按钮（[replyAction] 直接回复 / [redPacketClaimAction] 领红包）、多角色消息分组汇总（postCompanionGroupSummary）。
 * 「何时发哪条通知」的编排逻辑仍在 [Notifier]。§5 红线：深链 key / extra 值逐字节不变，[NotificationReplyReceiver] /
 * [RedPacketClaimReceiver] 的接收契约不破。
 */

/**
 * 13.8·B2：红包「领取」动作（无 RemoteInput，故 PendingIntent 用 FLAG_IMMUTABLE，更安全）。点击 →
 * [RedPacketClaimReceiver] 触发**既有** [com.situ.aichat.redpacket.RedPacketService.acceptRedPacket]（幂等·不新增钱算）。
 * data 用 recordUuid 确保各红包 PI 互不覆盖。
 */
internal fun redPacketClaimAction(
    context: Context,
    notificationId: Int,
    recordUuid: String,
    conversationUuid: String?,
): NotificationCompat.Action {
    val intent = Intent(context, RedPacketClaimReceiver::class.java).apply {
        action = Notifier.ACTION_CLAIM_RED_PACKET
        data = Uri.parse("aichat://redpacket-claim/$recordUuid")
        putExtra(Notifier.EXTRA_RED_PACKET_UUID, recordUuid)
        putExtra(Notifier.EXTRA_CLAIM_CONVERSATION, conversationUuid)
        putExtra(Notifier.EXTRA_CLAIM_NOTIF_ID, notificationId)
    }
    val pi = PendingIntent.getBroadcast(
        context,
        notificationId,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Action.Builder(
        R.drawable.ic_notif_red_packet,
        context.getString(R.string.notif_red_packet_claim_action),
        pi,
    ).build()
}

/**
 * 13.8·B3：把「角色发来的消息」通知（主动消息 / 日历 / 忙碌回复，都走 [post]）升级为 [NotificationCompat.MessagingStyle]
 * 聊天气泡——角色 [Person]（名字 + 头像）+ 一条消息，系统把它归入「对话」区、显示圆形头像，像真微信。iOS 通知是
 * 普通样式，这是安卓超越项（纯加分·观感升级）。锁屏隐私不变：[privacyBuilder] 已设 VISIBILITY_PRIVATE + 「新消息」
 * publicVersion，MessagingStyle 仅是私有完整版的样式。头像从烤进 [payload] 的 avatarPath 同步解码（cache-first，发出点
 * 位于精确闹钟广播接收者 / 加急 worker，无法 suspend；≤512px 快解、解后入缓存）；为空 → 只显示名字气泡（优雅降级）。
 */
internal fun NotificationCompat.Builder.applyCompanionMessagingStyle(
    context: Context,
    payload: NotificationPayload,
): NotificationCompat.Builder {
    val character = Person.Builder()
        .setName(payload.title)
        .apply {
            payload.characterId?.let { setKey(it) }
            AvatarStore.loadBlocking(payload.avatarPath)?.let { setIcon(IconCompat.createWithBitmap(it)) }
        }
        .build()
    val you = Person.Builder().setName(context.getString(R.string.notif_messaging_you)).build()
    val timestamp = payload.scheduledAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
    val style = NotificationCompat.MessagingStyle(you).addMessage(payload.body, timestamp, character)
    // 13.8·B3 复核 LOW：挂会话快捷方式 id（ConversationShortcutPublisher 以 conversationUuid 发布的长效快捷方式）→
    // 有对应快捷方式（活跃 top 会话）时归入系统「对话」区并消除 MessagingStyle-无-shortcut 警告；无对应则正常区渲染。
    payload.conversationUuid?.let { setShortcutId(it) }
    // 15.2 #8：标人际消息（≈ iOS INSendMessageIntent communication-notification），拿回 HyperOS 通讯类待遇。
    return setStyle(style).setCategory(NotificationCompat.CATEGORY_MESSAGE)
}

/**
 * 13.8·B1：通知「直接回复」动作（[RemoteInput] 输入框）。点击 → [NotificationReplyReceiver] 取文字 → 加急 worker
 * 跑完整一轮 LLM 回复并回推（见 [postChatReply]）。PendingIntent **必须 FLAG_MUTABLE**（系统据此把用户输入塞回 intent）。
 * data 用会话/角色键确保各会话 PI 互不覆盖（filterEquals 忽略 extras）。[conversationUuid] 为空时由 worker 据 [characterId] 解析/建会话。
 */
internal fun replyAction(
    context: Context,
    notificationId: Int,
    conversationUuid: String?,
    characterId: String?,
    characterName: String,
    avatarPath: String?,
): NotificationCompat.Action {
    val remoteInput = RemoteInput.Builder(Notifier.KEY_TEXT_REPLY)
        .setLabel(context.getString(R.string.notif_reply_hint))
        .build()
    val key = conversationUuid ?: characterId ?: notificationId.toString()
    val intent = Intent(context, NotificationReplyReceiver::class.java).apply {
        action = Notifier.ACTION_REPLY
        data = Uri.parse("aichat://notif-reply/$key")
        putExtra(Notifier.EXTRA_REPLY_CONVERSATION, conversationUuid)
        putExtra(Notifier.EXTRA_REPLY_CHARACTER_ID, characterId)
        putExtra(Notifier.EXTRA_REPLY_TITLE, characterName)
        putExtra(Notifier.EXTRA_REPLY_AVATAR, avatarPath)
        putExtra(Notifier.EXTRA_REPLY_NOTIF_ID, notificationId)
    }
    val pi = PendingIntent.getBroadcast(
        context,
        notificationId,
        intent,
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Action.Builder(R.drawable.ic_notif_reply, context.getString(R.string.notif_reply_action), pi)
        .addRemoteInput(remoteInput)
        .setAllowGeneratedReplies(true)
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
        .setShowsUserInterface(false)
        .build()
}

/**
 * C1#1：维护 COMPANION 分组的 group summary。统计当前托盘内属本组、非 summary 的活跃子通知数：
 * ≥2 → 发/更新一条 InboxStyle 汇总（HyperOS 折叠成一束「N 条新消息」）；<2 → 撤掉 summary（避免一条
 * 子通知时残留空组头）。归入系统「对话」区的会话通知由系统单独渲染、本就不在 group 子集里——不受影响。
 * 无 POST_NOTIFICATIONS 权限时上游已 return，这里再守一次活跃查询的 SecurityException。
 */
@SuppressLint("MissingPermission")
internal fun postCompanionGroupSummary(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val childCount = runCatching {
        manager.activeNotifications.count {
            it.notification.group == Notifier.COMPANION_GROUP_KEY && it.id != Notifier.COMPANION_GROUP_SUMMARY_ID
        }
    }.getOrDefault(0)
    if (childCount < 2) {
        NotificationManagerCompat.from(context).cancel(Notifier.COMPANION_GROUP_SUMMARY_ID)
        return
    }
    val summaryText = context.getString(R.string.notif_companion_group_summary, childCount)
    val summary = NotificationCompat.Builder(context, NotificationChannels.COMPANION)
        .setSmallIcon(R.drawable.ic_notif_companion)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(summaryText)
        .setStyle(NotificationCompat.InboxStyle().setSummaryText(summaryText))
        .setGroup(Notifier.COMPANION_GROUP_KEY)
        .setGroupSummary(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setAutoCancel(true)
        .build()
    try {
        NotificationManagerCompat.from(context).notify(Notifier.COMPANION_GROUP_SUMMARY_ID, summary)
    } catch (e: Exception) {
        Log.w(TAG, "群组汇总通知投递失败: ${e.message}")
    }
}

/** 角色消息通知样式（COMPANION 渠道）：主动消息 / 续火花 / 日历事件 / 朋友圈互动共用，委托 [privacyBuilder]。（宠物 / 红包已于 13.8·B4 拆出独立渠道。） */
internal fun companionBuilder(
    context: Context,
    title: String,
    body: String,
    contentIntent: PendingIntent,
): NotificationCompat.Builder =
    privacyBuilder(context, NotificationChannels.COMPANION, title, body, contentIntent, smallIcon = R.drawable.ic_notif_companion)

/** 故事更新通知样式（STORY 渠道，11.1f-2），委托 [privacyBuilder]。 */
internal fun storyBuilder(
    context: Context,
    title: String,
    body: String,
    contentIntent: PendingIntent,
): NotificationCompat.Builder =
    privacyBuilder(context, NotificationChannels.STORY, title, body, contentIntent, smallIcon = R.drawable.ic_notif_story)

/**
 * 公共「锁屏隐私占位 + BigText/BigPicture + 深链」通知样式（按渠道参数化，避免重复）：锁屏隐藏敏感通知时旁人只看到
 * 标题 + 「新消息」占位（对齐 iOS NotificationCategoryRegistrar 占位文案）。
 * [image] 非空（13.7e 朋友圈首图）→ 展开用 BigPictureStyle 大图；为空 → BigTextStyle 展开正文（默认，原各调用方不变）。
 *
 * @param smallIcon 状态栏小图标（**必须是单色剪影 drawable**：系统按 alpha 取形，彩色 adaptive 启动图标会被渲染成白色实心块）。
 *   显式传过审专属剪影的：[companionBuilder]/[storyBuilder]、红包过期预警、NotifierWorld 到达/摘要（灵动岛卷一 R1 🔵-1）。
 *   默认 [R.drawable.ic_notif_companion] = 其余直调方的兜底（聊天/朋友圈/里程碑/经济/宠物 + 红包领取确认）；
 *   给它们配专属剪影属 UI 设计决策，未过审不自行分配（灵动岛卷一 §11 D-5）。
 */
internal fun privacyBuilder(
    context: Context,
    channelId: String,
    title: String,
    body: String,
    contentIntent: PendingIntent,
    image: Bitmap? = null,
    smallIcon: Int = R.drawable.ic_notif_companion,
): NotificationCompat.Builder {
    val publicVersion = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(smallIcon)
        .setContentTitle(title)
        .setContentText(context.getString(R.string.notif_hidden_placeholder))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()
    val style = if (image != null) {
        NotificationCompat.BigPictureStyle().bigPicture(image).setSummaryText(body)
    } else {
        NotificationCompat.BigTextStyle().bigText(body)
    }
    return NotificationCompat.Builder(context, channelId)
        .setSmallIcon(smallIcon)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(style)
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion)
        .setContentIntent(contentIntent)
}
