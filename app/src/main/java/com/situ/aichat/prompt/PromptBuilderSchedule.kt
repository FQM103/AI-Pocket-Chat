package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 1:1 port of iOS `Services/PromptBuilder+Schedule.swift` — `scheduleAwareness`（默认 prefix，全天日程背景）
 * 与 `currentMoment`（默认 suffix，【此刻】即时块）两个模块（M12 / P5.2）。日程数据由调用方
 * （ChatViewModel）查好后经 [PromptBuilder.BuildContext.todaySchedule]/`todayScheduleEvents` 传入。
 *
 * 提示词全部**硬编码中文**（LLM 读的产品资产），不进 values。
 * 线下见面专版已落地（前后置区审计 🟡-1b·2026-07-13）：【此刻】走 [buildOfflineCurrentMomentBlock]
 * （见面=唯一事实、日程降级背景板）；时间锚同卷 factsOnly。**仍延后**：今日天气行（数据列已留、P11 才有值）。
 */

/** 日程列表 + 状态标签 + 天气 + 时态硬约束（scheduleAwareness 模块）。无日程数据→空串（【此刻】兜底交给 currentMoment）。 */
internal fun buildScheduleModule(ctx: PromptBuilder.BuildContext): String {
    if (!ctx.appSettings.scheduleSystemEnabled) return ""
    val data = loadScheduleData(ctx) ?: return ""

    val parts = mutableListOf<String>()
    parts.add("【你今天完整的日程】")
    // 刀4 旧戏份压缩（招3·2026-07-11 过审）：[✓已发生] 事件合并为**一行流水账**——保留时段词、删钟点/
    // 地点/心情（演完的戏只留存在感,降低"拿早晨素材演此刻"的显著度;13:39 被答成清晨的事故根源之二）。
    // [▶️正在]/[⏳未来] 各行原样全细节;段标题与三个时态标签字面 = 红线零碰。
    val past = data.sortedEvents.filter { it.endTime < data.nowMillis }
    if (past.isNotEmpty()) {
        val pastLine = past.joinToString(" → ") { event ->
            val periodPart = if (event.periodLabel.isEmpty()) "" else "${event.periodLabel} "
            "$periodPart${event.activity}"
        }
        parts.add("$TAG_PAST $pastLine")
    }
    for (event in data.sortedEvents.filter { it.endTime >= data.nowMillis }) {
        val status = scheduleEventStatusTag(event.startTime, event.endTime, data.nowMillis)
        val timeRange = "${hhmm(event.startTime, data.zone)}-${hhmm(event.endTime, data.zone)}"
        val periodPart = if (event.periodLabel.isEmpty()) "" else "${event.periodLabel} "
        var line = "$status $periodPart$timeRange ${event.activity}"
        if (event.location.isNotEmpty()) line += "（${event.location}）"
        event.moodText?.takeIf { it.isNotEmpty() }?.let { line += " — 心情：$it" }
        parts.add(line)
    }

    // 今日天气：数据列已留位（P11 才有值），非线下模式注入；当前 weatherCondition 恒 null → 跳过。
    val city = data.schedule.cityName
    val condition = data.schedule.weatherCondition
    if (city != null && condition != null) {
        var weatherLine = "\n今天天气：$city，$condition"
        data.schedule.temperatureHigh?.let { weatherLine += "，${Math.round(it)}°C" }
        parts.add("$weatherLine。")
    }

    // 非线下：追加时态状态标签硬约束（线下专用指令属 M16/P10）。
    parts.add("\n$SCHEDULE_TAG_GUIDANCE")
    return parts.joinToString("\n")
}

/** 【此刻】即时块（currentMoment 模块）+ 默认注入指令。无日程数据→走兜底推测；线下见面→专版（背景板化）。 */
internal fun buildCurrentMomentModule(ctx: PromptBuilder.BuildContext): String {
    if (!ctx.appSettings.scheduleSystemEnabled) return ""
    // 线下见面专版（前后置区审计 🟡-1b·2026-07-13 拍板）：见面就是「此刻」唯一的事实——在线版的
    // 日程时点/短信示范/⚠️分心提示在此全不适用（与末位见面说明书"面对面"直接矛盾）。日程降级为背景板。
    if (ctx.scene == PromptScene.OFFLINE_MEETING) return buildOfflineCurrentMomentBlock(ctx)
    val data = loadScheduleData(ctx) ?: return buildEmptyScheduleFallback(ctx)

    val momentBlock = buildCurrentMomentBlock(data, ctx.resolvedUserName)
    if (momentBlock.isEmpty()) return ""
    return "$momentBlock\n\n$DEFAULT_INJECTION_INSTRUCTION"
}

