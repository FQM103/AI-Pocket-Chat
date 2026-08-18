package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StoryTxtExporter 纯逻辑单测（ST8·全文导出内容对表·断言从契约 §5 反推）：
 * 标签剥净 + 选择标注 ▶ + 章序 + 书名置顶。
 */
class StoryTxtExporterTest {

    private val header = "第 %1\$d 话 · %2\$s"
    private val choice = "▶ 你的选择：%1\$s"

    private fun ch(number: Int, title: String, content: String, userChoice: String? = null) =
        StoryChapterEntity(id = "c$number", storyId = "s", chapterNumber = number, title = title, content = content, userChoice = userChoice)

    @Test
    fun 导出_剥标签_选择标注_书名置顶() {
        val out = StoryTxtExporter.build(
            title = "与你重逢的第七年",
            chapters = listOf(
                ch(1, "初见", "[mood:warm]那年春天，我们相遇了。[/mood]", userChoice = "上前打招呼"),
                ch(2, "重逢", "[weather:rain]七年后，伞下重逢。", userChoice = null),
            ),
            chapterHeaderFormat = header,
            choicePrefixFormat = choice,
        )
        // 书名置顶
        assertTrue(out.startsWith("与你重逢的第七年\n\n"))
        // 章头格式化
        assertTrue(out.contains("第 1 话 · 初见"))
        assertTrue(out.contains("第 2 话 · 重逢"))
        // 沉浸标签剥净（不得漏生肉）
        assertFalse("不得含标签", out.contains("[mood:") || out.contains("[/mood]") || out.contains("[weather:"))
        assertTrue(out.contains("那年春天，我们相遇了。"))
        assertTrue(out.contains("七年后，伞下重逢。"))
        // 有选择的章标注 ▶；无选择的章不标注
        assertTrue(out.contains("▶ 你的选择：上前打招呼"))
        assertEquals("仅一处选择标注", 1, Regex("▶ 你的选择：").findAll(out).count())
    }

    @Test
    fun 导出_乱序章节按章号升序() {
        val out = StoryTxtExporter.build(
            title = "书",
            chapters = listOf(ch(3, "丙", "c3"), ch(1, "甲", "c1"), ch(2, "乙", "c2")),
            chapterHeaderFormat = header,
            choicePrefixFormat = choice,
        )
        val i1 = out.indexOf("第 1 话")
        val i2 = out.indexOf("第 2 话")
        val i3 = out.indexOf("第 3 话")
        assertTrue("应按章号升序排布", i1 in 0 until i2 && i2 < i3)
    }

    @Test
    fun 导出_空白选择不标注() {
        val out = StoryTxtExporter.build(
            title = "书",
            chapters = listOf(ch(1, "甲", "正文", userChoice = "   ")),
            chapterHeaderFormat = header,
            choicePrefixFormat = choice,
        )
        assertFalse("空白 userChoice 不应产生标注", out.contains("▶"))
    }
}
