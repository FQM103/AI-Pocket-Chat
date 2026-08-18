package com.situ.aichat.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.situ.aichat.MainActivity
import com.situ.aichat.R
import com.situ.aichat.util.AvatarStore
import com.situ.aichat.widget.PetWidgetIntents

/**
 * 构建并发出一条本地通知（P6.1a / 6.1d 扩展）。两种触发模式（预生成精确闹钟 / 到点现写）最终都经这里发出，
 * 保证外观一致。无 POST_NOTIFICATIONS 权限时静默跳过（国行 / 低版本兜底）——此时也**不**落物化标记
 * （没弹出的通知不该变成会话消息，对齐 iOS「未授权则不调度/不物化」）。
 *
 * 锁屏隐私：整条设 VISIBILITY_PRIVATE，并挂一个仅显示「新消息」的 publicVersion——锁屏隐藏敏感
 * 通知时旁人只看到标题(角色名)+「新消息」（对齐 iOS NotificationCategoryRegistrar 的占位文案）。
 *
 * 6.1d：
 * - **点击深链**——点击通知打开 [MainActivity] 并带 [NotificationPayload] 的关键字段（投递标识 / 会话 /
 *   角色 / 文案…），由 MainActivity 路由到对应会话并物化（[StreakNotificationBridgeService.materializeFromClick]）。
 * - **发出即投递标记**——成功 notify() 后，对可物化通知（[NotificationPayload.deliveryIdentifier] 非空）落一条
 *   [PendingDeliveryStore] 标记，App 回前台时转成会话里的 assistant 消息。
 */
object Notifier {

    private const val TAG = "Notifier"

