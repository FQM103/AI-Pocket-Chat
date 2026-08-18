package com.situ.aichat.notification

/**
 * 一条待发通知的内容快照（P6.1a / 扩展于 6.1d）。两种触发模式（预生成精确闹钟 / 到点现写）最终都把它交给
 * [Notifier.post] 发出，保证外观一致。
 *
 * 6.1d 起，本快照同时承载「落成聊天消息 + 点击跳转」所需的全部字段：
 * - [deliveryIdentifier] 非空表示这是一条**可物化**的主动消息——发出时落「待物化标记」
 *   （[PendingDeliveryStore]），回前台转成会话里的 assistant 消息；点击深链也带它命中台账去重。
 * - [characterId] 用于角色已删 / 会话缺失时兜底建会话（对齐 iOS ensureConversation）。
 * - [conversationUuid] 点击跳转目标；为空则据 [characterId] 解析。
 * - [requestKey] / [scheduledAtMillis] 写入投递台账（[requestKey] 同时用于从系统托盘撤回该通知）。
 */
data class NotificationPayload(
    /** 系统通知 id；同 id 再发会覆盖。由调用方用稳定 key 的 hashCode 生成。 */
    val notificationId: Int,
    /** 通知标题（一般是角色名）。 */
    val title: String,
    /** 通知正文（角色口吻文案）。 */
    val body: String,
    /** 关联会话 uuid（点击跳转用；为空则据 [characterId] 解析或新建预留会话）。 */
    val conversationUuid: String? = null,
    /** 角色 uuid（物化兜底建会话用）。 */
    val characterId: String? = null,
    /** 投递标识：非空 → 这是可物化的主动消息（发出时落标记 + 点击带它命中去重）。 */
    val deliveryIdentifier: String? = null,
    /** 通知分类（火花 6 类 / schedule_N），写入台账。 */
    val category: String? = null,
    /** 调度时的稳定 requestKey（= 台账 requestIdentifier；其 hashCode 即通知 id，供托盘撤回）。 */
    val requestKey: String? = null,
    /** 计划触发时刻（epoch millis），写入台账 scheduledAt。 */
    val scheduledAtMillis: Long = 0L,
    /** P6.1e app 图标角标值（未读会话和 + 待发序号）；>0 时 [Notifier] 调 setNumber，HyperOS/MIUI 桌面读取显示数字角标。 */
    val badgeCount: Int = 0,
    /** 故事 id（仅故事解锁通知 [Notifier.CATEGORY_STORY_UNLOCK] 用：点击深链跳该故事，见 [Notifier.postStory]）。 */
    val storyId: String? = null,
    /**
     * true = 到点不直接发本 [body]，而是由 [NotificationAlarmReceiver] 起加急
     * [com.situ.aichat.work.ProactiveNotificationWorker] 走「现做 → 兜底链 → 闸门」管线（正文到点才产生，
     * 排程期 [body] 恒空串）。**仅主动消息**（火花 / 日程驱动）恒为 true；日历 / 红包 / 故事 / 宠物 /
     * 见面等直发类别均为 false，仍到点直接发预烤 [body]——receiver 靠本字段区分两条路。
     */
    val freshResolution: Boolean = false,
    /**
     * 角色头像文件路径（13.8 · B3）：随通知透传到发出时刻，[Notifier.post] 据此把主动消息通知升级为
     * MessagingStyle 聊天气泡（带角色头像）。为空 → 只显示名字气泡（优雅降级）。仅「角色发来的消息」类
     * （主动 / 日历 / 忙碌回复）填充；宠物 / 红包 / 故事等非会话通知不用。
     */
    val avatarPath: String? = null,
    /**
     * 由头描述（「因为什么事想找你」）：排程时定下、随闹钟透传到到点，交
     * [com.situ.aichat.prompt.notification.ProactiveMessageComposer] 现做文案。仅主动消息填；
     * 老版本残留闹钟无此字段（null）→ 现做时用锁定兜底由头。
     */
    val occasion: String? = null,
)
