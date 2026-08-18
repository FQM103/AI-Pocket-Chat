package com.situ.aichat.world.bulletin

import com.situ.aichat.data.local.entity.WorldEventEntity

/**
 * 开机小报的**模板文案常量**（W5 图纸 §4.4 / §4.5·逐字锁死·图纸 §9 禁改）：零 LLM 的骨架文本，恒有值；
 * LLM 润色只是锦上添花，断网/失败一律退回这里（§7.A 降级铁则）。
 */
object WorldBulletinTemplates {

    /** 城名兜底（`WorldAtlas.cityById` 查无时·§4.4）。 */
    const val FALLBACK_CITY = "小城"

    /** 展示事件上限（§4.4·降序取 ≤5 条·超出并一行小结）。 */
    const val MAX_SHOWN_EVENTS = 5

    /**
     * 有事模板（§4.4·锁死）：首行「你不在的这段时间，{城名}发生了这些事：」+ 事件按 happenedAt 降序 ≤5 条
     * 每条「· {summary}」+ 超出追加「……还有 {n} 件小事」。[events] 由调用方给窗内事件（非空）。
     */
    fun withEvents(cityName: String, events: List<WorldEventEntity>): String {
        val sorted = events.sortedByDescending { it.happenedAt }
        val shown = sorted.take(MAX_SHOWN_EVENTS)
        val sb = StringBuilder("你不在的这段时间，")
        sb.append(cityName).append("发生了这些事：")
        for (event in shown) sb.append("\n· ").append(event.summary)
        val overflow = sorted.size - shown.size
        if (overflow > 0) sb.append("\n……还有 ").append(overflow).append(" 件小事")
        return sb.toString()
    }

    /** 静好模板（§4.4·锁死·窗内零事件且缺席 ≥24h）。 */
    fun quiet(cityName: String): String = "这几天${cityName}安安静静的，大家各自过着日子。"

    /** 润色 LLM system 提示词（§4.5·锁死·一字不改）。 */
    const val POLISH_SYSTEM_PROMPT =
        "你是一座温暖小城的市井小报主笔。把下面的事件清单改写成一段 80 到 160 字的连贯短文：口吻温热、有生活气，" +
            "像朋友向刚回家的人絮叨近况。只许写清单里的事，不许编造新的事件、人物或对话；不用标题、不用列表、不用 emoji、不用 markdown。"
}
