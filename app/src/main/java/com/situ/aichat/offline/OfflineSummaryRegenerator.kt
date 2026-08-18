package com.situ.aichat.offline

import com.situ.aichat.util.DateFormatters
import java.time.ZoneId

/**
 * 线下见面摘要的**规则兜底生成器**。1:1 port of iOS `OfflineSummaryRegenerator`。
 *
 * 作用：当 LLM 摘要连续多次失败时，用**纯字符串拼接**生成一段最小可用摘要，写入
 * `CharacterEntity.offlineMeetingMemorySummary`，保证 AI 至少能看到「这次见面的时间/地点/活动/时长/轮数」骨架，
 * 不会完全失忆。
 *
 * 设计原则：
 * - **无 IO**：纯字符串拼接，不发 LLM、不网络、不访问文件系统，必然成功。
 * - **格式与 LLM 版对齐**：段落以 `【见面 · yyyy-MM-dd HH:mm · 地点】` 开头，将来 LLM 能正常生成时
 *   `extractOfflineMeetingMemory` 的 prompt 会识别同日期同地点段落原位更新，不与兜底段落共存。
 * - **保留线索**：一行话包含「多久 + 多少轮」，让 AI 至少能说出「上次我们在公园散步了挺久」。
 *
 * ⚠️ **段落标题 `【见面 · 日期 · 地点】` 紧耦合警告**（spec §2.6）：被 4 处硬编码引用
 * （buildFallbackParagraph 本处 / PromptBuilder 见面记忆禁令段 / PromptModuleService / DirtyMessageDetector
 * 复读识别），改格式必须 4 处同步，否则防复读防线失效。
 *
 * 纯函数 object，日期格式取 [zone] 入参（默认系统时区）便于单测 byte-compare。
 */
object OfflineSummaryRegenerator {

    /**
     * 生成一段兜底摘要段落（不含前后换行，调用方负责拼接），1:1 iOS buildFallbackParagraph。
     *
     * 形如 `【见面 · 2026-04-18 15:30 · 公园】一次你主动约的见面,约1小时20分钟,主要是喝咖啡,共 42 轮对话,整体氛围温暖。`
     * （正文分隔符为**半角逗号** `,`，句末**全角句号** `。`，「共 N 轮对话」数字两侧带空格——逐字对齐 iOS）。
     *
     * @param startMillis 见面开始时间（用于段落标题）
     * @param location 见面地点，空串→「某个地方」
     * @param activity 见面活动，空串→不输出
     * @param durationText 时长文案（如「约1小时20分钟」），来自离场标记 payload
     * @param messageCount 本次见面有效消息轮数（排除 marker）
     * @param finalMood 结束情绪基调（warm/sweet/melancholic/awkward/neutral），null 或非法值→不输出
     * @param initiatedByUser 是否用户主动发起：true→「一次你主动约的见面」；false→「一次 ta 主动约的见面」；
     *   null→「一次见面」（旧数据或无法判定）
     */
    internal fun buildFallbackParagraph(
        startMillis: Long,
        location: String,
        activity: String,
        durationText: String,
        messageCount: Int,
        finalMood: String? = null,
        initiatedByUser: Boolean? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val dateString = DateFormatters.yearMonthDayHourMinute(startMillis, zone)
        val resolvedLocation = location.ifEmpty { "某个地方" }
        val heading = "【见面 · $dateString · $resolvedLocation】"
        return "$heading\n${buildFallbackBody(durationText, activity, messageCount, finalMood, initiatedByUser)}"
    }

