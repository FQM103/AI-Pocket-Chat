package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 结局档案「足迹 + 摘句」派生数据（ST8·契约 §5 / D5）。
 *
 * 纯逻辑：从故事 + 全量章节算出档案卡 / 分享长图共用的展示素材（章数 / 选择数 / 天数 / 起讫时刻 / 末章摘句）。
 * 不碰数据模型、不落库、不含本地化文案——章数/天数等原子值由 UI 层套字符串模板。
 */
data class StoryArchiveDigest(
    val chapterCount: Int,
    val choiceCount: Int,
    /** 起讫按日历日差（同日=0）。 */
    val dayCount: Int,
    val startMillis: Long,
    val endMillis: Long,
    /** 末章末段摘句（剥标签·≤60 字），空正文时为空串（UI 决定是否隐藏摘句区）。 */
    val quote: String,
    /** 结局类型 raw（[StoryEntity.finalEndingType]·null=自然/满章结局→徽章走中性）。 */
    val endingType: String?,
)

object StoryArchiveDigestBuilder {
    /** 末章摘句最大字数（D5：自动取末章末段 ~60 字）。 */
    const val QUOTE_MAX_CHARS = 60

    /** 句末标点（摘句回取整句时的边界）。 */
    private const val SENTENCE_ENDS = "。！？!?…"

    /**
     * 从故事 + 全量章节装配档案摘要。
     *
     * @param chapters 该故事全量章节（任意顺序·内部按 chapterNumber 升序整理）
     * @param zone 设备本地时区（天数按日历日算·测试可注入固定时区）
     */
    fun build(
        story: StoryEntity,
        chapters: List<StoryChapterEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): StoryArchiveDigest {
        val ordered = chapters.sortedBy { it.chapterNumber }
        val startMillis = story.createdAt
        // 完结≈末章创建时；无缓存/无章节时回落 updatedAt，保证非空。
        val endMillis = story.cachedLatestChapterCreatedAt
            ?: ordered.lastOrNull()?.createdAt
            ?: story.updatedAt
        return StoryArchiveDigest(
            chapterCount = if (ordered.isNotEmpty()) ordered.size else (story.cachedLatestChapterNumber ?: story.cachedChapterCount),
            choiceCount = ordered.count { it.userChoice != null },
            dayCount = dayCount(startMillis, endMillis, zone),
            startMillis = startMillis,
            endMillis = endMillis,
            quote = extractQuote(ordered.lastOrNull()?.content.orEmpty()),
            endingType = story.finalEndingType,
        )
    }

    /** 起讫按日历日差（同日 → 0；异常/负值钳 0）。 */
    internal fun dayCount(startMillis: Long, endMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        runCatching {
            val start = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
            val end = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
            ChronoUnit.DAYS.between(start, end).coerceAtLeast(0).toInt()
        }.getOrDefault(0)

    /**
     * 末章末段摘句（D5）：剥沉浸标签 → 取最后一个非空段落 → 超 [maxChars] 字则从末尾回取一句完整话
     * （在句末标点后起头，且留够半数字才掐半句，否则整段末尾），空正文 → 空串。纯逻辑，单测反推。
     */
    internal fun extractQuote(rawLastContent: String, maxChars: Int = QUOTE_MAX_CHARS): String {
        val clean = StoryTextSanitizer.sanitize(rawLastContent)
        if (clean.isBlank()) return ""
        val lastPara = clean.split('\n').map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return ""
        if (lastPara.length <= maxChars) return lastPara
        val tail = lastPara.takeLast(maxChars)
        val cut = tail.indexOfFirst { it in SENTENCE_ENDS }
        return if (cut in 0 until tail.length - 1 && tail.length - cut - 1 >= maxChars / 2) {
            tail.substring(cut + 1).trimStart()
        } else {
            tail
        }
    }
}
