package com.situ.aichat.profile

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.offline.OfflineMeetingSessionExtractor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把 [CompanionStats] 的 5 项从 Room 聚合出来（1:1 iOS `CompanionStats.init`）。
 * 见面次数复用已建的 [OfflineMeetingSessionExtractor.countSessions]。
 */
@Singleton
class CompanionStatsService @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val offlineExtractor: OfflineMeetingSessionExtractor,
) {
    suspend fun compute(
        character: CharacterEntity,
        nowMillis: Long = System.currentTimeMillis(),
    ): CompanionStats {
        // 字数：每会话最近 100 条（排除 system + 空内容）的内容长度求和，与 iOS 同口径。
        var characterCount = 0
        for (conversation in conversationDao.getByCharacter(character.uuid)) {
            characterCount += messageDao
                .recentNonSystemForConversation(conversation.uuid, 100)
                .sumOf { it.content.length }
        }
        return CompanionStats(
            companionDays = CompanionStatsMath.companionDays(character.creationDate, nowMillis),
            messageCount = messageDao.countNonSystemForCharacter(character.uuid),
            characterCount = characterCount,
            memoryEntryCount = CompanionStatsMath.memoryEntryCount(character.memorySummary),
            offlineMeetingCount = offlineExtractor.countSessions(character),
        )
    }
}
