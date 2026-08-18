package com.situ.aichat.data.backup

import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.NotificationTemplateEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity

// ════════════════════════════════ 创建副本：私有子树 uuid 重映射（13.6b-2b 纯函数，可测） ════════════════════════════════

/**
 * 「创建副本」重映射结果：整条角色私有子树，主键全部换新、FK 全部重指向新父键。原角色不在其中（原封不动）。
 */
internal class RemappedSubtree(
    val character: CharacterEntity,
    val conversations: List<ConversationEntity>,
    val messages: List<MessageEntity>,
    val milestones: List<MilestoneEntity>,
    val pet: CharacterPetEntity?,
    val wallet: CharacterWalletEntity?,
    val schedules: List<CharacterDailyScheduleEntity>,
    val scheduleEvents: List<ScheduleEventEntity>,
    val notificationTemplates: List<NotificationTemplateEntity>,
)

/**
 * 把一个角色的私有备份段重映射成「副本」的实体（**纯函数**，零 DB / 零 IO；[nextUuid] 可注入便于确定性单测）。
 *
 * **为什么安卓必须比 iOS 多做这一步**（用户已签字的被迫偏离）：iOS SwiftData 不强制主键唯一，故其 duplicate 路径
 * 复用 message/pet/wallet 的原 uuid（recon 实测会 uuid 碰撞）；Room **强制主键唯一** → 若照搬，副本会 REPLACE 掉
 * 原角色的消息/宠物/💰钱包（丢消息、**丢钱**）。故这里把整条私有子树主键全部换新：角色 / 会话 / 消息 / 里程碑 /
 * 宠物 / 💰角色钱包 / 日程+事件 / 通知模板。
 *
 * - **消息**：messageUUID 换新；[MessageExport.quotedMessageUUID] 按「角色内全量旧→新消息表」重映射（含跨会话引用；
 *   映射不到则保留原值）。embedding 字节原样拷贝（语义由内容决定，复制即正确）。
 * - **💰角色钱包**：仅覆写 uuid/characterUuid，**余额三字段照 [CharacterWalletExport.toEntity] 快照原样拷贝**
 *   （`?? 0` 不钳位），**无任何新增钱算/重放路径**——复用 13.6d 已判 SHIP 的语义。原角色钱包不动 → 不丢钱、不错算。
 * - **全局段（朋友圈/礼物/红包/故事/钱包/设置）不在此函数**：按 13.6a 已签字设计整体恢复一次，副本不另得重映射（已登记 LOW）。
 */
internal fun remapCharacterSubtree(
    cd: CharacterBackupData,
    newCharUuid: String,
    newPathByKey: Map<String, String>,
    nameSuffix: String,
    nextUuid: () -> String,
): RemappedSubtree {
    val character = cd.character
        .toEntity(
            cd.character.avatarArchiveKey?.let { newPathByKey[it] },
            cd.character.chatWallpaperArchiveKey?.let { newPathByKey[it] },
        )
        .copy(uuid = newCharUuid, name = cd.character.name + nameSuffix)

    // 角色内全量「旧 messageUUID → 新 uuid」表（先建全表，再插 → 覆盖跨会话引用的引用消息）。
    val msgRemap = HashMap<String, String>()
    cd.conversations?.forEach { conv -> conv.messages?.forEach { m -> msgRemap[m.messageUUID] = nextUuid() } }

    val conversations = ArrayList<ConversationEntity>()
    val messages = ArrayList<MessageEntity>()
    cd.conversations?.forEach { conv ->
        val newConvUuid = nextUuid()
        conversations.add(conv.toEntity(newCharUuid).copy(uuid = newConvUuid))
        conv.messages?.forEach { m ->
            messages.add(
                m.toEntity(newConvUuid, newPathByKey).copy(
                    messageUUID = msgRemap.getValue(m.messageUUID),
                    quotedMessageUUID = m.quotedMessageUUID?.let { msgRemap[it] ?: it },
                ),
            )
        }
    }
    val milestones = cd.milestones.orEmpty().map { it.toEntity(newCharUuid).copy(uuid = nextUuid()) }
    val pet = cd.pet?.let { it.toEntity(newCharUuid).copy(uuid = nextUuid()) }
    val wallet = cd.wallet?.let { it.toEntity(newCharUuid).copy(uuid = nextUuid()) }

    val schedules = ArrayList<CharacterDailyScheduleEntity>()
    val scheduleEvents = ArrayList<ScheduleEventEntity>()
    cd.schedules?.forEach { s ->
        val newSchedUuid = nextUuid()
        schedules.add(s.toEntity(newCharUuid).copy(uuid = newSchedUuid))
        s.events?.forEach { ev -> scheduleEvents.add(ev.toEntity(newSchedUuid).copy(uuid = nextUuid())) }
    }
    val notificationTemplates = cd.notificationTemplates.orEmpty().map { it.toEntity(newCharUuid).copy(id = nextUuid()) }

    return RemappedSubtree(
        character = character,
        conversations = conversations,
        messages = messages,
        milestones = milestones,
        pet = pet,
        wallet = wallet,
        schedules = schedules,
        scheduleEvents = scheduleEvents,
        notificationTemplates = notificationTemplates,
    )
}
