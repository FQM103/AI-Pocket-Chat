package com.situ.aichat.prompt

/**
 * 1:1 port of iOS `CalendarItemParser`（Utilities）。把消息内容里的日历引用行 `[#E1]`/`[#R1]` 逐行解析为
 * text + calendarItem 混合段，供聊天把卡片行渲染成日程卡片（[com.situ.aichat.ui.chat.ScheduleCardBubble]）。
 *
 * 安卓无系统提醒事项，但解析仍兼容 `[#R…]`（防御 AI 误输出）；渲染时 reminder 类型只换图标。纯函数，单测覆盖。
 */
object CalendarItemParser {

    sealed interface Segment {
        data class Text(val text: String) : Segment
        data class Item(val item: ParsedCalendarItem) : Segment
    }

    enum class ItemType { EVENT, REMINDER }

    data class ParsedCalendarItem(
        val type: ItemType,
        val index: Int,
        val title: String,
        val dateInfo: String,
    )

    /** 快速判断：是否包含 `[#E1]`/`[#R1]` 格式的日历引用。 */
    private val quickCheckRegex = Regex("""\[#[ER]\d+\]""")

    /** 行级匹配：类型、编号、内容。 */
    private val lineRegex = Regex("""\[#([ER])(\d+)\]\s*(.+)""")

    /** 卡片标题尾部需清理的标点（对齐 iOS trailingPunctuation）。 */
    private const val TRAILING_PUNCTUATION = "。！？.!?，,；;、"

    /** 剥离标签用正则（含尾部可选空白），对齐 iOS stripRegex。 */
    private val stripRegex = Regex("""\[#[ER]\d+\]\s*""")

    fun containsCalendarRefs(content: String): Boolean = quickCheckRegex.containsMatchIn(content)

    /** 从内容中剥离 `[#E1]`/`[#R1]` 标签（复制 & 无障碍用），对齐 iOS stripCalendarRefs（不 trim）。 */
    fun stripCalendarRefs(content: String): String = stripRegex.replace(content, "")

    /**
     * 解析为 text + calendarItem 混合段：含 `[#E1]`/`[#R1]` 的行 → Item，连续普通文本行合并为一个 Text。
     * 全无匹配 → 单个 Text(content)。
     */
    fun parse(content: String): List<Segment> {
        val lines = content.split("\n")
        val segments = mutableListOf<Segment>()
        val pending = StringBuilder()

        fun flushPending() {
            val trimmed = pending.toString().trim()
            if (trimmed.isNotEmpty()) segments.add(Segment.Text(trimmed))
            pending.setLength(0)
        }

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) {
                if (pending.isNotEmpty()) pending.append("\n")
                continue
            }
            val match = lineRegex.find(trimmedLine)
            if (match != null) {
                flushPending()
                val typeStr = match.groupValues[1]
                val itemType = if (typeStr == "E") ItemType.EVENT else ItemType.REMINDER
                val itemIndex = match.groupValues[2].toIntOrNull() ?: 0
                val rawContent = match.groupValues[3].trim()
                val (title, dateInfo) = splitTitleAndDate(rawContent)
                segments.add(Segment.Item(ParsedCalendarItem(itemType, itemIndex, title, dateInfo)))
            } else {
                if (pending.isNotEmpty()) pending.append("\n")
                pending.append(trimmedLine)
            }
        }
        flushPending()

        return segments.ifEmpty { listOf(Segment.Text(content)) }
    }

    /**
     * 拆分引用内容为标题与日期信息（对齐 iOS splitTitleAndDate）：
     * 1) 中/英文括号 → 括号前标题、括号内日期；2) 含 `~` 且含数字 → 第一个数字起为日期；3) 否则整段为标题。
     */
    internal fun splitTitleAndDate(content: String): Pair<String, String> {
        // 策略1：中文括号 （...）
        run {
            val open = content.indexOf('（')
            val close = content.lastIndexOf('）')
            if (open in 0 until close) {
                val title = cleanTitle(content.substring(0, open))
                val dateInfo = content.substring(open + 1, close).trim()
                if (title.isNotEmpty() && dateInfo.isNotEmpty()) return title to dateInfo
            }
        }
        // 策略1b：英文括号 (...)
        run {
            val open = content.indexOf('(')
            val close = content.lastIndexOf(')')
            if (open in 0 until close) {
                val title = cleanTitle(content.substring(0, open))
                val dateInfo = content.substring(open + 1, close).trim()
                if (title.isNotEmpty() && dateInfo.isNotEmpty()) return title to dateInfo
            }
        }
        // 策略2：含 ~ 且含数字（日期区间）
        if (content.contains("~") && content.any { it.isDigit() }) {
            val firstDigit = content.indexOfFirst { it.isDigit() }
            if (firstDigit >= 0) {
                val titlePart = cleanTitle(content.substring(0, firstDigit))
                val datePart = content.substring(firstDigit).trim()
                if (datePart.isNotEmpty()) {
                    return if (titlePart.isEmpty()) datePart to "" else titlePart to datePart
                }
            }
        }
        // 策略3：无法拆分，整段作为标题
        return cleanTitle(content) to ""
    }

    /** 清理卡片标题：去除尾部标点 + trim（对齐 iOS cleanTitle）。 */
    private fun cleanTitle(title: String): String {
        var result = title
        while (result.isNotEmpty() && TRAILING_PUNCTUATION.contains(result.last())) {
            result = result.dropLast(1)
        }
        return result.trim()
    }
}
