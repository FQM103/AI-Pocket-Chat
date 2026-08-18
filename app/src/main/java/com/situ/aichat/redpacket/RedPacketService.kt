package com.situ.aichat.redpacket

import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.RedPacketDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.RedPacketRecordEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.RedPacketAmountCatalog
import com.situ.aichat.data.model.RedPacketData
import com.situ.aichat.data.model.RedPacketEventSenderRole
import com.situ.aichat.data.model.RedPacketJson
import com.situ.aichat.data.model.RedPacketStatus
import com.situ.aichat.data.model.SystemEventJson
import com.situ.aichat.data.model.SystemEventType
import com.situ.aichat.data.model.WalletOwnerType
import com.situ.aichat.data.model.makeRedPacketSystemEventData
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.economy.CurrencyService
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 红包发送/收/拒/过期服务（1:1 iOS `Services/RedPacketService.swift`，托管账户模型）。
 *
 * 流程总览（钱相关 1:1，**托管账户**：发送瞬间扣发送方、钱锁 Record status=pending、收/拒/24h 过期才二次结算）：
 * - **发送**（用户或角色）：校验金额范围 → 扣发送方钱包（spend / [CurrencyTransactionCategory.RED_PACKET]，relatedId=record.uuid）
 *   → 建 [RedPacketRecordEntity]（pending）→ 插 [MessageKind.RED_PACKET] 消息 → 落盘。
 * - **接受**（用户拆 / 角色 LLM 决定收）：guard pending → 转入接收方钱包（earn）→ status=accepted+resolvedAt → 系统事件消息。
 * - **拒收**（角色 LLM 决定拒）：guard pending → 退钱回**发送方** → status=rejected+rejectionReason+resolvedAt → 系统事件消息。
 * - **过期**（24h 未拆，9.3b 扫描调）：guard pending → 退钱回发送方 → status=expired+resolvedAt（不写 rejectionReason）→ 系统事件消息。
 *
 * iOS 是 `@MainActor enum`（单 `context.save()` 原子）；安卓改 `@Singleton` + 每法整段 [AppDatabase.withTransaction]
 * （= iOS 单 save 原子，任一步抛错全回滚）。[CurrencyService] 各 add/spend 内部各开 withTransaction，嵌在外层会**合流为同一事务**
 * （与 [com.situ.aichat.gift.ProactiveGiftExecutor]/[com.situ.aichat.gift.GiftSendService] 同模式，已过钱路径复核）。
 *
 * 幂等：accept/reject/expire 入口 `guard status==PENDING`，重复处理抛 [RedPacketError.AlreadyResolved]。
 * 系统事件消息（[insertSystemEventMessage]）失败（找不到 conversation/character）只 warn、不阻断状态机 + 退钱主路径（= iOS）。
 */
