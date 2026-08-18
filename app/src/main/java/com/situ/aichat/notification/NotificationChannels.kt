package com.situ.aichat.notification

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.situ.aichat.R

/**
 * 通知渠道(P6.1a)。Android 8+ 必须先建渠道再发通知——在 [com.situ.aichat.AIChatApplication.onCreate]
 * 调一次 [ensureCreated] 即可（重复调用幂等）。
 *
 * iOS 用单一 generic category（锁屏隐藏预览时显示「新消息」占位）。安卓对应：一个「角色消息」渠道，
 * 承载主动消息 / 续火花 / 日历事件提醒——都是「角色发来的消息」，重要级 HIGH 以便横幅弹出。
 * 锁屏隐私占位由 [Notifier] 在每条通知上设 VISIBILITY_PRIVATE + publicVersion 实现。
 */
object NotificationChannels {

    /** 角色消息渠道 id：主动消息 / 续火花 / 日历事件提醒共用。 */
    const val COMPANION = "companion_messages"

    /** 语音通话常驻通知渠道（P10.1g）：前台服务的 CallStyle 通知，IMPORTANCE_HIGH 但静音（通话本身有声）。 */
    const val VOICE_CALL = "voice_call_ongoing"

    /**
     * 故事更新渠道（P11 · 11.1f-2）：章节生成完成/失败、11.1g 章节解锁提醒。独立渠道便于用户单独静音
     * （用户拍板，2026-06-05）。IMPORTANCE_DEFAULT（出声 + 进通知栏，不强弹横幅）——「内容已就绪」类提醒，
     * 不及 COMPANION 的「有人找你」级别。
     */
    const val STORY = "story_updates"

    /**
     * 故事生成前台服务常驻通知渠道（P11 · 11.1g-3）：生成期间保活用的 ongoing 通知。IMPORTANCE_LOW（静默、不打扰）——
     * 这是前台服务的技术性常驻指示，非用户提醒。
     */
    const val STORY_GENERATING = "story_generating_ongoing"

    /**
     * 自动备份状态渠道（13.6c）：定时本地自动备份的完成 / 失败提醒。IMPORTANCE_LOW（静默进通知栏、不弹横幅不响）——
     * 备份非紧急，每日成功提示不该打扰；失败在通知栏可见即可（下个周期会重试）。
     */
    const val BACKUP = "backup_status"

    /**
     * 朋友圈新动态渠道（13.7e）：「X 发了新动态」系统通知。独立渠道（同 [STORY] 的拆分理由）便于用户单独静音——
     * 这是「好友发帖了」的轻提醒，IMPORTANCE_DEFAULT（出声 + 进通知栏，不强弹横幅），不及 [COMPANION] 的「有人找你」级别。
     */
    const val MOMENT = "moment_new_post"

    /**
     * 宠物提醒渠道（13.8 · B4「每类独立渠道」）：宠物饿/病到点提醒（13.7c）。从 [COMPANION] 拆出，便于用户在
     * 系统通知设置里单独静音 / 调整宠物提醒（不连累角色消息）。IMPORTANCE_HIGH——保持拆分前在 COMPANION 上的
     * 行为（「到点真叫」需被注意），用户若嫌吵可自行在系统设置下调本渠道。
     */
    const val PET = "pet_reminders"

    /**
     * 红包提醒渠道（13.8 · B4「每类独立渠道」）：红包即将过期预警（P9.3b，角色→用户红包 22h 未拆）。从 [COMPANION]
     * 拆出便于单独静音。IMPORTANCE_HIGH——保持拆分前行为（过期预警需被及时看到，否则红包退回），用户可自行下调。
     */
    const val RED_PACKET = "red_packet"

    /**
     * 关系里程碑渠道（15.2-P1 批0 · 服务 P1-33·拍板 33A）：关系达到新阶段（朋友→恋人等）的庆祝通知。
     * IMPORTANCE_DEFAULT（出声 + 进通知栏，不强弹横幅）——情感节点值得一声轻响，但频率天然极低、
     * 不及 [COMPANION] 的「有人找你」级别；独立渠道便于单独静音。
     */
    const val MILESTONE = "relationship_milestone"

    /**
     * 角色经济动态渠道（15.2-P1 批0 · 服务 P1-40·拍板 40A）：发薪/房租/奖金/日程消费的聚合汇总通知。
     * IMPORTANCE_LOW + 静音（同 [BACKUP] 理由）——账本动态非紧急且可能较频繁（日程消费近乎每日），
     * 静默进通知栏留痕即可；通知内容三档（详细/简要/关）由设置层 gate，本渠道只管系统侧呈现。
     */
    const val ECONOMY = "economy_events"

    /**
     * 世界系统动态渠道（W8·契约 §7.A）：TA 到达你的城、你到站、世界合并摘要。IMPORTANCE_DEFAULT（出声 + 进通知栏，
     * 不强弹横幅·与 [MOMENT] 同级）——世界该「喊你一声」但绝不吵；每日封顶 / 统一排队 / 只降频不静音都在逻辑层做，
     * 本渠道只管系统侧呈现。独立渠道便于用户单独静音。
     */
    const val WORLD = "world_events"

