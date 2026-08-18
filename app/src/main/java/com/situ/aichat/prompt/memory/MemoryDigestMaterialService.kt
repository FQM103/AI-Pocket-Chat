package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.DiaryDao
import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.util.StringListJson
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 消化素材收集与标记（记忆改造一期·部件③/朋友圈/日记消化·图纸 §3.5）：把即将从各注入窗口滑出的见面档案 /
 * 朋友圈动态 / 交换日记，在消失前收集成一段「非聊天素材」文本，交消化作业熔进长期记忆。
 *
 * **收集本身无副作用**（失败可无损重收）；标记只在 [markDigested] 被显式调用后写（= 消化作业成功后·图纸 §2.3）。
 * 三路各有封顶（见面 2 / 朋友圈 8 / 日记 2·§3.5·E8），余量后续班次继续消化。行格式硬编码中文（LLM 读的产品资产·
 * 与聊天内容同等对待）。日志纪律：绝不打素材内容，只打计数（§5·E20）。
 */
@Singleton
class MemoryDigestMaterialService @Inject constructor(
    private val momentRepo: MomentRepository,
    private val offlineMeetingMemoryDao: OfflineMeetingMemoryDao,
    private val diaryDao: DiaryDao,
    private val characterDao: CharacterDao,
) {
    /**
     * 一班次收集的素材束：[text] = 头行 + 三路行（按行首日期升序拼接·全空为 ""）；[meetingUuids]/[diaryUuids] =
     * 本次纳入待标记的行 uuid；[momentsWatermarkAdvanceTo] = 朋友圈水位推进值（null = 本路不推进）；[lineCount] = 素材总行数。
     */
    data class MaterialBundle(
        val text: String,
        val meetingUuids: List<String>,
        val diaryUuids: List<String>,
        val momentsWatermarkAdvanceTo: Long?,
        val lineCount: Int,
    )

    /**
     * 收集三路素材（图纸 §3.5·任一路为空跳过·全空 → text=""）。[userName] 用于行格式代入用户名（空回退「用户」·施工日志 D3）。
     */
    suspend fun collect(
        characterUuid: String,
        userName: String,
        settings: AppSettings,
        nowMillis: Long,
        zone: ZoneId,
    ): MaterialBundle {
        val user = userName.ifBlank { "用户" }
        val meeting = collectMeetings(characterUuid, settings.meetingMemoryInjectCount, zone)
        val moments = collectMoments(characterUuid, user, zone, nowMillis)
        val diary = collectDiary(characterUuid, user, zone, nowMillis)

        // 三路行按行首日期（=底层 timestamp）升序拼接。
        val allLines = (meeting.lines + moments.lines + diary.lines).sortedBy { it.first }
        val text = if (allLines.isEmpty()) "" else HEADER + "\n" + allLines.joinToString("\n") { it.second }
        return MaterialBundle(
            text = text,
            meetingUuids = meeting.uuids,
            diaryUuids = diary.uuids,
            momentsWatermarkAdvanceTo = moments.watermark,
            lineCount = allLines.size,
        )
    }

    /** 标记消化完成（图纸 §3.5·消化作业成功后调·逐条列级 UPDATE + 水位推进·幂等·[characterUuid] 见施工日志 D4）。 */
    suspend fun markDigested(characterUuid: String, bundle: MaterialBundle, now: Long) {
        for (uuid in bundle.meetingUuids) offlineMeetingMemoryDao.markDigested(uuid, now)
        for (uuid in bundle.diaryUuids) diaryDao.markDigested(uuid, now)
        bundle.momentsWatermarkAdvanceTo?.let { characterDao.updateMomentsDigestWatermark(characterUuid, it) }
    }

    // ── A. 见面档案（部件③·降级前消化·上限 2·最旧优先·图纸 §3.5-A） ──

    private data class PathLines(val lines: List<Pair<Long, String>>, val uuids: List<String>)

    private suspend fun collectMeetings(characterUuid: String, injectCount: Int, zone: ZoneId): PathLines {
        val meetings = offlineMeetingMemoryDao.byCharacter(characterUuid).filter { it.kindRaw == "meeting" }
        // 剔除仍在完整注入组的最新 injectCount 条（稳定规则·预算临时降级不算消化触发·§3.5-A）。
        val protectedUuids = meetings.takeLast(injectCount.coerceAtLeast(0)).map { it.uuid }.toSet()
        val candidates = meetings
            .filter { it.digestedAtMillis == null && it.uuid !in protectedUuids }
            .take(MEETING_CAP)
        val lines = candidates.map { it.startedAtMillis to renderMeetingLine(it, zone) }
        return PathLines(lines, candidates.map { it.uuid })
    }

    private fun renderMeetingLine(m: OfflineMeetingMemoryEntity, zone: ZoneId): String {
        val loc = m.location.ifEmpty { "某地" }
        val sb = StringBuilder(
            "[${dateStr(m.startedAtMillis, zone)}] （见面）你们当天在${loc}见面（${m.activity}），留下的记录：${m.summary}",
        )
        val highlights = StringListJson.decode(m.highlightsJson)
        if (highlights.isNotEmpty()) sb.append(" 难忘：${highlights.joinToString("；")}。")
        moodCn(m.moodRaw)?.let { sb.append(" 当时的气氛：${it}。") }
        return sb.toString()
    }

    private fun moodCn(raw: String): String? = when (raw) {
        "warm" -> "温暖"
        "sweet" -> "甜蜜"
        "melancholic" -> "怅然"
        "awkward" -> "尴尬"
        "neutral" -> "平静"
        else -> null
    }

    // ── B. 朋友圈（7 天窗滑出前·6 天线·水位线防重复·上限 8·图纸 §3.5-B） ──

    private data class MomentResult(val lines: List<Pair<Long, String>>, val watermark: Long?)

    private suspend fun collectMoments(characterUuid: String, user: String, zone: ZoneId, nowMillis: Long): MomentResult {
        val character = characterDao.getByUuid(characterUuid) ?: return MomentResult(emptyList(), null)
        val rawWater = character.momentsDigestedUntilMillis
        // 水位 0（新装/旧备份）→ 视作 now−7 天起步，绝不深挖历史（E11）。
        val water = if (rawWater == 0L) nowMillis - SEVEN_DAYS_MS else rawWater
        val rangeEnd = nowMillis - SIX_DAYS_MS
        if (rangeEnd <= water) return MomentResult(emptyList(), null) // 本路为空、不推进
        val candidates = momentRepo.postsForDigest(characterUuid, water, rangeEnd) // (water, rangeEnd] 升序

        val lines = mutableListOf<Pair<Long, String>>()
        var lastCheckedTs = 0L
        var stoppedEarly = false
        for (post in candidates) {
            lastCheckedTs = post.timestamp
            if (post.authorTypeRaw == MomentAuthorType.USER.raw) {
                // 用户帖只收该角色有互动的（照 MomentChatContextService 同款谓词）。
                val charLike = momentRepo.likesForPost(post.uuid).firstOrNull {
                    it.authorTypeRaw == MomentAuthorType.CHARACTER.raw && it.characterUuid == characterUuid
                }
                val charComments = momentRepo.commentsForPost(post.uuid).filter {
                    it.authorTypeRaw == MomentAuthorType.CHARACTER.raw && it.characterUuid == characterUuid
                }
                if (charLike == null && charComments.isEmpty()) continue // 无互动 → 跳过（仍算已检查）
                lines.add(post.timestamp to renderUserPostLine(post, charLike != null, charComments.map { it.content }, user, zone))
            } else {
                val userLike = momentRepo.likesForPost(post.uuid).firstOrNull { it.authorTypeRaw == MomentAuthorType.USER.raw }
                val userComments = momentRepo.commentsForPost(post.uuid).filter { it.authorTypeRaw == MomentAuthorType.USER.raw }
                lines.add(post.timestamp to renderCharPostLine(post, userLike != null, userComments.map { it.content }, user, zone))
            }
            if (lines.size >= MOMENTS_CAP) {
                stoppedEarly = true
                break
            }
        }
        // 收满提前停 → 最后一条已检查候选的 timestamp；否则 → rangeEnd（§3.5-B）。
        val watermark = if (stoppedEarly) lastCheckedTs else rangeEnd
        return MomentResult(lines, watermark)
    }

    private fun renderCharPostLine(post: MomentPostEntity, liked: Boolean, comments: List<String>, user: String, zone: ZoneId): String {
        val content = post.content.ifEmpty { "（无文字，只有图）" }
        val reactions = buildReactions(if (liked) "${user}点赞了" else null, comments.map { "${user}评论说：“${it}”" })
        return "[${dateStr(post.timestamp, zone)}] （朋友圈）你发了动态：“${content}”$reactions"
    }

    private fun renderUserPostLine(post: MomentPostEntity, liked: Boolean, comments: List<String>, user: String, zone: ZoneId): String {
        val content = post.content.ifEmpty { "（无文字，只有图）" }
        val img = if (post.imagePaths.isNotEmpty()) "（附带图片）" else ""
        val reactions = buildReactions(if (liked) "你点赞了" else null, comments.map { "你评论说：“${it}”" })
        return "[${dateStr(post.timestamp, zone)}] （朋友圈）${user}发了动态${img}：“${content}”$reactions"
    }

    /** 反应段：`点赞了` + 各评论以「；」连接，非空时整段以 ` ← ` 引出（照 MomentChatContextService 字面）。 */
    private fun buildReactions(like: String?, comments: List<String>): String {
        val parts = (listOfNotNull(like) + comments)
        return if (parts.isEmpty()) "" else " ← " + parts.joinToString("；")
    }

    // ── C. 交换日记（回流·24h 后·上限 2·最旧优先·图纸 §3.5-C） ──

    private suspend fun collectDiary(characterUuid: String, user: String, zone: ZoneId, nowMillis: Long): PathLines {
        val before = nowMillis - DIARY_DELAY_MS // 留 24h 收用户回应
        val entries = diaryDao.exchangeDiariesToDigest(characterUuid, before)
        val lines = entries.map { it.timestamp to renderDiaryLine(it, user, zone) }
        return PathLines(lines, entries.map { it.uuid })
    }

    private suspend fun renderDiaryLine(entry: DiaryEntryEntity, user: String, zone: ZoneId): String {
        val mood = entry.moodText?.takeIf { it.isNotBlank() } ?: entry.moodEmoji?.takeIf { it.isNotBlank() }
        val moodPart = if (mood != null) "（心情：${mood}）" else ""
        val replies = diaryDao.commentsForEntry(entry.uuid).filter { it.isFromUser }.take(2)
        val replyPart = replies.joinToString("") { "；${user}回复说：“${excerpt(it.content, 40)}”" }
        return "[${dateStr(entry.timestamp, zone)}] （交换日记）你以笔友身份给${user}写了一封信${moodPart}，" +
            "信里写道：“${excerpt(entry.content, 60)}”$replyPart"
    }

    // ── 通用 ──

    /** 摘录：去换行、trim；codePoint 计数超 [n] 则截前 n 加「…」（图纸 §3.5-C）。 */
    private fun excerpt(s: String, n: Int): String {
        val cleaned = s.replace("\n", " ").trim()
        val cps = cleaned.codePointCount(0, cleaned.length)
        if (cps <= n) return cleaned
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, n)) + "…"
    }

    private fun dateStr(millis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(YMD)

    companion object {
        /** 三路封顶（图纸 §3.5·E8）。日记上限 2 在 DAO 查询 LIMIT 2 内。 */
        const val MEETING_CAP = 2
        const val MOMENTS_CAP = 8

        private const val SIX_DAYS_MS = 6L * 24 * 60 * 60 * 1000
        private const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000
        private const val DIARY_DELAY_MS = 24L * 60 * 60 * 1000

        /** 素材头行（图纸 §3.5·锁定逐字）。 */
        const val HEADER =
            "[以下为同期的非聊天素材（朋友圈动态 / 交换日记 / 见面档案），请把其中值得记住的信息一并合并进记忆，与聊天内容同等对待]"

        // 无 locale 敏感符号 → Locale.ROOT 恒 ASCII 数字（照 DateFormatters 范式）。
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    }
}
