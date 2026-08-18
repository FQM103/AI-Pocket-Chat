package com.situ.aichat.offline

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.model.OfflineInviteJson
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.offline.OfflineSummaryRegenerator.MergeEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单次线下见面的结构化数据（轻量值类型，供见面回忆 UI 展示，1:1 iOS `OfflineMeetingSession`）。
 */
data class OfflineMeetingSession(
    val id: String,
    val location: String,
    val activity: String,
    val startMillis: Long,
    val durationText: String,
    val finalMood: String?,            // warm / sweet / melancholic / awkward / neutral
    val summaryText: String?,          // 从见面记忆正则提取的摘要
    val conversationUuid: String,      // 跳转 OfflineReviewView 用
    val initiatedByUser: Boolean,      // true = 用户经入口发起；false = 角色邀约发起
    val usedFallbackSummary: Boolean,  // 摘要是否由规则兜底生成（UI 显示「简版」+ 可手动重试）
)

/** 入场标记行（已解析地点/活动），[OfflineMeetingSessionExtractor.assembleSessions] 用。 */
internal data class OfflineStartMarkerRow(
    val sessionId: String,
    val conversationUuid: String,
    val startMillis: Long,
    val location: String,
    val activity: String,
)

/** 离场标记行（时长）。 */
internal data class OfflineEndMarkerRow(val sessionId: String, val durationText: String, val timestamp: Long)

/** 结束确认卡行（finalMood）。 */
internal data class OfflineEndCardRow(val sessionId: String, val finalMood: String?, val timestamp: Long)

/** 见面摘要正则提取的条目（1:1 iOS `MeetingSummaryEntry`）。 */
internal data class MeetingSummaryEntry(val dateString: String, val location: String, val text: String)

/**
 * 线下见面会话的**元数据提取**（10.2d 见面摘要兜底用，对齐 iOS `OfflineMeetingSessionExtractor`）。
 *
 * 本块只移植**摘要兜底所需的单 session 子集**（兜底段落要的地点/活动/起始时间/情绪/发起方 + 软上限合并要的
 * MergeEntry）。完整会话列表 + 摘要正则提取（见面回忆 UI）留到 10.2e。
 *
 * 数据源：入场标记（地点/活动/起始时间）、结束确认卡（finalMood）、邀约卡（判发起方）——均按 [conversationUuid]
 * 内查（fallback 列表是会话级字段，其 sessionId 都属本会话）。发起方判定 [isCharacterInitiated] 抽为纯函数便于单测。
 */