@Singleton
class RedPacketService @Inject constructor(
    private val db: AppDatabase,
    private val currencyService: CurrencyService,
    private val redPacketDao: RedPacketDao,
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
    private val characterRepo: CharacterRepository,
) {

    /** 发送结果（含消息 + Record，便于调用方后续业务，1:1 iOS `SendOutcome`）。 */
    data class SendOutcome(val message: MessageEntity, val record: RedPacketRecordEntity)

    // ── 发送出口 ──

    /**
     * 用户发送红包给角色（1:1 iOS `sendFromUser`，整段原子）。
     * 校验金额+余额 → 扣用户钱包（relatedId=record.uuid）→ 建 pending Record + RED_PACKET 消息（role=user）。
     */
    suspend fun sendFromUser(
        toCharacterUuid: String,
        toCharacterName: String,
        amount: Int,
        blessing: String = "",
        festivalId: String? = null,
        conversationUuid: String,
        now: Long = System.currentTimeMillis(),
    ): SendOutcome = db.withTransaction {
        if (!RedPacketAmountCatalog.isValidAmount(amount)) throw RedPacketError.AmountOutOfRange(amount)
        val balance = currencyService.ensureUserWallet(now).coinBalance
        if (balance < amount) throw RedPacketError.InsufficientBalance(need = amount, have = balance)

        val cleanedBlessing = cappedBlessing(blessing)
        val recordUuid = UUID.randomUUID().toString()

        // 扣用户钱包（spend / redPacket 流水 relatedId=record.uuid）。同一事务内余额已校验，null 为防御性。
        currencyService.spendCoinsFromUser(
            amount = amount,
            category = CurrencyTransactionCategory.RED_PACKET,
            note = "发给${toCharacterName}的红包",
            relatedId = recordUuid,
            now = now,
        ) ?: run {
            Log.w(TAG, "用户发红包·扣币失败(竞态) → $toCharacterName amount=$amount have=$balance")
            throw RedPacketError.InsufficientBalance(need = amount, have = balance)
        }

        val message = insertRedPacketMessage(recordUuid, conversationUuid, "user", amount, cleanedBlessing, festivalId, now)
        val record = RedPacketRecordEntity(
            uuid = recordUuid,
            messageUuid = message.messageUUID,
            conversationUuid = conversationUuid,
            senderType = WalletOwnerType.USER.raw,
            senderCharacterUUID = "",
            receiverType = WalletOwnerType.CHARACTER.raw,
            receiverCharacterUUID = toCharacterUuid,
            amount = amount,
            blessingText = cleanedBlessing,
            festivalId = festivalId,
            status = RedPacketStatus.PENDING.raw,
            createdAt = now,
            expiresAt = now + RedPacketRecordEntity.DEFAULT_EXPIRATION_MS,
        )
        redPacketDao.insert(record)
        Log.i(TAG, "用户发红包 → $toCharacterName amount=$amount record=$recordUuid")
        SendOutcome(message, record)
    }

    /**
     * 角色主动发送红包给用户（1:1 iOS `sendFromCharacter`，对称 sendFromUser 反向）。
     * 扣角色钱包，role=assistant，sender=character / receiver=user。供 [com.situ.aichat.gift.ProactiveGiftExecutor] 接通。
     */
    suspend fun sendFromCharacter(
        characterUuid: String,
        amount: Int,
        blessing: String = "",
        festivalId: String? = null,
        conversationUuid: String,
        now: Long = System.currentTimeMillis(),
    ): SendOutcome = db.withTransaction {
        if (!RedPacketAmountCatalog.isValidAmount(amount)) throw RedPacketError.AmountOutOfRange(amount)
        val balance = currencyService.walletForCharacter(characterUuid, now).coinBalance
        if (balance < amount) throw RedPacketError.InsufficientBalance(need = amount, have = balance)

        val cleanedBlessing = cappedBlessing(blessing)
        val recordUuid = UUID.randomUUID().toString()

        currencyService.spendCoinsFromCharacter(
            characterUuid = characterUuid,
            amount = amount,
            category = CurrencyTransactionCategory.RED_PACKET,
            note = "给用户发的红包",
            relatedId = recordUuid,
            now = now,
        ) ?: run {
            Log.w(TAG, "角色发红包·扣币失败(竞态) character=$characterUuid amount=$amount have=$balance")
            throw RedPacketError.InsufficientBalance(need = amount, have = balance)
        }

        val message = insertRedPacketMessage(recordUuid, conversationUuid, "assistant", amount, cleanedBlessing, festivalId, now)
        val record = RedPacketRecordEntity(
            uuid = recordUuid,
            messageUuid = message.messageUUID,
            conversationUuid = conversationUuid,
            senderType = WalletOwnerType.CHARACTER.raw,
            senderCharacterUUID = characterUuid,
            receiverType = WalletOwnerType.USER.raw,
            receiverCharacterUUID = "",
            amount = amount,
            blessingText = cleanedBlessing,
            festivalId = festivalId,
            status = RedPacketStatus.PENDING.raw,
            createdAt = now,
            expiresAt = now + RedPacketRecordEntity.DEFAULT_EXPIRATION_MS,
        )
        redPacketDao.insert(record)
        Log.i(TAG, "角色发红包 → 用户 character=$characterUuid amount=$amount record=$recordUuid")
        SendOutcome(message, record)
    }

    // ── 接受出口 ──

    /**
     * 拆开/接受红包（用户点击拆开 or 角色 LLM 决定收下，1:1 iOS `acceptRedPacket`）。
     * guard pending → 按 receiverType 转账到接收方钱包（earn）→ status=accepted+resolvedAt → 系统事件消息。
     */
    suspend fun acceptRedPacket(recordUuid: String, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val record = redPacketDao.getByUuid(recordUuid) ?: throw RedPacketError.RecordNotFound(recordUuid)
            guardPending(record)

            val note = "收到红包"
            when (WalletOwnerType.fromRaw(record.receiverType)) {
                WalletOwnerType.USER ->
                    currencyService.addCoinsToUser(record.amount, CurrencyTransactionCategory.RED_PACKET, note, record.uuid, now)
                WalletOwnerType.CHARACTER -> {
                    val receiverUuid = record.receiverCharacterUUID
                    if (receiverUuid.isEmpty()) throw RedPacketError.ReceiverMissing
                    characterRepo.get(receiverUuid) ?: throw RedPacketError.ReceiverMissing
                    currencyService.addCoinsToCharacter(receiverUuid, record.amount, CurrencyTransactionCategory.RED_PACKET, note, record.uuid, now)
                }
            }

            val resolved = record.copy(status = RedPacketStatus.ACCEPTED.raw, resolvedAt = now)
            redPacketDao.update(resolved)
            insertSystemEventMessage(SystemEventType.RED_PACKET_ACCEPTED, resolved, now)
            Log.i(TAG, "红包被收下 record=$recordUuid amount=${record.amount} receiver=${record.receiverType}")
        }
    }

    // ── 拒收出口 ──

    /**
     * 拒收红包（角色 LLM 决策「不收」，1:1 iOS `rejectRedPacket`）。
     * guard pending → 按 **senderType** 退钱回发送方 → status=rejected+rejectionReason(截 30 字)+resolvedAt → 系统事件消息。
     */
    suspend fun rejectRedPacket(recordUuid: String, reason: String = "", now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val record = redPacketDao.getByUuid(recordUuid) ?: throw RedPacketError.RecordNotFound(recordUuid)
            guardPending(record)

            val cappedReason = cappedRejectionReason(reason)
            val note = if (cappedReason.isEmpty()) "红包被退回" else "红包被退回:$cappedReason"
            refundToSender(record, note, now)

            val resolved = record.copy(status = RedPacketStatus.REJECTED.raw, rejectionReason = cappedReason, resolvedAt = now)
            redPacketDao.update(resolved)
            insertSystemEventMessage(SystemEventType.RED_PACKET_REJECTED, resolved, now)
            Log.i(TAG, "红包被拒收 record=$recordUuid amount=${record.amount} reason=$cappedReason")
        }
    }

    // ── 过期出口 ──

    /**
     * 过期处理（24h 未拆，9.3b 扫描调用，1:1 iOS `expireRedPacket`，对称 reject 但 status=expired、不写 rejectionReason）。
     */
    suspend fun expireRedPacket(recordUuid: String, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val record = redPacketDao.getByUuid(recordUuid) ?: throw RedPacketError.RecordNotFound(recordUuid)
            guardPending(record)

            refundToSender(record, "红包 24 小时未拆,自动退回", now)

            val resolved = record.copy(status = RedPacketStatus.EXPIRED.raw, resolvedAt = now)
            redPacketDao.update(resolved)
            insertSystemEventMessage(SystemEventType.RED_PACKET_EXPIRED, resolved, now)
            Log.i(TAG, "红包过期退回 record=$recordUuid amount=${record.amount}")
        }
    }

    // ── 内部辅助 ──

    /** 退钱回**发送方**钱包（reject/expire 共用，1:1 iOS 两法的 senderType 退钱分支）。 */
    private suspend fun refundToSender(record: RedPacketRecordEntity, note: String, now: Long) {
        when (WalletOwnerType.fromRaw(record.senderType)) {
            WalletOwnerType.USER ->
                currencyService.addCoinsToUser(record.amount, CurrencyTransactionCategory.RED_PACKET, note, record.uuid, now)
            WalletOwnerType.CHARACTER -> {
                val senderUuid = record.senderCharacterUUID
                if (senderUuid.isEmpty()) throw RedPacketError.ReceiverMissing
                characterRepo.get(senderUuid) ?: throw RedPacketError.ReceiverMissing
                currencyService.addCoinsToCharacter(senderUuid, record.amount, CurrencyTransactionCategory.RED_PACKET, note, record.uuid, now)
            }
        }
    }

    /** 构造 RedPacketData + 插入 RED_PACKET 消息，返回该消息（messageUuid 供 Record 回填）。 */
    private suspend fun insertRedPacketMessage(
        recordUuid: String,
        conversationUuid: String,
        roleRaw: String,
        amount: Int,
        blessing: String,
        festivalId: String?,
        now: Long,
    ): MessageEntity {
        val data = RedPacketData(
            type = "red_packet",
            recordUUID = recordUuid,
            amount = amount,
            blessingText = blessing,
            festivalId = festivalId,
        )
        val message = MessageEntity(
            messageUUID = UUID.randomUUID().toString(),
            conversationUuid = conversationUuid,
            roleRaw = roleRaw,
            content = RedPacketJson.encode(data),
            timestamp = now,
            messageKindRaw = MessageKind.RED_PACKET.raw,
        )
        messageRepo.upsert(message)
        return message
    }

    /**
     * 插入系统事件消息（1:1 iOS `insertSystemEventMessage`，Sub D.3）：role=system + SYSTEM_EVENT_CARD。
     * 查不到 conversation/character 时 **warn + return**，不抛错、不阻断状态机+退钱主路径。仅 insert，由外层事务统一落盘。
     */
    private suspend fun insertSystemEventMessage(eventType: SystemEventType, record: RedPacketRecordEntity, now: Long) {
        val conversation = conversationRepo.get(record.conversationUuid)
        if (conversation == null) {
            Log.w(TAG, "系统事件消息 insert 失败 · 找不到 conversation for record ${record.uuid}")
            return
        }
        val character = characterRepo.get(conversation.characterUuid)
        if (character == null) {
            Log.w(TAG, "系统事件消息 insert 失败 · conversation.character 为空 for record ${record.uuid}")
            return
        }

        val senderRole = if (WalletOwnerType.fromRaw(record.senderType) == WalletOwnerType.USER) {
            RedPacketEventSenderRole.USER
        } else {
            RedPacketEventSenderRole.CHARACTER
        }
        val trimmedBlessing = record.blessingText.trim()
        val trimmedReason = record.rejectionReason.trim()
        val data = makeRedPacketSystemEventData(
            eventType = eventType,
            amount = record.amount,
            blessingText = trimmedBlessing.ifEmpty { null },
            rejectionReason = trimmedReason.ifEmpty { null },
            senderRole = senderRole,
            characterName = character.name,
            timestampMillis = now,
        )
        messageRepo.upsert(
            MessageEntity(
                messageUUID = UUID.randomUUID().toString(),
                conversationUuid = conversation.uuid,
                roleRaw = "system",
                content = SystemEventJson.encode(data),
                timestamp = now,
                messageKindRaw = MessageKind.SYSTEM_EVENT_CARD.raw,
            ),
        )
    }

    private fun guardPending(record: RedPacketRecordEntity) {
        val status = RedPacketStatus.fromRaw(record.status)
        if (status != RedPacketStatus.PENDING) {
            Log.w(TAG, "红包并发二次处理被拦 record=${record.uuid} status=${record.status}")
            throw RedPacketError.AlreadyResolved(status)
        }
    }

    companion object {
        private const val TAG = "RedPacketService"

        /** 祝福文字截断到 80 字（超出 + "…"，1:1 iOS `cappedBlessing`，与 DIY content / llmRepresentation 对齐）。 */
        internal fun cappedBlessing(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            return if (trimmed.length > 80) trimmed.take(80) + "…" else trimmed
        }

        /** 拒收理由截断到 30 字（**纯截断无 …**，1:1 iOS `cappedRejectionReason`，与 DecisionService prompt 约束对齐）。 */
        internal fun cappedRejectionReason(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            return if (trimmed.length > 30) trimmed.take(30) else trimmed
        }
    }
}

/**
 * 红包错误（1:1 iOS `RedPacketError`）。
 * [SaveFailed] 为 iOS save() 失败封装；安卓改 [AppDatabase.withTransaction] 自动回滚，DB 异常通常直接抛 SQLiteException —— 保留此 case 以对齐 iOS 错误面。
 */
sealed class RedPacketError(message: String) : Exception(message) {
    data class InsufficientBalance(val need: Int, val have: Int) :
        RedPacketError("余额不足:需要 $need 金币,当前 $have 金币")

    data class AmountOutOfRange(val amount: Int) :
        RedPacketError("金额 $amount 超出范围 [${RedPacketAmountCatalog.MIN_AMOUNT}, ${RedPacketAmountCatalog.MAX_AMOUNT}]")

    data object ReceiverMissing : RedPacketError("接收方信息缺失")

    data class RecordNotFound(val uuid: String) : RedPacketError("找不到红包记录 $uuid")

    data class AlreadyResolved(val status: RedPacketStatus?) :
        RedPacketError("红包已是终态:${status?.raw},不能重复处理")

    data class SaveFailed(val underlying: Throwable) : RedPacketError("保存失败:${underlying.message}")
}