    /**
     * 兜底摘要**正文**（无标题行·= [buildFallbackParagraph] 去掉标题行的正文部分）——梦剧场 B 部 v2 行存兜底路
     * （图纸 §3.5）用它写 row.summary（标题行由 [OfflineMeetingMemoryRenderer] 拼装）。
     *
     * ⚠️ §9 禁改：顺序（发起方 → 时长 → 活动 → 轮数 → 情绪）、分隔符**半角逗号** `,`、句末**全角句号** `。`、
     * 「共 N 轮对话」数字两侧空格、情绪映射（[localizedMoodLabel]）——**措辞与标点逐字保留**。
     */
    internal fun buildFallbackBody(
        durationText: String,
        activity: String,
        messageCount: Int,
        finalMood: String? = null,
        initiatedByUser: Boolean? = null,
    ): String {
        val bodyParts = mutableListOf<String>()
        bodyParts.add(
            when (initiatedByUser) {
                null -> "一次见面"
                true -> "一次你主动约的见面"
                false -> "一次 ta 主动约的见面"
            },
        )
        if (durationText.isNotEmpty()) bodyParts.add(durationText)
        if (activity.isNotEmpty()) bodyParts.add("主要是$activity")
        if (messageCount > 0) bodyParts.add("共 $messageCount 轮对话")
        localizedMoodLabel(finalMood)?.let { bodyParts.add("整体氛围$it") }
        return bodyParts.joinToString(",") + "。"
    }

    /** 把 finalMood raw 值映射为中文口语描述，非法/空→null（1:1 iOS localizedMoodLabel；先 lowercase）。 */
    private fun localizedMoodLabel(finalMood: String?): String? {
        val raw = finalMood?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (raw) {
            "warm" -> "温暖"
            "sweet" -> "甜蜜"
            "melancholic" -> "微涩"
            "awkward" -> "微妙"
            "neutral" -> "平淡"
            else -> null // 未知枚举值（如将来扩展）不输出，避免乱码
        }
    }

    // MARK: - 软上限合并（M2）

    /** 一条被合并的见面元数据（用于生成「【早期见面合并】…」单行摘要），1:1 iOS MergeEntry。 */
    data class MergeEntry(val startMillis: Long, val location: String, val activity: String)

    /**
     * 生成「早期见面合并行」，1:1 iOS buildMergedEarlyMeetingsLine。形如：
     * `【早期见面合并】共 3 次: 4/1 公园 · 散步; 4/5 咖啡馆 · 喝咖啡; 4/10 商场 · 购物`
     * （日期 `M/d` 无前导零；地点空→「某地」；活动空→省略「 · 活动」；条目间「; 」；「共 N 次: 」带空格 + 半角冒号）。
     */
    internal fun buildMergedEarlyMeetingsLine(entries: List<MergeEntry>, zone: ZoneId = ZoneId.systemDefault()): String {
        if (entries.isEmpty()) return ""
        val parts = entries.map { entry ->
            val dateStr = DateFormatters.monthDay(entry.startMillis, zone)
            val loc = entry.location.ifEmpty { "某地" }
            if (entry.activity.isEmpty()) "$dateStr $loc" else "$dateStr $loc · ${entry.activity}"
        }
        return "【早期见面合并】共 ${entries.size} 次: ${parts.joinToString("; ")}"
    }

    /**
     * 生成某次见面对应的段落标题（用于在 summary 里精确定位），1:1 iOS paragraphTitle。
     * 返回 `【见面 · yyyy-MM-dd HH:mm · 地点】`；地点空→「某个地方」。
     */
    internal fun paragraphTitle(startMillis: Long, location: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val dateStr = DateFormatters.yearMonthDayHourMinute(startMillis, zone)
        val loc = location.ifEmpty { "某个地方" }
        return "【见面 · $dateStr · $loc】"
    }

    /**
     * 从 summary 删除指定标题的段落，1:1 iOS removeParagraphs。
     * 按 `\n\n` 拆段，**首行**（trim 空格后）精确等于目标标题之一→整段删除。
     */
    internal fun removeParagraphs(summary: String, titles: Set<String>): String {
        if (titles.isEmpty()) return summary
        val paragraphs = summary.split("\n\n")
        val kept = paragraphs.filter { paragraph ->
            val firstLine = paragraph.split("\n").firstOrNull() ?: ""
            firstLine.trim() !in titles
        }
        return kept.joinToString("\n\n")
    }
}