    /**
     * 统一通知投递：包 try/catch。notify() 在畸形通知 / binder 瞬时故障 / 系统异常时会抛，而本类多由
     * BroadcastReceiver / Worker 调用——未捕获会连带崩溃且零痕迹（用户只觉「通知没来」）。捕获 + Log.w 留痕，
     * 投递失败不连累调用方。[label] 仅用于日志定位是哪类通知失败。返回是否投递成功（仅 [post] 用以决定后续记账）。
     */
    @SuppressLint("MissingPermission") // 调用方均已 isGranted 守卫
    internal fun postSafely(context: Context, notificationId: Int, notification: Notification, label: String): Boolean =
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            // W8·§3.4：任何出声通知刷新「上次出声」台账（静音 channel 不算「震」）——世界到达通知门 7 排队据此让路。
            if (notification.channelId !in NotificationChannels.SILENT_CHANNELS) NotificationPostLedger.recordPost(context)
            true
        } catch (e: Exception) {
            Log.w(TAG, "通知投递失败·$label id=$notificationId: ${e.message}")
            false
        }

    // POST_NOTIFICATIONS 由 NotificationPermission.isGranted 守卫（lint 看不穿自定义助手，故抑制误报）。
    @SuppressLint("MissingPermission")
    fun post(context: Context, payload: NotificationPayload) {
        if (!NotificationPermission.isGranted(context)) return

        val notification = companionBuilder(context, payload.title, payload.body, deepLinkIntent(context, payload))
            // C1#1：挂统一分组键，与 group summary 配合让系统把多角色主动消息折成一束（防通知轰炸）。
            .setGroup(COMPANION_GROUP_KEY)
            // P6.1e app 图标角标：未读会话+待发序号烤进的数字，HyperOS/MIUI 桌面读取显示（原生 Android 仅圆点）。
            .apply { if (payload.badgeCount > 0) setNumber(payload.badgeCount) }
            // 13.8·B3：升级为 MessagingStyle 头像气泡（角色发来的消息 → 像真微信，归入系统「对话」区）。
            .applyCompanionMessagingStyle(context, payload)
            // 13.8·B1：可物化的角色消息（主动 / 日历，deliveryIdentifier 非空）挂「直接回复」框——在通知栏打字回角色，
            // 后台跑完整一轮 LLM、回复再推回通知栏。忙碌延迟回复（deliveryIdentifier=null）不挂（避开忙碌投递的边界双答）。
            .apply {
                if (payload.deliveryIdentifier != null && payload.characterId != null) {
                    addAction(replyAction(context, payload.notificationId, payload.conversationUuid, payload.characterId, payload.title, payload.avatarPath))
                }
            }
            .build()

        if (!postSafely(context, payload.notificationId, notification, "companion")) return
        // C1#1：发一条 group summary 把同组子通知折叠成一束（治多角色主动/续火花消息在 HyperOS 通知栏轰炸）。
        // 归入「对话」区的会话通知（有匹配快捷方式）由系统单独渲染、不参与 summary——无害；落普通区的才被折叠。
        postCompanionGroupSummary(context)

        // 发出即投递：可物化通知落一条标记，回前台物化成会话消息（去重靠 deliveryIdentifier + materializedAt）。
        val deliveryId = payload.deliveryIdentifier
        if (deliveryId != null) {
            PendingDeliveryStore.appendDelivered(
                context,
                PendingDeliveryStore.PendingDelivery(
                    deliveryIdentifier = deliveryId,
                    characterId = payload.characterId.orEmpty(),
                    category = payload.category.orEmpty(),
                    conversationUuid = payload.conversationUuid.orEmpty(),
                    notificationBody = payload.body,
                    requestIdentifier = payload.requestKey ?: deliveryId,
                    scheduledAt = payload.scheduledAtMillis,
                    deliveredAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * 13.8·B1：通知栏直接回复后，把这一轮对话回推成通知栏 MessagingStyle 气泡（一来一回都在通知栏完成）。由
     * [com.situ.aichat.notification.NotificationReplyWorker] 调用两次：① 提交回复后立即 [statusHint]=「正在回复…」+ 只含
     * 你刚打的那句；② 回合跑完后 [statusHint]=null（成功）/「稍后回复你」（失败）+ 含会话最近消息（含 AI 回复）。
     * 点击进会话（纯导航，消息已落库，不再物化）。**保留**回复框（[replyAction]）便于继续聊。无权限静默跳过。
     */
    @SuppressLint("MissingPermission") // 同 post()：isGranted 守卫
    fun postChatReply(
        context: Context,
        notificationId: Int,
        conversationUuid: String,
        characterId: String?,
        characterName: String,
        avatarPath: String?,
        messages: List<NotificationReplyThread.ReplyThreadMessage>,
        statusHint: String?,
    ) {
        if (!NotificationPermission.isGranted(context)) return
        NotificationChannels.ensureCreated(context)
        val character = Person.Builder()
            .setName(characterName)
            .apply {
                characterId?.let { setKey(it) }
                AvatarStore.loadBlocking(avatarPath)?.let { setIcon(IconCompat.createWithBitmap(it)) }
            }
            .build()
        val you = Person.Builder().setName(context.getString(R.string.notif_messaging_you)).build()
        val style = NotificationCompat.MessagingStyle(you)
        messages.forEach { style.addMessage(it.text, it.timestamp, if (it.isUser) you else character) }
        val preview = messages.lastOrNull()?.text.orEmpty()
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            conversationShortcutIntent(context, conversationUuid), // 纯导航进会话（消息已落库）
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = privacyBuilder(context, NotificationChannels.COMPANION, characterName, preview, contentIntent)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE) // 15.2 #8：标人际消息，拿回 HyperOS 通讯类待遇（≈ iOS INSendMessageIntent）
            .setShortcutId(conversationUuid) // 13.8·B3 复核 LOW：归入系统「对话」区（会话快捷方式）
            .addAction(replyAction(context, notificationId, conversationUuid, characterId, characterName, avatarPath))
            .apply { statusHint?.let { setSubText(it) } }
            .build()
        postSafely(context, notificationId, notification, "chatReply")
    }

    /**
     * 朋友圈互动系统通知（决策①，P7.2.8）：AI 赞/评用户的帖 → 发系统通知，点击深链进帖子详情（**有意偏离
     * iOS 的 app 内通知列表**，铁律#1 原生达同效）。走 [ACTION_OPEN_MOMENT] 独立动作，不进会话物化路径；
     * 不落 [PendingDeliveryStore]（朋友圈通知不物化成聊天消息）。无权限静默跳过——in-app 红点/列表仍照常。
     */
    @SuppressLint("MissingPermission") // 同 post()：isGranted 守卫
    fun postMomentInteraction(context: Context, notificationId: Int, title: String, body: String, postUuid: String) {
        if (!NotificationPermission.isGranted(context)) return
        NotificationChannels.ensureCreated(context) // 防后台 worker 进程未经启动初始化 → 渠道缺失
        val notification = companionBuilder(context, title, body, momentDeepLinkIntent(context, notificationId, postUuid)).build()
        postSafely(context, notificationId, notification, "momentInteraction")
    }

    /**
     * 朋友圈「X 发了新动态」单帖通知（13.7e·**安卓超越 iOS**：iOS 对角色自己的新帖零提醒、只在 feed 静默出现）：
     * MOMENT 渠道（可单独静音）+ 有图 BigPicture(首图) / 无图 BigText(正文) + 深链进该帖详情（复用 [ACTION_OPEN_MOMENT]）。
     * **不物化成聊天消息**（不落 [PendingDeliveryStore]）。无权限静默跳过。仅由后台周期发帖触发（见 MomentNewPostNotifier）。
     */
    @SuppressLint("MissingPermission") // 同 post()：isGranted 守卫
    fun postNewMomentPost(
        context: Context,
        notificationId: Int,
        title: String,
        body: String,
        image: Bitmap?,
        postUuid: String,
    ) {
        if (!NotificationPermission.isGranted(context)) return
        NotificationChannels.ensureCreated(context) // 防后台 worker 进程未初始化渠道 → 通知静默失败
        val notification = privacyBuilder(
            context, NotificationChannels.MOMENT, title, body, momentDeepLinkIntent(context, notificationId, postUuid), image,
        ).build()
        postSafely(context, notificationId, notification, "newMomentPost")
    }

    /**
     * 朋友圈「N 位好友发了新动态」合并通知（13.7e）：MOMENT 渠道 + 深链进朋友圈 feed（[ACTION_OPEN_MOMENTS_FEED]，
     * 非单帖故无 uuid，避开必填 uuid 的 moment/{uuid} 路由）。不物化。无权限静默跳过。
     */
    @SuppressLint("MissingPermission") // 同 post()：isGranted 守卫
    fun postMergedMomentPosts(context: Context, notificationId: Int, title: String, body: String) {
        if (!NotificationPermission.isGranted(context)) return
        NotificationChannels.ensureCreated(context)
        val notification = privacyBuilder(
            context, NotificationChannels.MOMENT, title, body, momentsFeedDeepLinkIntent(context, notificationId),
        ).build()
        postSafely(context, notificationId, notification, "mergedMomentPosts")
    }

    /** 经济聚合通知固定 id：后发替换前发不堆叠（同朋友圈合并通知惯例）。 */
    val ECONOMY_SUMMARY_NOTIFICATION_ID = "economy_summary".hashCode()

    /** 关系里程碑庆祝通知固定 id（P1-33）：替换式=「批量合并」（单次评估单角色，跨角色近同时升级近空集）。 */
    val MILESTONE_NOTIFICATION_ID = "relationship_milestone".hashCode()

    /** C1#1：COMPANION 角色消息通知统一分组键 + group summary 固定 id（防多角色主动消息通知栏轰炸）。 */
    internal const val COMPANION_GROUP_KEY = "com.situ.aichat.notification.COMPANION_GROUP"
    internal val COMPANION_GROUP_SUMMARY_ID = "companion_group_summary".hashCode()

    /**
     * 关系里程碑庆祝通知（P1-33·安卓超越 iOS 零通知）：[NotificationChannels.MILESTONE] 渠道（DEFAULT·
     * 频率极低的庆祝）+ 深链该角色资料页（[ACTION_OPEN_CHARACTER_PROFILE]，MainActivity 经
     * NotificationNavigator.requestCharacterProfile 跳 `characterProfile/{uuid}`）。**不显金币、不物化**。
     * 文案由调用方（MilestoneCelebrationNotifier）组好传入。无权限静默跳过。
     * @return 是否真发出（P1-44：权限被收回时 false——调用方据此决定是否记「最后庆祝角色」单槽，防脏槽覆盖）。
     */
    @SuppressLint("MissingPermission") // 同 post()：isGranted 守卫
    fun postMilestone(context: Context, characterUuid: String, title: String, body: String): Boolean {
        if (!NotificationPermission.isGranted(context)) return false
        NotificationChannels.ensureCreated(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CHARACTER_PROFILE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHARACTER_PROFILE_UUID, characterUuid)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            MILESTONE_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = privacyBuilder(context, NotificationChannels.MILESTONE, title, body, contentIntent).build()
        postSafely(context, MILESTONE_NOTIFICATION_ID, notification, "milestone")
        return true
    }

    /** 里程碑通知点击 → 角色资料页 uuid（非该通知点击返回 null）。MainActivity 据此投放跳转目标，纯导航不物化。 */
    fun characterProfileClickUuidFrom(intent: Intent?): String? {
        if (intent == null || intent.action != ACTION_OPEN_CHARACTER_PROFILE) return null
        return intent.getStringExtra(EXTRA_CHARACTER_PROFILE_UUID)
    }

    /**
     * 角色经济动态聚合通知（P1-40·安卓超越 iOS：iOS 对发薪/房租/奖金零通知）：[NotificationChannels.ECONOMY]
     * 静音渠道（IMPORTANCE_LOW，价值在留痕可回看）+ 固定 id 替换式（发薪日波及全角色合 1 条）+ 点击开 App
     * （事件跨角色无单一深链目标；明细在各角色钱包卡/账本）。**不物化**。无权限静默跳过。
     */
    @SuppressLint("MissingPermission") // 同 post()：isGranted 守卫
    fun postEconomySummary(context: Context, title: String, body: String) {
        if (!NotificationPermission.isGranted(context)) return
        NotificationChannels.ensureCreated(context)
        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            ECONOMY_SUMMARY_NOTIFICATION_ID,
            openApp,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = privacyBuilder(context, NotificationChannels.ECONOMY, title, body, contentIntent).build()
        postSafely(context, ECONOMY_SUMMARY_NOTIFICATION_ID, notification, "economySummary")
    }

    /**
     * 宠物饿/病提醒（13.7c；安卓超越 iOS·到点真叫）：[NotificationChannels.PET] 独立渠道（13.8 · B4，便于单独静音）
     * + 深链进宠物详情（复用 [PetWidgetIntents.openPetDetail] = 小组件点击同一路由，MainActivity 经
     * NotificationNavigator.requestPet 跳转）。**不物化成聊天消息**（宠物提醒非角色对话）。文案为静态模板（精确闹钟
     * 预烤，到点由 [NotificationAlarmReceiver] 经本方法发出，App 被杀也弹）。无权限静默跳过。
     */
    @SuppressLint("MissingPermission") // 同 post()：isGranted 守卫
    fun postPet(context: Context, notificationId: Int, title: String, body: String, characterUuid: String) {
        if (!NotificationPermission.isGranted(context)) return
        NotificationChannels.ensureCreated(context) // 防后台闹钟进程未经启动初始化 → 渠道缺失
        val pi = PendingIntent.getActivity(
            context,
            notificationId,
            PetWidgetIntents.openPetDetail(context, characterUuid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = privacyBuilder(context, NotificationChannels.PET, title, body, pi).build()
        postSafely(context, notificationId, notification, "pet")
    }

    /**
     * 红包即将过期预警（P9.3b，角色→用户红包 22h 未拆）：[NotificationChannels.RED_PACKET] 独立渠道（13.8 · B4，
     * 便于单独静音）+ 深链进会话（看红包气泡），**不物化成聊天消息**（瞬时提醒，不落 [PendingDeliveryStore]，对齐 iOS
     * 直接 UNNotification 而非走投递管线）。`setOnlyAlertOnce` → 精确闹钟与前台扫描可能用同 id 双投，只首次出声、后续静默
     * 更新，避免重复打扰。**通知不含金额**（T4，payload 已脱敏）。
     */
    @SuppressLint("MissingPermission") // 同 post()：isGranted 守卫
    fun postRedPacketExpiring(context: Context, payload: NotificationPayload) {
        if (!NotificationPermission.isGranted(context)) return
        NotificationChannels.ensureCreated(context) // 防后台闹钟进程未经启动初始化 → 渠道缺失
        val builder = privacyBuilder(
            context, NotificationChannels.RED_PACKET, payload.title, payload.body, deepLinkIntent(context, payload),
            smallIcon = R.drawable.ic_notif_red_packet,
        )
            .setOnlyAlertOnce(true)
        // 13.8·B2：挂「领取」动作按钮——通知栏点一下就领（触发既有 acceptRedPacket，绝不新增钱算路径）。
        val recordUuid = redPacketUuidFromRequestKey(payload.requestKey)
        if (recordUuid != null) {
            builder.addAction(redPacketClaimAction(context, payload.notificationId, recordUuid, payload.conversationUuid))
        }
        postSafely(context, payload.notificationId, builder.build(), "redPacketExpiring")
    }

    /**
     * 13.8·B2：红包领取成功后的确认通知（**不含金额**，保持金额只在 App 内可见的现有习惯，用户拍板 2026-06-09）。
     * 复用原预警的 [notificationId] → 替换掉「快过期」预警（= 收起原提醒 + 提示已领取）。点击进会话看红包气泡。无权限静默跳过。
     */
    @SuppressLint("MissingPermission") // 同 post()：isGranted 守卫
    fun postRedPacketClaimed(context: Context, notificationId: Int, conversationUuid: String?) {
        if (!NotificationPermission.isGranted(context)) return
        NotificationChannels.ensureCreated(context)
        val tapIntent = if (conversationUuid != null) {
            conversationShortcutIntent(context, conversationUuid)
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = privacyBuilder(
            context,
            NotificationChannels.RED_PACKET,
            context.getString(R.string.notif_red_packet_claimed_title),
            context.getString(R.string.notif_red_packet_claimed_body),
            contentIntent,
        ).build()
        postSafely(context, notificationId, notification, "redPacketClaimed")
    }

    /**
     * 故事通知（P11）：章节生成完成/失败（11.1f-2，1:1 iOS `StoryGenerationTaskManager.sendNotification`）与章节解锁
     * （11.1g-1，1:1 iOS `StoryScheduleService.scheduleUnlockNotification`）共用——都只是 STORY 渠道上一条带故事深链的通知，
     * 仅 [title]/[body] 不同。STORY 渠道 + 锁屏隐私占位（标题照常显示、正文隐为「新消息」= iOS generic category）。点击深链
     * [ACTION_OPEN_STORY] + 故事 id 打开 app——**跳转到具体故事的路由留 11.1h-j**（书架/阅读器 UI 落地时由 MainActivity/
     * NotificationNavigator 经 [storyClickIdFrom] 消费）。**不物化成聊天消息**（不落 [PendingDeliveryStore]，对齐 iOS）。无权限静默跳过。
     */
    @SuppressLint("MissingPermission") // 同 post()：isGranted 守卫
    fun postStory(context: Context, notificationId: Int, storyId: String, title: String, body: String) {
        if (!NotificationPermission.isGranted(context)) return
        NotificationChannels.ensureCreated(context) // 防后台进程未经启动初始化 → 渠道缺失
        val notification = storyBuilder(context, title, body, storyDeepLinkIntent(context, notificationId, storyId)).build()
        postSafely(context, notificationId, notification, "story")
    }

    /**
     * 「未来约定见面」到点提醒（Phase 10·角色到点喊你赴约）：COMPANION 渠道（同日历提醒「有人找你」级·HIGH）+
     * 升 MessagingStyle 头像气泡（[applyCompanionMessagingStyle]，像角色发来一句「该出发啦」）。点击走
     * [ACTION_OPEN_MEETUP_ARRIVAL] 深链 → 回会话**自动进入线下见面沉浸**（赴约·MainActivity 路由）；过宽限交爽约扫描。
     * **不物化成聊天消息**（提醒非对话·不落 [PendingDeliveryStore]）。文案预烤（精确闹钟到点由 [NotificationAlarmReceiver]
     * 经本方法发出，App 被杀也弹）。requestKey 缺前缀（理论不会）/ 无权限 → 静默跳过。
     */
    @SuppressLint("MissingPermission") // 同 post()：isGranted 守卫
    fun postMeetup(context: Context, payload: NotificationPayload) {
        if (!NotificationPermission.isGranted(context)) return
        NotificationChannels.ensureCreated(context) // 防后台闹钟进程未经启动初始化 → 渠道缺失
        val appointmentUuid = meetupUuidFromRequestKey(payload.requestKey) ?: return
        val notification = companionBuilder(context, payload.title, payload.body, meetupArrivalIntent(context, payload, appointmentUuid))
            .applyCompanionMessagingStyle(context, payload)
            .build()
        postSafely(context, payload.notificationId, notification, "meetup")
    }

    /**
     * 朋友圈帖子深链 Intent（通知点击 PendingIntent 与桌面小组件 13.9b 共用同一通道）：走 [ACTION_OPEN_MOMENT]
     * 独立动作，MainActivity 经 [momentClickUuidFrom] 取 uuid 直接投放 `moment/{uuid}` 跳转目标，不走会话物化路径。
     */
    fun momentClickIntent(context: Context, postUuid: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_MOMENT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_MOMENT_UUID, postUuid)
        }

    private fun momentDeepLinkIntent(context: Context, notificationId: Int, postUuid: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId,
            momentClickIntent(context, postUuid),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** 朋友圈通知点击 → 帖子 uuid（非朋友圈点击返回 null）。MainActivity 据此直接投放跳转目标，不走物化。 */
    fun momentClickUuidFrom(intent: Intent?): String? {
        if (intent == null || intent.action != ACTION_OPEN_MOMENT) return null
        return intent.getStringExtra(EXTRA_MOMENT_UUID)
    }

    /** 朋友圈「N 位好友」合并通知点击 → 打开朋友圈 feed（13.7e；无单帖 uuid，故独立 action 不走 moment/{uuid} 路由）。 */
    private fun momentsFeedDeepLinkIntent(context: Context, notificationId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_MOMENTS_FEED
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** 朋友圈合并通知点击 → 是否为「打开 feed」请求（13.7e；MainActivity 据此跳朋友圈 feed，不走单帖路由/物化）。 */
    fun isMomentsFeedClick(intent: Intent?): Boolean = intent?.action == ACTION_OPEN_MOMENTS_FEED

    /**
     * 自动备份通知点击 → 打开 [MainActivity] 跳备份设置（P15·P0-19，纯导航不物化）。[focusFolder]=true（失败/目录丢失）
     * 额外带标志让备份页进页自动开目录选择器重选。成功/失败两条共用 [BackupNotifier] 的固定通知 id，故用不同 requestCode
     * 防 [PendingIntent] 因 FLAG_UPDATE_CURRENT 复用到陈旧 extra。
     */
    fun backupSettingsIntent(context: Context, focusFolder: Boolean, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_BACKUP
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_BACKUP_FOCUS_FOLDER, focusFolder)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** 备份通知点击 → focusFolder 标志（非备份点击返 null）。MainActivity 据此跳备份页 / 自动重选目录。 */
    fun backupClickFocusFolderFrom(intent: Intent?): Boolean? {
        if (intent == null || intent.action != ACTION_OPEN_BACKUP) return null
        return intent.getBooleanExtra(EXTRA_BACKUP_FOCUS_FOLDER, false)
    }

    /** 故事通知点击 → 打开 [MainActivity] 并带故事 id（跳转到阅读器留 11.1h-j 消费）。requestCode 用 notificationId 区分。 */
    internal fun storyDeepLinkIntent(context: Context, notificationId: Int, storyId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_STORY
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_STORY_ID, storyId)
        }
        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** 故事通知点击 → 故事 id（非故事点击返回 null）。供 11.1h-j 的 MainActivity 路由到阅读器，本块仅备好解析。 */
    fun storyClickIdFrom(intent: Intent?): String? {
        if (intent == null || intent.action != ACTION_OPEN_STORY) return null
        return intent.getStringExtra(EXTRA_STORY_ID)
    }

    /**
     * 「未来约定见面」到点赴约通知点击 → 打开 [MainActivity] 走 [ACTION_OPEN_MEETUP_ARRIVAL]：带约定 uuid + 会话 uuid，
     * MainActivity 经 [meetupArrivalClickFrom] 路由到赴约（回会话 + 进沉浸·Phase 10b）。requestCode 用 notificationId 区分。
     */
    private fun meetupArrivalIntent(context: Context, payload: NotificationPayload, appointmentUuid: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_MEETUP_ARRIVAL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_MEETUP_UUID, appointmentUuid)
            putExtra(EXTRA_CONVERSATION, payload.conversationUuid)
        }
        return PendingIntent.getActivity(
            context,
            payload.notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** 到点赴约通知点击 → (约定 uuid, 会话 uuid)；非赴约点击返回 null。供 MainActivity 路由赴约（Phase 10b 接线消费）。 */
    fun meetupArrivalClickFrom(intent: Intent?): MeetupArrivalClick? {
        if (intent == null || intent.action != ACTION_OPEN_MEETUP_ARRIVAL) return null
        val appointmentUuid = intent.getStringExtra(EXTRA_MEETUP_UUID) ?: return null
        return MeetupArrivalClick(appointmentUuid, intent.getStringExtra(EXTRA_CONVERSATION))
    }

    /** 点击通知 → 打开 [MainActivity]，带物化 / 跳转所需字段。requestCode 用 notificationId 区分各通知。 */
    private fun deepLinkIntent(context: Context, payload: NotificationPayload): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CONVERSATION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DELIVERY_ID, payload.deliveryIdentifier)
            putExtra(EXTRA_CONVERSATION, payload.conversationUuid)
            putExtra(EXTRA_CHARACTER_ID, payload.characterId)
            putExtra(EXTRA_BODY, payload.body)
            putExtra(EXTRA_CATEGORY, payload.category)
            putExtra(EXTRA_REQUEST_KEY, payload.requestKey)
            putExtra(EXTRA_SCHEDULED_AT, payload.scheduledAtMillis)
        }
        return PendingIntent.getActivity(
            context,
            payload.notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * 从 [MainActivity] 收到的 Intent 解析出点击负载；非通知点击（普通启动）返回 null。
     * 即使投递标记/台账丢失，凭这些字段也能自愈物化（[StreakNotificationBridgeService.materializeFromClick]）。
     */
    fun clickPayloadFrom(intent: Intent?): NotificationClickPayload? {
        if (intent == null || intent.action != ACTION_OPEN_CONVERSATION) return null
        return NotificationClickPayload(
            deliveryIdentifier = intent.getStringExtra(EXTRA_DELIVERY_ID),
            conversationUuid = intent.getStringExtra(EXTRA_CONVERSATION),
            characterId = intent.getStringExtra(EXTRA_CHARACTER_ID),
            notificationBody = intent.getStringExtra(EXTRA_BODY).orEmpty(),
            category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty(),
            requestKey = intent.getStringExtra(EXTRA_REQUEST_KEY),
            scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, 0L),
        )
    }

    /**
     * 会话快捷方式（C2 图标长按动态快捷方式）的跳转 [Intent]——复用现有会话深链通道（同 [MainActivity] +
     * [EXTRA_CONVERSATION]），但走独立 [ACTION_OPEN_CONVERSATION_SHORTCUT]：**纯导航**到会话、**不**触发通知物化。
     */
    fun conversationShortcutIntent(context: Context, conversationUuid: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CONVERSATION_SHORTCUT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION, conversationUuid)
        }

    /** 从快捷方式点击 Intent 解析会话 uuid；非快捷方式点击返回 null。 */
    fun conversationShortcutUuidFrom(intent: Intent?): String? {
        if (intent == null || intent.action != ACTION_OPEN_CONVERSATION_SHORTCUT) return null
        return intent.getStringExtra(EXTRA_CONVERSATION)
    }

    /** 红包过期预警通知的 category（NotificationAlarmReceiver 据此路由到 [postRedPacketExpiring]，不走物化）。 */
    const val CATEGORY_RED_PACKET_EXPIRING = "red_packet_expiring"

    /**
     * 13.8·B2：从红包预警 requestKey（`red_packet_expiring_<uuid>` = [RedPacketExpirationScanService.warningRequestKey]）
     * 剥前缀取 recordUuid，供「领取」动作定位红包。前缀 = [CATEGORY_RED_PACKET_EXPIRING] + "_"（复用本类常量、不耦合红包模块内部）。
     * 非红包预警 key / 缺失 / 仅前缀 → null（不挂领取动作）。internal 供纯函数单测（涉钱：错 uuid = 错领目标）。
     */
    internal fun redPacketUuidFromRequestKey(requestKey: String?): String? {
        if (requestKey == null) return null
        val prefix = "${CATEGORY_RED_PACKET_EXPIRING}_"
        if (!requestKey.startsWith(prefix)) return null
        return requestKey.removePrefix(prefix).takeIf { it.isNotBlank() }
    }

    /**
     * 从到点提醒 requestKey（`meetup_<uuid>` = [com.situ.aichat.meeting.MeetupNotificationService.requestKey]）剥前缀取
     * 约定 uuid，供赴约深链定位约定。前缀 = [CATEGORY_MEETUP] + "_"（单源·与服务侧严丝合缝）。非 meetup key /
     * 缺失 / 仅前缀 → null。internal 供纯函数单测。
     */
    internal fun meetupUuidFromRequestKey(requestKey: String?): String? {
        if (requestKey == null) return null
        val prefix = "${CATEGORY_MEETUP}_"
        if (!requestKey.startsWith(prefix)) return null
        return requestKey.removePrefix(prefix).takeIf { it.isNotBlank() }
    }

    /** 故事章节解锁通知的 category（11.1g-1；NotificationAlarmReceiver 据此路由到 [postStory]，不走物化）。 */
    const val CATEGORY_STORY_UNLOCK = "story_unlock"

    /** 宠物饿/病提醒的 category（13.7c；NotificationAlarmReceiver 据此路由到 [postPet]，不走物化）。 */
    const val CATEGORY_PET = "pet_status"

    /**
     * 「未来约定见面」到点提醒的 category（Phase 10；NotificationAlarmReceiver 据此路由到 [postMeetup]，不走物化）。
     * 同时是闹钟 key 前缀（`meetup_<uuid>`，§6 参数表「通知 id」）——服务侧 requestKey / 本类剥前缀均单源于此。
     */
    const val CATEGORY_MEETUP = "meetup"

    const val ACTION_OPEN_CONVERSATION = "com.situ.aichat.notification.OPEN_CONVERSATION"
    const val ACTION_OPEN_MOMENT = "com.situ.aichat.notification.OPEN_MOMENT"
    const val ACTION_OPEN_MOMENTS_FEED = "com.situ.aichat.notification.OPEN_MOMENTS_FEED"
    const val ACTION_OPEN_STORY = "com.situ.aichat.notification.OPEN_STORY"
    const val ACTION_OPEN_MEETUP_ARRIVAL = "com.situ.aichat.notification.OPEN_MEETUP_ARRIVAL"

    /** 关系里程碑庆祝通知点击 → 角色资料页（P1-33）。 */
    const val ACTION_OPEN_CHARACTER_PROFILE = "com.situ.aichat.notification.OPEN_CHARACTER_PROFILE"

    /** 会话快捷方式（C2 图标长按 / 动态快捷方式）跳转 action——**纯导航**到会话，不走通知物化路径。 */
    const val ACTION_OPEN_CONVERSATION_SHORTCUT = "com.situ.aichat.shortcut.OPEN_CONVERSATION"

    /** 快捷设置磁贴（QS Tile · C7，13.10c「找角色」）跳转 action——**纯导航**到联系人 Tab。 */
    const val ACTION_OPEN_CONTACTS = "com.situ.aichat.tile.OPEN_CONTACTS"

    /** 是否为 QS 磁贴「找角色」点击（[ACTION_OPEN_CONTACTS]）。 */
    fun isOpenContactsClick(intent: Intent?): Boolean = intent?.action == ACTION_OPEN_CONTACTS

    /** 自动备份通知点击（P15·P0-19）跳备份设置 action——纯导航，不走物化。 */
    const val ACTION_OPEN_BACKUP = "com.situ.aichat.notification.OPEN_BACKUP"
    const val EXTRA_BACKUP_FOCUS_FOLDER = "backup_focus_folder"

    /** 13.8·B1：通知直接回复动作 action（[NotificationReplyReceiver] 接收，exported=false 仅本应用 PI 触发）。 */
    const val ACTION_REPLY = "com.situ.aichat.notification.REPLY"

    /** 13.8·B1：[RemoteInput] 结果 key（[NotificationReplyReceiver] 用 [RemoteInput.getResultsFromIntent] 取此键的文字）。 */
    const val KEY_TEXT_REPLY = "key_text_reply"

    const val EXTRA_REPLY_CONVERSATION = "reply_conversation_uuid"
    const val EXTRA_REPLY_CHARACTER_ID = "reply_character_id"
    const val EXTRA_REPLY_TITLE = "reply_title"
    const val EXTRA_REPLY_AVATAR = "reply_avatar_path"
    const val EXTRA_REPLY_NOTIF_ID = "reply_notification_id"

    /** 13.8·B2：红包「领取」动作 action（[RedPacketClaimReceiver] 接收，exported=false 仅本应用 PI 触发）。 */
    const val ACTION_CLAIM_RED_PACKET = "com.situ.aichat.notification.CLAIM_RED_PACKET"

    const val EXTRA_RED_PACKET_UUID = "claim_red_packet_uuid"
    const val EXTRA_CLAIM_CONVERSATION = "claim_conversation_uuid"
    const val EXTRA_CLAIM_NOTIF_ID = "claim_notification_id"

    private const val EXTRA_MOMENT_UUID = "click_moment_uuid"
    private const val EXTRA_CHARACTER_PROFILE_UUID = "click_character_profile_uuid"
    private const val EXTRA_STORY_ID = "click_story_id"
    private const val EXTRA_MEETUP_UUID = "click_meetup_uuid"
    private const val EXTRA_DELIVERY_ID = "click_delivery_id"
    private const val EXTRA_CONVERSATION = "click_conversation_uuid"
    private const val EXTRA_CHARACTER_ID = "click_character_id"
    private const val EXTRA_BODY = "click_body"
    private const val EXTRA_CATEGORY = "click_category"
    private const val EXTRA_REQUEST_KEY = "click_request_key"
    private const val EXTRA_SCHEDULED_AT = "click_scheduled_at"
}

/** 到点赴约通知点击负载（Phase 10）：[appointmentUuid] 定位约定真理源，[conversationUuid] 跳转目标会话（可空则据约定解析）。 */
data class MeetupArrivalClick(
    val appointmentUuid: String,
    val conversationUuid: String?,
)