/**
 * 【此刻】线下见面专版（🟡-1b·文案锁定=微图纸 2026-07-13-前后置区审计与修缮 §二物料）：
 * 恒首行"见面是唯一在做的事"；日程只作背景板——正被见面替代的安排（别演成还在做）/ 赴约前刚结束的事 /
 * 晚些的安排（不用赶时间）。无日程数据 → ""（见面说明书已足够，不注入孤块）。标题沿用【此刻】（无检测器耦合）。
 */
private fun buildOfflineCurrentMomentBlock(ctx: PromptBuilder.BuildContext): String {
    val data = loadScheduleData(ctx) ?: return ""
    val now = data.nowMillis
    val lines = mutableListOf(
        "【此刻】",
        "你此刻正在和${ctx.resolvedUserName}线下见面——见面就是你现在唯一在做的事。",
    )
    val current = data.sortedEvents.firstOrNull { it.startTime <= now && now <= it.endTime }
    val pastLast = data.sortedEvents.lastOrNull { it.endTime < now }
    when {
        current != null ->
            lines.add("今天这个时段你原本安排的是「${current.activity}」——你为了这次见面把它放下了；可以自然当话头提起，但别演成你还在做它。")
        pastLast != null ->
            lines.add("见面之前，你刚结束「${pastLast.activity}」。")
    }
    data.sortedEvents.firstOrNull { it.startTime > now }?.let { next ->
        lines.add("今天晚些时候（${hhmm(next.startTime, data.zone)}）原本还有「${next.activity}」——不用赶时间，也别把日程当清单念。")
    }
    lines.add(MOMENT_PRIVATE_NOTE)
    return lines.joinToString("\n")
}

// MARK: - 共享数据加载（过滤 userInteraction + 按 startTime→sortOrder 排序）

private class ScheduleData(
    val schedule: CharacterDailyScheduleEntity,
    val sortedEvents: List<ScheduleEventEntity>,
    val nowMillis: Long,
    val zone: ZoneId,
)

private fun loadScheduleData(ctx: PromptBuilder.BuildContext): ScheduleData? {
    val schedule = ctx.todaySchedule ?: return null
    // 过滤聊天写回/线下记录（userInteraction），只留角色自己的日程；startTime 为主、sortOrder 为 tiebreak。
    val sorted = ctx.todayScheduleEvents
        .filter { it.eventTypeRaw != EVENT_TYPE_USER_INTERACTION }
        .sortedWith(compareBy({ it.startTime }, { it.sortOrder }))
    if (sorted.isEmpty()) return null

    val zone = schedule.timezoneIdentifier?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()
    return ScheduleData(schedule, sorted, ctx.now.toEpochMilli(), zone)
}

// MARK: - 「此刻」即时块（非线下 3 情况；线下专版见上方 buildOfflineCurrentMomentBlock）

