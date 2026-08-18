package com.situ.aichat.offline

import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 旧大字段 `CharacterEntity.offlineMeetingMemorySummary`（blob）→ 结构化行的**一次性解析**（梦剧场 B 部·图纸 §3.3）。
 * 懒播种（[com.situ.aichat.data.repository.OfflineMeetingMemoryRepository.ensureSeeded]）时调；纯函数便于 T1 逐字比对。
 *
 * 规格：按 `【见面 · yyyy-MM-dd HH:mm · 地点】` 切段——组1 日期可解析 → `kind="meeting"`（startedAt=日期·location=组2·
 * summary=标题行后到下一个「【」或文末）；日期不可解析 → 整段归 `kind="legacy"` 逐字。段外残余文本（含【早期见面合并】行、
 * 散句）每个非空段落各成一行 `kind="legacy"`（summary=原文逐字·startedAt=0·排最前）。source 一律 "legacy"。
 * uuid 单调序号派生（**确定性**：同一 blob 重解析产生相同 uuid → upsert REPLACE 容忍并发双播种）。
 */
internal object OfflineMeetingLegacyParser {

    private val MEETING_TITLE_REGEX = Regex("""【见面\s*·\s*([^·】]+?)\s*·\s*([^】]*)】""")
    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun parse(
        characterUuid: String,
        blob: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<OfflineMeetingMemoryEntity> {
        if (blob.isBlank()) return emptyList()
        val rows = mutableListOf<OfflineMeetingMemoryEntity>()
        var seq = 0
        fun nextUuid(): String = "offline_meeting_seed:$characterUuid:${seq++}"
        fun addLegacyParagraphs(text: String) {
            for (para in text.split("\n\n")) {
                val t = para.trim()
                if (t.isNotEmpty()) rows.add(legacyRow(nextUuid(), characterUuid, t))
            }
        }

        val titles = MEETING_TITLE_REGEX.findAll(blob).toList()
        if (titles.isEmpty()) {
            addLegacyParagraphs(blob)
            return rows
        }

        var cursor = 0
        for (m in titles) {
            val titleStart = m.range.first
            val titleEnd = m.range.last // 】的 index（含）
            // 本标题之前的段外残余 → legacy 段落
            if (titleStart > cursor) addLegacyParagraphs(blob.substring(cursor, titleStart))
            // body：标题行后 → 下一个「【」或文末
            val nextBracket = blob.indexOf('【', titleEnd + 1)
            val bodyEnd = if (nextBracket == -1) blob.length else nextBracket
            val dateStr = m.groupValues[1].trim()
            val loc = m.groupValues[2].trim()
            val startedAt = parseDate(dateStr, zone)
            if (startedAt != null) {
                val body = blob.substring(titleEnd + 1, bodyEnd).trim()
                rows.add(meetingRow(nextUuid(), characterUuid, startedAt, loc, body))
            } else {
                // 日期不可解析 → 整段（标题+正文）逐字归 legacy
                val segment = blob.substring(titleStart, bodyEnd).trim()
                if (segment.isNotEmpty()) rows.add(legacyRow(nextUuid(), characterUuid, segment))
            }
            cursor = bodyEnd
        }
        if (cursor < blob.length) addLegacyParagraphs(blob.substring(cursor))
        return rows
    }

    private fun parseDate(s: String, zone: ZoneId): Long? = runCatching {
        LocalDateTime.parse(s, DATE_FORMAT).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    private fun meetingRow(uuid: String, characterUuid: String, startedAt: Long, location: String, summary: String) =
        OfflineMeetingMemoryEntity(
            uuid = uuid,
            characterUuid = characterUuid,
            sessionId = "",
            kindRaw = "meeting",
            startedAtMillis = startedAt,
            endedAtMillis = 0,
            location = location,
            summary = summary,
            sourceRaw = "legacy",
            createdAtMillis = startedAt,
            updatedAtMillis = startedAt,
        )

    private fun legacyRow(uuid: String, characterUuid: String, summary: String) =
        OfflineMeetingMemoryEntity(
            uuid = uuid,
            characterUuid = characterUuid,
            sessionId = "",
            kindRaw = "legacy",
            startedAtMillis = 0,
            endedAtMillis = 0,
            summary = summary,
            sourceRaw = "legacy",
            createdAtMillis = 0,
            updatedAtMillis = 0,
        )
}
