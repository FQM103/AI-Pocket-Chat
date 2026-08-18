package com.situ.aichat.prompt.diary

/** 一次日记生成的产出：剥离 MOOD 元数据行后的正文 + 推断心情（白名单外/缺失 = null）。 */
data class DiaryDraft(val content: String, val moodEmoji: String?)

/**
 * `MOOD: <emoji>` 尾行解析（日记重设计 R2·契约 §2 F2）。
 *
 * ⚠️ **提示词↔解析器强耦合**（CLAUDE.md §5 已登记）：本解析格式与 `diary_prompt_mood_output`
 * （zh/en 双语）要求 LLM 输出的「最后一行 `MOOD: <emoji>`」一体两面——改任一侧必须同步另一侧，
 * 否则心情推断静默失效（优雅降级为 null，不会脏数据入库，但功能哑火）。
 *
 * 规格：
 * - 只认**最后一个非空行**；行首前缀容错 `MOOD` 任意大小写 / `心情`，冒号容半角/全角、两侧空白。
 * - 命中前缀即**整行剥离**（它是元数据，绝不许漏进日记正文）；emoji 从行余部取**第一个白名单命中**，
 *   白名单外 → 心情 null（行仍剥）。
 * - 最后一行不是 MOOD 行 → 原文原样返回（正文中段出现的 MOOD 字样不受影响）。
 * - 剥行后正文尾部空白一并修剪。
 */
object DiaryMoodLineParser {

    /**
     * 12 心情 emoji 白名单。与 UI 侧 [com.situ.aichat.ui.diary.DIARY_MOODS] 的一致性由
     * `DiaryMoodLineParserTest` 看门（prompt 层不反向依赖 ui 层）。
     */
    internal val ALLOWED_EMOJIS: Set<String> = setOf(
        "😊", "😌", "🥰", "😔", "😤", "😰", "🤔", "😴", "🎉", "😢", "💪", "🌈",
    )

    private val MOOD_LINE = Regex("""^\s*(?:mood|心情)\s*[:：]\s*(.*)$""", RegexOption.IGNORE_CASE)

    fun extract(raw: String): DiaryDraft {
        val lines = raw.lines()
        // 定位最后一个非空行。
        val lastIdx = lines.indexOfLast { it.isNotBlank() }
        if (lastIdx < 0) return DiaryDraft(raw.trim(), null)
        val match = MOOD_LINE.find(lines[lastIdx]) ?: return DiaryDraft(raw.trimEnd(), null)
        val remainder = match.groupValues[1]
        val emoji = ALLOWED_EMOJIS.firstOrNull { remainder.contains(it) }
        val content = lines.subList(0, lastIdx).joinToString("\n").trimEnd()
        return DiaryDraft(content, emoji)
    }
}