private fun buildCurrentMomentBlock(data: ScheduleData, userName: String): String {
    val now = data.nowMillis
    val zone = data.zone

    // 刀2 现在卡合并（2026-07-11 过审）：日期/时刻行删除——本模块与 timeAwareness 合并为一条「现在卡」，
    // 时间事实由前半的 <time_context> 独家供给（用户禁用 timeAwareness 的边缘配置=宁缺勿错，接受）。
    val lines = mutableListOf("【此刻】")

    val current = data.sortedEvents.firstOrNull { it.startTime <= now && now <= it.endTime }
    if (current != null) {
        // 情况 1：有进行中事件
        var headline = "你正在${current.activity}"
        if (current.location.isNotEmpty()) headline += "（地点：${current.location}）"
        val remainingMin = ((current.endTime - now) / 60_000L).coerceAtLeast(0L)
        if (remainingMin > 0) headline += "，预计还持续约 $remainingMin 分钟"
        headline += "。"
        lines.add(headline)

        current.moodText?.takeIf { it.isNotEmpty() }?.let { lines.add("心情：$it") }
        current.innerThought?.takeIf { it.isNotEmpty() }?.let { lines.add("内心活动：$it") }
        current.relatedCharacterNames?.takeIf { it.isNotEmpty() }?.let { lines.add("身边的人：$it") }

        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        if (scheduleIsSleepEvent(current.activity, current.isPhoneAvailable, hour)) {
            lines.add("⚠️ 你此刻处于睡觉/半睡状态——回复要体现困意（用省略号、短句、哈欠等措辞），一两句就好，不要精神饱满地展开长话题。只用文字回复，不要用括号写动作、神态或场景。")
        } else if (!current.isPhoneAvailable) {
            lines.add("⚠️ 此刻你注意力不在手机上（比如开会、开车、专注做事）——回复应该简短、略显分心（例如\"稍等\"、\"在开会\"、\"晚点说\"），不要展开长对话。")
        }

        data.sortedEvents.firstOrNull { it.startTime > now }?.let { next ->
            var nextLine = "接下来 ${hhmm(next.startTime, zone)} 要${next.activity}"
            if (next.location.isNotEmpty()) nextLine += "（地点：${next.location}）"
            nextLine += "。"
            lines.add(nextLine)
        }
    } else {
        // 情况 2/3：没有进行中的事件
        val pastLast = data.sortedEvents.lastOrNull { it.endTime < now }
        val next = data.sortedEvents.firstOrNull { it.startTime > now }
        if (next != null) {
            // 情况 2：空档期
            var headline = if (pastLast != null) "你刚结束「${pastLast.activity}」" else "今天的日程还没正式开始"
            headline += "，下一个安排是 ${hhmm(next.startTime, zone)} 的「${next.activity}」"
            if (next.location.isNotEmpty()) headline += "（地点：${next.location}）"
            headline += "。"
            lines.add(headline)
            lines.add("这段时间没有明确安排，你可以根据自己的人设自然决定此刻在做什么（比如走路、处理杂事、休息、刷手机等）。")
        } else {
            // 情况 3：今日日程全部结束
            var headline = "今天计划中的日程已全部结束"
            if (pastLast != null) headline += "（最后一件事是「${pastLast.activity}」）"
            headline += "。"
            lines.add(headline)
            lines.add("你可能在放松、自由活动或准备休息。")
        }
    }
    return lines.joinToString("\n")
}

/**
 * 兜底：无日程数据时引导 LLM 基于人设+当前时间自然推测此刻状态（非线下）。
 * 刀2 现在卡合并：时刻/日期行删除（由同卡前半 <time_context> 独家供给）；「历史是过去式」护栏
 * 由时间锚的间隔五档措辞承担（见 [TimeAnchorFormatter.buildTimeAnchor]），此处不重复。
 */
private fun buildEmptyScheduleFallback(ctx: PromptBuilder.BuildContext): String = """
    【此刻】结合你自己的职业、性格和作息，代入你这个角色此刻真实的状态，自然体现在回应里——不用硬编具体地点。
    ${MOMENT_PRIVATE_NOTE}
""".trimIndent()

// MARK: - 纯函数（单测覆盖）

/** 时刻 → 中文时段标签（对齐 iOS timeOfDayLabel：清晨/上午/中午/下午/晚上/深夜）。 */
internal fun scheduleTimeOfDayLabel(hour: Int): String = when (hour) {
    in 5..8 -> "清晨"
    in 9..11 -> "上午"
    in 12..13 -> "中午"
    in 14..17 -> "下午"
    in 18..21 -> "晚上"
    else -> "深夜"
}

/** 三个时态标签字面（红线：与 DirtyMessageDetector/状态标签说明强耦合，改一处必须同步全部）。 */
internal const val TAG_PAST = "[✓已发生]"
internal const val TAG_ONGOING = "[▶️正在]"
internal const val TAG_FUTURE = "[⏳未来·尚未发生]"

/** 事件状态标签（对齐 iOS：end<now=已发生 / start<=now<=end=正在 / 否则=未来）。 */
internal fun scheduleEventStatusTag(startMillis: Long, endMillis: Long, nowMillis: Long): String = when {
    endMillis < nowMillis -> TAG_PAST
    startMillis <= nowMillis -> TAG_ONGOING
    else -> TAG_FUTURE
}

