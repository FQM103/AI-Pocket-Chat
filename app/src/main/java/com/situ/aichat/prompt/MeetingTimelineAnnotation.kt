package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import java.time.Instant
import java.time.ZoneId

/**
 * 见面时间线注记（记忆改造二期·部件④·图纸 §3.1）。**全纯函数 object**——不碰 DB / 网络 / 协程。
 *
 * 普通聊天窗口里见面原文早已不注入（梦剧场 B 部退役原文通道），于是「见面发生过」在历史时间线上成了隐形
 * 黑洞：AI 看到见面前的铺垫与见面后的余韵直接相连、中间无迹可循。本注记在见面发生的时间位置插一条 system
 * 时间线注记，借用既有 [HistoryTimeDivider]（【时间 · 】）机制的全部安全设施。
 *
 * ⚠️ **强耦合（图纸 §3.1-D·三处 KDoc 互指登记）**：注记以「【时间 · 」开头 → **免费继承**三重安全设施——
 * [ReplyParser] 整行剥回声（`^【时间 ·…】$`）、[HistoryTimeDivider.isDivider] 末尾悬空清理、
 * [com.situ.aichat.prompt.DirtyMessageDetector] 保留标记避让（与 `matchesMarkerTextRepeat` 的「【线下见面开始 |」、
 * `matchesMeetingMemoryFormatRepeat` 的「【见面 · 」均无子串碰撞）。**双侧零改动**，无需改检测器 / 解析器。
 *
 * 数据源用见面档案行（location/activity/startedAtMillis·结构化单一真相源），非 marker 反解；摘要未落地的
 * 见面（重试中）该轮无注记，容忍（图纸 E4）。
 */
object MeetingTimelineAnnotation {

    /** 单窗口注记上限（图纸 §3.1-B·锁定）。 */
    const val MAX_ANNOTATIONS = 5

    /**
     * 候选筛选（图纸 §3.1-B·锁定）：[rows] 中 `kindRaw == "meeting"` 且 `startedAtMillis` 严格落在窗口跨度
     * ([firstTs], [lastTs]) **开区间**内的见面，按 startedAtMillis 升序，超 [MAX_ANNOTATIONS] 取**最新 5 条**
     * （`takeLast`·legacy 行 startedAt=0 天然出局）。[firstTs]/[lastTs] = 窗口首 / 末消息 timestamp。
     */
    fun selectEligible(
        rows: List<OfflineMeetingMemoryEntity>,
        firstTs: Long,
        lastTs: Long,
    ): List<OfflineMeetingMemoryEntity> =
        rows.filter { it.kindRaw == "meeting" && it.startedAtMillis > firstTs && it.startedAtMillis < lastTs }
            .sortedBy { it.startedAtMillis }
            .takeLast(MAX_ANNOTATIONS)

    /**
     * 注记整行（图纸 §3.1-D·锁定逐字）：
     * `【时间 · {formatLabel(startedAtMillis)} · 这中间你们线下见了一面：{location|某地}{，activity}】`
     * 示例：`【时间 · 7月3日 周四 19:20 · 这中间你们线下见了一面：江边，散步】`。
     * 复用 [HistoryTimeDivider.formatLabel]（同包 internal）+ OPEN/CLOSE 前后缀 → 继承三重安全设施（见类 KDoc）。
     */
    fun lineFor(row: OfflineMeetingMemoryEntity, now: Instant, zone: ZoneId): String {
        val label = HistoryTimeDivider.formatLabel(row.startedAtMillis, now, zone)
        val place = row.location.ifEmpty { "某地" }
        val activitySuffix = if (row.activity.isNotEmpty()) "，${row.activity}" else ""
        return "${HistoryTimeDivider.OPEN}$label · 这中间你们线下见了一面：$place$activitySuffix${HistoryTimeDivider.CLOSE}"
    }
}
