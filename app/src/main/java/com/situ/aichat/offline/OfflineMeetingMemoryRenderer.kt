package com.situ.aichat.offline

import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.util.StringListJson
import java.time.ZoneId

/**
 * 结构化见面回忆行 → 注入文本渲染（梦剧场 B 部·图纸 §3.6）。**注入端唯一产出口**：标题行
 * `【见面 · yyyy-MM-dd HH:mm · 地点】` 与旧 [OfflineSummaryRegenerator] 逐字节一致（含「 · 」两侧空格·地点空→「某地」），
 * 对 LLM 与 [com.situ.aichat.prompt.DirtyMessageDetector] 零变化。纯函数、无 IO，便于 T1 逐字比对。
 *
 * 三组（§3.6.1）：legacy 行（原样段落）→ 存档组（meeting 行除最新 [injectCount] 次外的全部·合并为一行索引）→
 * 完整组（最新 injectCount 次·老→新）。总长 > [budget] 时把完整组最老一次降级进存档行，循环到 ≤budget 或只剩 1 次完整。
 */
internal object OfflineMeetingMemoryRenderer {

    fun render(
        rows: List<OfflineMeetingMemoryEntity>,
        injectCount: Int,
        budget: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val sorted = rows.sortedBy { it.startedAtMillis }
        val legacyParagraphs = sorted
            .filter { it.kindRaw == "legacy" }
            .map { it.summary.trim() }
            .filter { it.isNotEmpty() }
        val meetings = sorted.filter { it.kindRaw == "meeting" }

        // 完整组数量：从 min(injectCount, size) 起，超预算逐次降级到存档（最后 1 次永不降级）。
        var fullCount = injectCount.coerceIn(0, meetings.size)
        while (true) {
            val archive = if (fullCount >= meetings.size) emptyList() else meetings.dropLast(fullCount)
            val full = meetings.takeLast(fullCount)

            val paragraphs = mutableListOf<String>()
            paragraphs.addAll(legacyParagraphs)
            if (archive.isNotEmpty()) {
                val mergedLine = OfflineSummaryRegenerator.buildMergedEarlyMeetingsLine(
                    archive.map { OfflineSummaryRegenerator.MergeEntry(it.startedAtMillis, it.location, it.activity) },
                    zone,
                )
                if (mergedLine.isNotEmpty()) paragraphs.add(mergedLine)
            }
            for (m in full) paragraphs.add(renderMeeting(m, zone))

            val out = paragraphs.joinToString("\n\n")
            if (out.length <= budget || fullCount <= 1) return out
            fullCount-- // 降级完整组最老一次进存档行
        }
    }

    /**
     * 完整组一次见面的段落：`【见面 · yyyy-MM-dd HH:mm · 地点】\n{summary}` +（非空时）难忘行。
     * **约定不再在此呈现**——改由【我们的约定】账本块单源展示（记忆改造一期·一事一形态·图纸 §3.9）；
     * [OfflineMeetingMemoryEntity.promisesJson] 字段保留供三期 UI 与历史回填（[com.situ.aichat.promise.PromiseLedgerService]）。
     */
    private fun renderMeeting(m: OfflineMeetingMemoryEntity, zone: ZoneId): String {
        val dateStr = DateFormatters.yearMonthDayHourMinute(m.startedAtMillis, zone)
        val loc = m.location.ifEmpty { "某地" }
        val title = "【见面 · $dateStr · $loc】"
        val highlights = StringListJson.decode(m.highlightsJson)
        val extra = buildString {
            if (highlights.isNotEmpty()) append("难忘：${highlights.joinToString("；")}。")
        }
        return if (extra.isEmpty()) "$title\n${m.summary}" else "$title\n${m.summary}\n$extra"
    }
}