/** 是否睡眠/休息状态（对齐 iOS ScheduleEvent.isSleepEvent：关键词 或 深夜 23-7 点且手机不可用）。 */
internal fun scheduleIsSleepEvent(activity: String, isPhoneAvailable: Boolean, hour: Int): Boolean {
    val lower = activity.lowercase()
    if (SLEEP_KEYWORDS.any { lower.contains(it) }) return true
    return (hour >= 23 || hour < 7) && !isPhoneAvailable
}

// MARK: - 格式化辅助

private fun hhmm(millis: Long, zone: ZoneId): String =
    HHMM_FORMATTER.withZone(zone).format(Instant.ofEpochMilli(millis))

private val HHMM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val SLEEP_KEYWORDS = listOf("睡", "休息", "入睡", "sleep")
private const val EVENT_TYPE_USER_INTERACTION = "userInteraction"

/**
 * 状态标签时态硬约束（scheduleAwareness 末尾，非线下）。刀3 时态例题合并（2026-07-11 过审·D 节留一道）：
 * 三行标签定义已自带能说/不能说,三道例题删两留一（保「未来当已做」最常翻车方向压阵）、「正确做法」段撤销
 * （与定义复读）,其独有的「问起全天安排可自然提及」并进 ⏳ 行。段标题与三个时态标签字面 = 红线零碰。
 */
private val SCHEDULE_TAG_GUIDANCE = """
    【状态标签说明（非常重要，影响回复时态）】
    - [✓已发生] = 已经过去的活动，只能用回忆口吻提，不能说成"正在做"或"刚做完"
    - [▶️正在] = 此刻真正在做的事（以后面的「此刻」块为准）
    - [⏳未来·尚未发生] = 还没到点的计划，**不要在回复里说"做了 / 正在做 / 刚做完"**，只能说"待会要"/"一会儿准备"；用户问起全天安排时可以自然提及
    （错误示范：事实是 [⏳未来] 18:00 做晚餐，回复却说"做晚餐时喝了奶茶"——这就是把没发生的说成发生了，禁止。）
""".trimIndent()

/** 现在卡全卡唯一的「给你看的」尾注（刀2 过审：时间锚/【此刻】各留一处 → 收敛到全卡末尾这一处）。 */
private const val MOMENT_PRIVATE_NOTE = "（这段是给你看的，不要在回复里输出。）"

/**
 * currentMoment 末尾默认注入指令（刀2 现在卡合并过审稿）：删「若聊天记录…以【此刻】为准」括号句
 * （双裁判之二,历史过时语义已由时间锚五档承担）；原第 4/5 条并一条；末尾追加全卡唯一尾注。
 * 第 5 条 = 多源事实裁决句（四小件图纸·2026-07-16）：记忆/朋友圈/日程与【此刻】冲突时的全局裁决序。
 * 仅在线主路注入——线下走 [buildOfflineCurrentMomentBlock]、无日程走 [buildEmptyScheduleFallback]，
 * 二者均无「日程 vs 此刻」冲突面（历史时态护栏由时间锚五档承担，不重复设岗）。
 */
private val DEFAULT_INJECTION_INSTRUCTION = """
    你的回复可以自然反映【此刻】的真实状态——让用户感受到你在过自己的生活，而不是随时待命的聊天机器人。
    请按以下节奏把握：
    1. 用户问你的状态时（如"在干嘛""忙吗""吃了吗"）——一定要用【此刻】的真实情况如实回应，不要敷衍
    2. 用户约你做事时——结合当前活动和后续安排，自然判断是否方便
    3. 【此刻】有 ⚠️ 提示时（睡眠、开会、专注）——回复要简短、体现分心
    4. 其他情况下可以自然带出，但不要强行塞进每句话；整段回复里带出一次足够，同一状态也别在连续几次回复里反复强调
    5. 记忆、朋友圈、日程里更早的信息如果和【此刻】说得不一样，一律以【此刻】为准——那些是过去，这是现在

    合格示范（仅供参考风格，不要照搬用词）：
    - 用户"在吗？" → "在呢，刚到公司准备开会，等会儿回你"
    - 用户"周末有空吗？" → "周末下午约了朋友，早上可以的"
    反例：用户在问你代码怎么写，你开头非要加一句"我现在在咖啡馆"——这叫硬塞，不是真实感。
    ${MOMENT_PRIVATE_NOTE}
""".trimIndent()
