package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity

/**
 * 结局全文 txt 导出内容装配（ST8·契约 §5·纯逻辑）。
 *
 * 逐章：章标题 + 干净正文（[StoryTextSanitizer] 剥沉浸标签——**只调现成清洗函数·绝不碰解析格式/标签/段标题**·红线①）
 * + 选择标注（有 userChoice 才追加）。本地化文案（章头 / 选择前缀）由调用方传格式串，保持纯可测；
 * SAF 落盘走 UI 层（[com.situ.aichat.ui.story.StoryArchiveDetailScreen]·照 BackupScreen 姿势）。
 */
object StoryTxtExporter {
    /**
     * @param title 书名（置顶）
     * @param chapters 全量章节（任意顺序·内部按 chapterNumber 升序）
     * @param chapterHeaderFormat 章头模板，如 "第 %1$d 话 · %2$s"（%1=章号 %2=标题）
     * @param choicePrefixFormat 选择标注模板，如 "▶ 你的选择：%1$s"（%1=用户所选文本）
     */
    fun build(
        title: String,
        chapters: List<StoryChapterEntity>,
        chapterHeaderFormat: String,
        choicePrefixFormat: String,
    ): String {
        val ordered = chapters.sortedBy { it.chapterNumber }
        val blocks = ordered.map { ch ->
            val header = String.format(chapterHeaderFormat, ch.chapterNumber, ch.title)
            val body = StoryTextSanitizer.sanitize(ch.content)
            val choice = ch.userChoice?.takeIf { it.isNotBlank() }
                ?.let { "\n\n" + String.format(choicePrefixFormat, it) }
                .orEmpty()
            "$header\n\n$body$choice"
        }
        return (listOf(title) + blocks).joinToString("\n\n") + "\n"
    }
}
