package com.situ.aichat.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.data.local.entity.WorldBookTimedStateEntity

/**
 * 时效三件套判定（WB3·契约 §2.1）。窗口语义与 WB1 存储约定一致：
 * 当前消息计数 < 锚点 + 时长 ⇒ 窗口内。
 * - **sticky**：窗口内条目无条件续贴（跳过关键词与概率重掷）；
 * - **cooldown**：窗口内不可再触发；**从 sticky 结束后起算**（ST 文档语义：二者同设时冷却接在保持之后）；
 * - **delay**：会话消息数不足 N 前不可激活（无状态，直接比数）。
 */
internal class WorldInfoTimedEffects(
    states: List<WorldBookTimedStateEntity>,
    private val messageCount: Int,
) {
    companion object {
        const val TYPE_STICKY = "sticky"
        const val TYPE_COOLDOWN = "cooldown"
    }

    private val stickyByEntry = states.filter { it.effectType == TYPE_STICKY }.associateBy { it.entryUuid }
    private val cooldownByEntry = states.filter { it.effectType == TYPE_COOLDOWN }.associateBy { it.entryUuid }

    /** 已走完窗口的状态（调用方删除）。 */
    val expired: List<WorldBookTimedStateEntity> =
        states.filter { messageCount >= it.triggeredAtMessageCount + it.durationMessages }

    private fun WorldBookTimedStateEntity.active(): Boolean =
        messageCount >= triggeredAtMessageCount && messageCount < triggeredAtMessageCount + durationMessages

    fun stickyActive(entryUuid: String): Boolean = stickyByEntry[entryUuid]?.active() == true

    fun cooldownBlocked(entryUuid: String): Boolean = cooldownByEntry[entryUuid]?.active() == true

    fun delayBlocked(entry: WorldBookEntryEntity): Boolean =
        entry.delay?.let { messageCount < it } == true

    /** 条目本轮真触发（非续贴）时应落的新时效状态。 */
    fun statesForTrigger(entry: WorldBookEntryEntity, conversationUuid: String): List<WorldBookTimedStateEntity> {
        val sticky = entry.sticky?.takeIf { it > 0 }
        val cooldown = entry.cooldown?.takeIf { it > 0 }
        return buildList {
            if (sticky != null) {
                add(
                    WorldBookTimedStateEntity(
                        conversationUuid = conversationUuid,
                        entryUuid = entry.uuid,
                        effectType = TYPE_STICKY,
                        triggeredAtMessageCount = messageCount,
                        durationMessages = sticky,
                    ),
                )
            }
            if (cooldown != null) {
                add(
                    WorldBookTimedStateEntity(
                        conversationUuid = conversationUuid,
                        entryUuid = entry.uuid,
                        effectType = TYPE_COOLDOWN,
                        triggeredAtMessageCount = messageCount + (sticky ?: 0),
                        durationMessages = cooldown,
                    ),
                )
            }
        }
    }
}
