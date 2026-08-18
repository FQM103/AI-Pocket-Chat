package com.situ.aichat.sticker

/**
 * 1:1 port of iOS `StickerTagParser` (Models/StickerTypes.swift). Parses `[sticker:ID]` tags out of
 * message text. The ID must contain no `]` and no whitespace (`[^\]\s]+`) — that constraint is the
 * reason custom-sticker aliases strip all whitespace (see [StickerService.buildCustomStickerAliasMap]).
 *
 * Note on `\s`: iOS `NSRegularExpression` treats `\s` as Unicode whitespace; `java.util.regex` `\s`
 * is ASCII-only by default. This is immaterial here — every real ID (built-in short ID, custom UUID,
 * cleaned `c_alias`) is whitespace-free, so both engines match identically.
 */
object StickerTagParser {

    private val stickerTagRegex = Regex("""\[sticker:([^\]\s]+)\]""")

    /** 从文本中提取所有表情包 ID（按出现顺序，含重复）。 */
    fun extractStickerIds(text: String): List<String> =
        stickerTagRegex.findAll(text).map { it.groupValues[1] }.toList()

    /** 纯表情包消息：去掉所有标签后 trim 为空，且至少有一个 ID。 */
    fun isStickerOnly(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val stripped = stickerTagRegex.replace(trimmed, "").trim()
        return stripped.isEmpty() && extractStickerIds(trimmed).isNotEmpty()
    }

    /** 移除所有表情包标签，返回纯文字部分（两端 trim）。 */
    fun stripStickerTags(text: String): String =
        stickerTagRegex.replace(text, "").trim()

    /** 将表情包标签替换为友好显示文本 `[表情包]`（会话预览/通知/引用/搜索）。 */
    fun replaceStickerTagsForDisplay(text: String): String =
        stickerTagRegex.replace(text, "[表情包]").trim()

    /** 快速判定文本是否含表情包标签（与 MessageSplitter 的防拆判定一致）。 */
    fun containsStickerTag(text: String): Boolean = text.contains("[sticker:")
}
