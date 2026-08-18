package com.situ.aichat.gift

import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.entity.GiftRecordEntity
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 「礼物历史」(`<gift_history>`) 提示词块（1:1 iOS `GiftHistoryPromptService`，三层礼物记忆的 **L2 中期**）。
 *
 * 把 GiftRecord 聚合成紧凑 XML 注入 system prompt，让角色穿透消息窗口始终知道"关系里的礼物流动"。**双向**注入两段
 * （任一为空省略该段，两段都空返回 ""）。人称=房子风格「你」=角色 + 用户名（图纸一·A1）：
 * - **【（用户名）送你的】**：receiver==char && sender=='user'
 * - **【你送（用户名）的】**：sender=='character' && senderCharacterUUID==char && receiver=='user'（主动送礼路径）
 *
 * 渲染纯函数 [render]/[relativeGiftTime] 与 DB 查询分离，便于单测。冒号用**半角** `:`（对齐 iOS）。
 */
object GiftHistoryPromptService {

    /** 构建 `<gift_history>` 块（DB 查询 + 渲染）。双向都无记录 → ""。[userName] = 调用方现成 resolved 用户名（图纸一·A1）。 */
    suspend fun buildContent(characterUuid: String, dao: GiftDao, now: Long = System.currentTimeMillis(), userName: String): String {
        val userToChar = runCatching { dao.userGiftsToCharacterDesc(characterUuid) }.getOrDefault(emptyList())
        val charToUser = runCatching { dao.characterGiftsToUserDesc(characterUuid) }.getOrDefault(emptyList())
        return render(userToChar, charToUser, now, userName)
    }

    /** 纯渲染（输入须已按 timestamp 降序，DAO 已 ORDER BY DESC）。标题人称=「你」=角色 + [userName]（图纸一·A1·§9）。 */
    internal fun render(
        userToChar: List<GiftRecordEntity>,
        charToUser: List<GiftRecordEntity>,
        now: Long,
        userName: String,
    ): String {
        if (userToChar.isEmpty() && charToUser.isEmpty()) return ""
        val lines = mutableListOf("<gift_history>")
        if (userToChar.isNotEmpty()) lines += formatSection("【${userName}送你的】", userToChar, now)
        if (charToUser.isNotEmpty()) lines += formatSection("【你送${userName}的】", charToUser, now)
        lines += "</gift_history>"
        return lines.joinToString("\n")
    }

    /** 单方向 → 3 行（summary + recent + 可选 precious）。 */
    private fun formatSection(label: String, records: List<GiftRecordEntity>, now: Long): List<String> {
        val total = records.size
        val preciousCount = records.count { it.pricePaid > 200 }
        val handmadeCount = records.count { isHandmade(it) }

        val summaryParts = mutableListOf("共 $total 份礼物")
        if (preciousCount > 0) summaryParts += "$preciousCount 件珍贵"
        if (handmadeCount > 0) summaryParts += "$handmadeCount 件手作"
        val lines = mutableListOf(label + summaryParts.joinToString("、"))

        val recent = records.take(3)
        lines += "最近:" + recent.joinToString("、") { describe(it, now) }

        // 最珍贵：价格最高 且 >200 且 不在最近 3 件中
        val recentUuids = recent.mapTo(HashSet()) { it.uuid }
        val mostPrecious = records.maxByOrNull { it.pricePaid }
        if (mostPrecious != null && mostPrecious.pricePaid > 200 && mostPrecious.uuid !in recentUuids) {
            lines += "最珍贵:" + describe(mostPrecious, now)
        }
        return lines
    }

    /** `<名称>(<手作·?><相对时间>)`，如 `手写情书(手作·3 天前)`。 */
    private fun describe(record: GiftRecordEntity, now: Long): String {
        val name = displayName(record)
        val handmadeTag = if (isHandmade(record)) "手作·" else ""
        val time = relativeGiftTime(record.timestamp, now)
        return "$name($handmadeTag$time)"
    }

    private fun displayName(record: GiftRecordEntity): String {
        if (record.isDIY) return record.diyTitle.ifEmpty { "一份手作礼物" }
        return GiftCatalog.find(record.giftItemId)?.name ?: "一份礼物"
    }

    /** 手作判定：record.isDIY（运行时 DIY）或目录 isHandmade（note/postcard/origami/love_letter）。 */
    private fun isHandmade(record: GiftRecordEntity): Boolean {
        if (record.isDIY) return true
        return GiftCatalog.find(record.giftItemId)?.isHandmade == true
    }

    /**
     * 礼物历史专用相对时间（1:1 iOS `relativeGiftTime`，粒度较粗）：今天/昨天/2-6天前/约N周前(7-29)/约N个月前(30-364)/
     * 约N年前(≥365)；未来时间防御性返回"刚刚"。用设备时区日历日做差（= iOS Calendar.current + startOfDay）。
     */
    internal fun relativeGiftTime(
        fromMillis: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val fromDay = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        val toDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(fromDay, toDay).toInt()
        return when {
            days < 0 -> "刚刚"
            days == 0 -> "今天"
            days == 1 -> "昨天"
            days < 7 -> "$days 天前"
            days < 30 -> "约 ${days / 7} 周前"
            days < 365 -> "约 ${maxOf(1, days / 30)} 个月前"
            else -> "约 ${maxOf(1, days / 365)} 年前"
        }
    }
}
