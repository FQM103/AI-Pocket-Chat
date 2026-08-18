package com.situ.aichat.ui.story

import com.situ.aichat.story.StoryChapterLength
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「创作设定」组两个纯映射的 T1（图纸二 D2）：行尾值摘要（空/超长/换行）与章长字数反查。
 * 断言从规格反推：摘要口径 = 空→占位文案、首 12 字、超 12 字补省略号、换行折成空格。
 */
class StorySettingsCreativeRowsTest {

    private val empty = "未填写"

    @Test
    fun 摘要_空与纯空白都回占位文案() {
        assertEquals(empty, creativeRowSummary("", empty))
        assertEquals(empty, creativeRowSummary("   ", empty))
        assertEquals(empty, creativeRowSummary("\n\n", empty))
    }

    @Test
    fun 摘要_不超十二字原样显示_不补省略号() {
        assertEquals("对面楼的灯", creativeRowSummary("对面楼的灯", empty))
        assertEquals("十二个字刚刚好一二三四", creativeRowSummary("十二个字刚刚好一二三四", empty))
    }

    @Test
    fun 摘要_恰好十二字不截断_十三字才截() {
        val twelve = "一二三四五六七八九十甲乙"
        assertEquals(12, twelve.length)
        assertEquals(twelve, creativeRowSummary(twelve, empty))

        val thirteen = twelve + "丙"
        assertEquals("$twelve…", creativeRowSummary(thirteen, empty))
    }

    @Test
    fun 摘要_换行折成空格不把行撑成两行() {
        assertEquals("第一行 第二行", creativeRowSummary("第一行\n第二行", empty))
        assertEquals("第一行 第二行", creativeRowSummary("第一行\r\n第二行".replace("\r", ""), empty))
    }

    @Test
    fun 摘要_首尾空白先trim再算长度() {
        assertEquals("凌晨一点的写字楼", creativeRowSummary("  凌晨一点的写字楼  ", empty))
    }

    @Test
    fun 章长_四档按字数反查() {
        assertEquals(StoryChapterLength.SHORT, chapterLengthOf(500))
        assertEquals(StoryChapterLength.MEDIUM, chapterLengthOf(1500))
        assertEquals(StoryChapterLength.LONG, chapterLengthOf(3000))
        assertEquals(StoryChapterLength.EXTRA_LONG, chapterLengthOf(5000))
    }

    @Test
    fun 章长_非四档字数回中档_与既有回显口径一致() {
        assertEquals(StoryChapterLength.MEDIUM, chapterLengthOf(1234))
        assertEquals(StoryChapterLength.MEDIUM, chapterLengthOf(0))
    }
}