    /**
     * 静音渠道集合（W8·§3.4·§9 恰四员）：这四个 channel 本就不出声（IMPORTANCE_LOW 或显式关声/震），故它们的通知
     * **不算「震」**——[Notifier.postSafely] 成功时不刷新「上次出声」台账（[NotificationPostLedger]），世界通知门 7
     * 排队据此不为它们让路。任何出声渠道（COMPANION / 日历 / RED_PACKET / PET / MILESTONE / MOMENT / WORLD…）都算。
     */
    val SILENT_CHANNELS = setOf(VOICE_CALL, STORY_GENERATING, BACKUP, ECONOMY)

    fun ensureCreated(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        val companion = NotificationChannelCompat.Builder(
            COMPANION,
            NotificationManagerCompat.IMPORTANCE_HIGH,
        )
            .setName(context.getString(R.string.notif_channel_companion_name))
            .setDescription(context.getString(R.string.notif_channel_companion_desc))
            .build()
        // 通话渠道：HIGH 保证常驻可见，但关声音/震动——通话音频自身就是反馈，通知不该再响。
        val voiceCall = NotificationChannelCompat.Builder(
            VOICE_CALL,
            NotificationManagerCompat.IMPORTANCE_HIGH,
        )
            .setName(context.getString(R.string.voice_call_notif_channel_name))
            .setDescription(context.getString(R.string.voice_call_notif_channel_desc))
            .setSound(null, null)
            .setVibrationEnabled(false)
            .build()
        val story = NotificationChannelCompat.Builder(
            STORY,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName(context.getString(R.string.notif_channel_story_name))
            .setDescription(context.getString(R.string.notif_channel_story_desc))
            .build()
        // 生成前台服务常驻通知：LOW + 静音，仅作「正在创作」的技术性指示。
        val storyGenerating = NotificationChannelCompat.Builder(
            STORY_GENERATING,
            NotificationManagerCompat.IMPORTANCE_LOW,
        )
            .setName(context.getString(R.string.notif_channel_story_generating_name))
            .setDescription(context.getString(R.string.notif_channel_story_generating_desc))
            .setSound(null, null)
            .setVibrationEnabled(false)
            .build()
        // 自动备份状态：LOW + 静音，完成/失败提醒进通知栏但不打扰。
        val backup = NotificationChannelCompat.Builder(
            BACKUP,
            NotificationManagerCompat.IMPORTANCE_LOW,
        )
            .setName(context.getString(R.string.notif_channel_backup_name))
            .setDescription(context.getString(R.string.notif_channel_backup_desc))
            .setSound(null, null)
            .setVibrationEnabled(false)
            .build()
        // 朋友圈新动态：DEFAULT（出声 + 进通知栏，不强弹横幅），可被用户在系统设置单独静音。
        val moment = NotificationChannelCompat.Builder(
            MOMENT,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName(context.getString(R.string.notif_channel_moment_name))
            .setDescription(context.getString(R.string.notif_channel_moment_desc))
            .build()
        // 宠物提醒：HIGH（保持从 COMPANION 拆出前的行为），独立渠道便于单独静音/调整。
        val pet = NotificationChannelCompat.Builder(
            PET,
            NotificationManagerCompat.IMPORTANCE_HIGH,
        )
            .setName(context.getString(R.string.notif_channel_pet_name))
            .setDescription(context.getString(R.string.notif_channel_pet_desc))
            .build()
        // 红包提醒：HIGH（保持从 COMPANION 拆出前的行为），独立渠道便于单独静音。
        val redPacket = NotificationChannelCompat.Builder(
            RED_PACKET,
            NotificationManagerCompat.IMPORTANCE_HIGH,
        )
            .setName(context.getString(R.string.notif_channel_red_packet_name))
            .setDescription(context.getString(R.string.notif_channel_red_packet_desc))
            .build()
        manager.createNotificationChannel(companion)
        manager.createNotificationChannel(voiceCall)
        manager.createNotificationChannel(story)
        manager.createNotificationChannel(storyGenerating)
        // 关系里程碑：DEFAULT（出声 + 进通知栏），频率极低的庆祝时刻。
        val milestone = NotificationChannelCompat.Builder(
            MILESTONE,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName(context.getString(R.string.notif_channel_milestone_name))
            .setDescription(context.getString(R.string.notif_channel_milestone_desc))
            .build()
        // 角色经济动态：LOW + 静音（同 backup），账本汇总静默留痕、不打扰。
        val economy = NotificationChannelCompat.Builder(
            ECONOMY,
            NotificationManagerCompat.IMPORTANCE_LOW,
        )
            .setName(context.getString(R.string.notif_channel_economy_name))
            .setDescription(context.getString(R.string.notif_channel_economy_desc))
            .setSound(null, null)
            .setVibrationEnabled(false)
            .build()
        // 世界系统动态：DEFAULT（出声 + 进通知栏，不强弹横幅），世界该轻响一声但绝不吵（封顶 / 排队 / 降频在逻辑层）。
        val world = NotificationChannelCompat.Builder(
            WORLD,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName(context.getString(R.string.notif_channel_world_name))
            .setDescription(context.getString(R.string.notif_channel_world_desc))
            .build()
        manager.createNotificationChannel(backup)
        manager.createNotificationChannel(moment)
        manager.createNotificationChannel(pet)
        manager.createNotificationChannel(redPacket)
        manager.createNotificationChannel(milestone)
        manager.createNotificationChannel(economy)
        manager.createNotificationChannel(world)
    }
}
