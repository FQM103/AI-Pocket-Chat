package com.situ.aichat.story

/**
 * 阅读器标题/章节号文本格式化（1:1 iOS `storyCleanTitle` / `storyNumberToChinese`，`StoryReaderView+Sections.swift:117-135`）。
 * 纯函数，单测反推 iOS。
 */

/** 去掉标题里可能重复的「第X章：」前缀（1:1 iOS `storyCleanTitle`，去前缀后为空则保留原标题）。 */
fun storyCleanTitle(title: String): String {
    val match = Regex("^第.{1,4}章[：:]?\\s*").find(title) ?: return title
    val cleaned = title.substring(match.range.last + 1)
    return cleaned.ifEmpty { title }
}

/**
 * 数字转中文（1→一、11→十一、20→二十、101→一百零一…），对应 iOS `NumberFormatter(.spellOut, zh_Hans)`。
 * 覆盖章节号常见范围 0-9999；越界或负数回退十进制串。
 */
fun storyNumberToChinese(number: Int): String {
    if (number == 0) return "零"
    if (number < 0 || number > 9999) return number.toString()

    val digits = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    val units = arrayOf("", "十", "百", "千")
    val s = number.toString()
    val len = s.length
    val sb = StringBuilder()
    var zeroPending = false
    for (i in s.indices) {
        val d = s[i] - '0'
        val unitPos = len - 1 - i
        if (d == 0) {
            zeroPending = true
        } else {
            if (zeroPending) {
                sb.append("零")
                zeroPending = false
            }
            sb.append(digits[d]).append(units[unitPos])
        }
    }
    var result = sb.toString()
    // 10-19 读「十一」而非「一十一」（NumberFormatter 同；110 等仍保留「一百一十」）。
    if (result.startsWith("一十")) result = result.removePrefix("一")
    return result
}