@Singleton
class OfflineMeetingSessionExtractor @Inject constructor(
    private val messageRepo: MessageRepository,
    private val conversationRepo: ConversationRepository,
) {

    /** 见面摘要兜底所需的单 session 元数据（1:1 iOS FallbackMetadata）。 */
    data class FallbackMetadata(
        val startMillis: Long,
        val location: String,
        val activity: String,
        val finalMood: String?,
        val initiatedByUser: Boolean?,
    )

    /**
     * 为指定 [sessionId] 提取兜底元数据（1:1 iOS extractFallbackMetadata）。
     * 入场标记缺失/无法解析（该 session 不在 iOS extractSessions 列表里）→ 返回占位
     * （startMillis=[now]，location/activity 空，finalMood/initiatedByUser=null）。
     */
    suspend fun extractFallbackMetadata(
        conversationUuid: String,
        sessionId: String,
        now: Long = System.currentTimeMillis(),
    ): FallbackMetadata {
        val sessionMessages = messageRepo.offlineSessionMessages(conversationUuid, sessionId)
        val startMsg = sessionMessages.firstOrNull { it.messageKindRaw == MessageKind.OFFLINE_MARKER_START.raw }
        val startPayload = startMsg?.let { OfflineMarkerStartPayload.parse(it.content) }
        if (startMsg == null || startPayload == null) {
            return FallbackMetadata(now, "", "", null, null)
        }

        // 情绪：从结束确认卡（offline_end_card，标 isOfflineMode+sessionId → 在 offlineSessionMessages 内）读 finalMood；
        // 无结束卡（用户从导航栏直接结束，未经 AI 结束卡）→ null。**取最后一张**（升序列表 lastOrNull = 最近）：
        // 一次见面可有多张结束卡（AI 提议结束→用户「再待一会儿」→再次提议），iOS endCards[sid]=msg 无条件覆盖 = last-wins，
        // 最近一张才是真正结束时的情绪基调。
        val finalMood = sessionMessages
            .lastOrNull { it.messageKindRaw == MessageKind.OFFLINE_END_CARD.raw }
            ?.let { OfflineInviteJson.parse(it.content)?.finalMood }

        // 发起方：入场标记前是否有已接受的角色邀约卡。邀约卡 isOfflineMode=false，单独按 kind 查。
        val cards = messageRepo.messagesByKind(conversationUuid, MessageKind.OFFLINE_INVITE_CARD.raw)
            .map { it.timestamp to OfflineInviteJson.parse(it.content)?.responded }
        val startTimestamps = messageRepo.messagesByKind(conversationUuid, MessageKind.OFFLINE_MARKER_START.raw)
            .map { it.timestamp }
        val initiatedByUser = !isCharacterInitiated(startMsg.timestamp, startTimestamps, cards)

        return FallbackMetadata(startMsg.timestamp, startPayload.location, startPayload.activity, finalMood, initiatedByUser)
    }

    /**
     * 为 fallback 列表里的 [sessionId] 提取合并元数据（软上限合并用）。入场标记缺失 → null（调用方用占位）。
     */
    suspend fun extractMergeEntry(conversationUuid: String, sessionId: String): MergeEntry? {
        val sessionMessages = messageRepo.offlineSessionMessages(conversationUuid, sessionId)
        val startMsg = sessionMessages.firstOrNull { it.messageKindRaw == MessageKind.OFFLINE_MARKER_START.raw } ?: return null
        val payload = OfflineMarkerStartPayload.parse(startMsg.content) ?: return null
        return MergeEntry(startMsg.timestamp, payload.location, payload.activity)
    }

    /**
     * 提取角色的所有线下见面会话，按日期倒序（1:1 iOS `extractSessions`，供见面回忆 UI）。
     *
     * 遍历角色所有会话，收集入场/离场标记 + 结束确认卡 + 邀约卡，按 sessionId 分组组装；摘要从
     * offlineMeetingMemorySummary（旧数据再兜底 memorySummary）正则提取并按日期/地点匹配；fallback 列表
     * 标「简版」。取数后委托纯函数 [assembleSessions]（分组/匹配/排序可单测）。
     */
    suspend fun extractSessions(character: CharacterEntity): List<OfflineMeetingSession> {
        val conversations = conversationRepo.getByCharacter(character.uuid)

        val startRows = mutableListOf<OfflineStartMarkerRow>()
        val endRows = mutableListOf<OfflineEndMarkerRow>()
        val cardRows = mutableListOf<OfflineEndCardRow>()
        val convsWithSessions = mutableSetOf<String>()

        for (conv in conversations) {
            // 入场标记（地点/活动/起始）——只收 sessionId 非空且 payload 可解析的（= iOS guard）。
            for (m in messageRepo.messagesByKind(conv.uuid, MessageKind.OFFLINE_MARKER_START.raw)) {
                val sid = m.offlineSessionId
                if (sid.isNullOrEmpty()) continue
                val payload = OfflineMarkerStartPayload.parse(m.content) ?: continue
                startRows.add(OfflineStartMarkerRow(sid, conv.uuid, m.timestamp, payload.location, payload.activity))
                convsWithSessions.add(conv.uuid)
            }
            // 离场标记（时长）。
            for (m in messageRepo.messagesByKind(conv.uuid, MessageKind.OFFLINE_MARKER_END.raw)) {
                val sid = m.offlineSessionId
                if (sid.isNullOrEmpty()) continue
                val payload = OfflineMarkerEndPayload.parse(m.content) ?: continue
                endRows.add(OfflineEndMarkerRow(sid, payload.durationText, m.timestamp))
            }
            // 结束确认卡（finalMood）。
            for (m in messageRepo.messagesByKind(conv.uuid, MessageKind.OFFLINE_END_CARD.raw)) {
                val sid = m.offlineSessionId
                if (sid.isNullOrEmpty()) continue
                cardRows.add(OfflineEndCardRow(sid, OfflineInviteJson.parse(m.content)?.finalMood, m.timestamp))
            }
        }

        // 邀约卡（判发起方）：只查有 session 的会话，邀约卡 isOfflineMode=false 单独按 kind 查。
        val inviteByConv = mutableMapOf<String, List<Pair<Long, String?>>>()
        for (conv in conversations) {
            if (conv.uuid !in convsWithSessions) continue
            val invites = messageRepo.messagesByKind(conv.uuid, MessageKind.OFFLINE_INVITE_CARD.raw)
            if (invites.isNotEmpty()) {
                inviteByConv[conv.uuid] = invites.map { it.timestamp to OfflineInviteJson.parse(it.content)?.responded }
            }
        }

        // 摘要：见面记忆独立字段优先，旧数据兜底从 memorySummary 读。
        val summaryEntries = parseMeetingSummaries(character.offlineMeetingMemorySummary) +
            parseMeetingSummaries(character.memorySummary)

        // fallback session 集合（标「简版」）：所有会话的 CSV 字段合并。
        val fallbackSessionIds = conversations
            .flatMap { it.offlineSummaryFallbackSessionIds.split(",").map(String::trim).filter(String::isNotEmpty) }
            .toSet()

        return assembleSessions(startRows, endRows, cardRows, inviteByConv, summaryEntries, fallbackSessionIds)
    }

    /** 统计角色线下见面次数（轻量版，只数不同 sessionId，1:1 iOS `countSessions`）。 */
    suspend fun countSessions(character: CharacterEntity): Int {
        val conversations = conversationRepo.getByCharacter(character.uuid)
        val sessionIds = mutableSetOf<String>()
        for (conv in conversations) {
            for (m in messageRepo.messagesByKind(conv.uuid, MessageKind.OFFLINE_MARKER_START.raw)) {
                val sid = m.offlineSessionId
                if (!sid.isNullOrEmpty()) sessionIds.add(sid)
            }
        }
        return sessionIds.size
    }

    companion object {
        /**
         * 纯决策（1:1 iOS isCharacterInitiated）：同对话中，本次见面入场标记之前（且上一次见面开始之后）是否存在
         * 一张已接受（responded=="accepted"）的邀约卡。存在 → 角色发起；不存在 → 用户经 +菜单手动发起。
         *
         * @param startMillis 本次见面入场标记时间
         * @param allMarkerStartTimestamps 同对话所有入场标记时间戳（定上一次见面下界，避免误匹配更早 session 的邀约）
         * @param inviteCards 同对话所有邀约卡 (时间戳, responded)，**升序**
         */
        internal fun isCharacterInitiated(
            startMillis: Long,
            allMarkerStartTimestamps: List<Long>,
            inviteCards: List<Pair<Long, String?>>,
        ): Boolean {
            if (inviteCards.isEmpty()) return false
            // 上一次见面开始时间（搜索下界），无更早 → Long.MIN_VALUE（= iOS .distantPast）。
            val lowerBound = allMarkerStartTimestamps.filter { it < startMillis }.maxOrNull() ?: Long.MIN_VALUE
            for ((ts, responded) in inviteCards.asReversed()) {
                if (ts >= startMillis) continue   // = iOS guard ts < startDate else continue
                if (ts <= lowerBound) break        // = iOS guard ts > lowerBound else break
                if (responded == "accepted") return true
            }
            return false
        }

        // ===== 完整会话列表 + 摘要正则提取（10.2e-4 补全；纯函数，单测反推 iOS）=====

        /** 见面摘要段落正则（1:1 iOS pattern）：【见面 · {日期} · {地点}】+ 正文直到下一【或结尾。 */
        private val meetingSummaryRegex =
            Regex("""【见面\s*·\s*([^·]+?)\s*·\s*([^】]+?)】\s*\n?([\s\S]*?)(?=\n【|$)""")

        /** "yyyy-MM-dd" 日期前缀（findBestSummary 匹配用；与摘要标题 yyyy-MM-dd HH:mm 的前缀一致）。 */
        private val dateOnlyFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        /**
         * 从见面记忆正则提取所有【见面 · 日期 · 地点】段落（1:1 iOS parseMeetingSummaries）；正文空的段落跳过。
         */
        internal fun parseMeetingSummaries(memorySummary: String): List<MeetingSummaryEntry> {
            if (memorySummary.isEmpty()) return emptyList()
            return meetingSummaryRegex.findAll(memorySummary).mapNotNull { match ->
                val dateStr = match.groupValues[1].trim()
                val location = match.groupValues[2].trim()
                val text = match.groupValues[3].trim()
                if (text.isEmpty()) null else MeetingSummaryEntry(dateStr, location, text)
            }.toList()
        }

        /**
         * 为指定见面查找最匹配的摘要（1:1 iOS findBestSummary）：优先地点匹配 + 日期前缀匹配，次优仅日期前缀，
         * 否则 null。
         */
        internal fun findBestSummary(
            startMillis: Long,
            location: String,
            entries: List<MeetingSummaryEntry>,
            zone: ZoneId = ZoneId.systemDefault(),
        ): String? {
            val datePrefix = Instant.ofEpochMilli(startMillis).atZone(zone).format(dateOnlyFormatter)
            entries.firstOrNull { it.location == location && it.dateString.startsWith(datePrefix) }?.let { return it.text }
            entries.firstOrNull { it.dateString.startsWith(datePrefix) }?.let { return it.text }
            return null
        }

        /**
         * 把已取数的标记/卡片/摘要组装成会话列表（1:1 iOS extractSessions 的组装段，纯函数可单测）。
         *
         * 分组：入场标记按 sessionId 取**最早**（= iOS 升序首遇 first-wins）；离场/结束卡取**最晚**
         * （= iOS 升序无条件覆盖 last-wins）。每 session 匹配摘要、判发起方（!isCharacterInitiated）、标 fallback；
         * 最后按起始时间倒序。
         */
        internal fun assembleSessions(
            startMarkers: List<OfflineStartMarkerRow>,
            endMarkers: List<OfflineEndMarkerRow>,
            endCards: List<OfflineEndCardRow>,
            inviteCardsByConv: Map<String, List<Pair<Long, String?>>>,
            summaryEntries: List<MeetingSummaryEntry>,
            fallbackSessionIds: Set<String>,
            zone: ZoneId = ZoneId.systemDefault(),
        ): List<OfflineMeetingSession> {
            val startBySession = startMarkers.groupBy { it.sessionId }
                .mapValues { (_, rows) -> rows.minByOrNull { it.startMillis }!! }
            val durationBySession = endMarkers.groupBy { it.sessionId }
                .mapValues { (_, rows) -> rows.maxByOrNull { it.timestamp }!!.durationText }
            val moodBySession = endCards.groupBy { it.sessionId }
                .mapValues { (_, rows) -> rows.maxByOrNull { it.timestamp }!!.finalMood }
            // 每会话内所有起始时间（升序），定 isCharacterInitiated 的下界（上一次见面）。
            val convStartDates = startBySession.values.groupBy { it.conversationUuid }
                .mapValues { (_, rows) -> rows.map { it.startMillis }.sorted() }

            return startBySession.map { (sessionId, start) ->
                val initiatedByUser = !isCharacterInitiated(
                    start.startMillis,
                    convStartDates[start.conversationUuid] ?: emptyList(),
                    inviteCardsByConv[start.conversationUuid] ?: emptyList(),
                )
                OfflineMeetingSession(
                    id = sessionId,
                    location = start.location,
                    activity = start.activity,
                    startMillis = start.startMillis,
                    durationText = durationBySession[sessionId] ?: "",
                    finalMood = moodBySession[sessionId],
                    summaryText = findBestSummary(start.startMillis, start.location, summaryEntries, zone),
                    conversationUuid = start.conversationUuid,
                    initiatedByUser = initiatedByUser,
                    usedFallbackSummary = sessionId in fallbackSessionIds,
                )
            }.sortedByDescending { it.startMillis }
        }
    }
}
