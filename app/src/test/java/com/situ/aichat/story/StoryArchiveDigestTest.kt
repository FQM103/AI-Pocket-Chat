package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * StoryArchiveDigestBuilder 纯逻辑单测（ST8·结局档案足迹 + 摘句·断言从契约 §5/D5 独立反推）。
 * 覆盖：天数按日历日差 / 选择数计数 / 章数 / 末段摘句（短段原样·多段取末·超长回取整句·剥标签·空正文）。
 */
class StoryArchiveDigestTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun millisOf(y: Int, m: Int, d: Int): Long =
        LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun story(
        createdAt: Long = 0,
        latestChapterCreatedAt: Long? = null,
        finalEndingType: String? = null,
        cachedLatestChapterNumber: Int? = null,
    ) = StoryEntity(
        id = "s1", title = "书", genre = "言情", writingStyle = "严肃文学",
        createdAt = createdAt, updatedAt = createdAt,
        cachedLatestChapterCreatedAt = latestChapterCreatedAt,
        cachedLatestChapterNumber = cachedLatestChapterNumber,
        finalEndingType = finalEndingType,
    )

    private fun chapter(number: Int, content: String = "正文", userChoice: String? = null, createdAt: Long = 0) =
        StoryChapterEntity(id = "c$number", storyId = "s1", chapterNumber = number, content = content, userChoice = userChoice, createdAt = createdAt)

    // ---- dayCount ----

    @Test
    fun 天数_跨月日历日差() {
        // 5 月 8 日 → 6 月 18 日 = 41 天（mockup 屏八/屏九 基准值）
        assertEquals(41, StoryArchiveDigestBuilder.dayCount(millisOf(2026, 5, 8), millisOf(2026, 6, 18), zone))
    }

    @Test
    fun 天数_同日为0_负值钳0() {
        assertEquals(0, StoryArchiveDigestBuilder.dayCount(millisOf(2026, 5, 8), millisOf(2026, 5, 8), zone))
        assertEquals(0, StoryArchiveDigestBuilder.dayCount(millisOf(2026, 6, 18), millisOf(2026, 5, 8), zone))
    }

    // ---- extractQuote ----

    @Test
    fun 摘句_短末段原样返回() {
        assertEquals("伞下总是两个人。", StoryArchiveDigestBuilder.extractQuote("第一段\n\n伞下总是两个人。"))
    }

    @Test
    fun 摘句_取最后一个非空段落() {
        assertEquals("最后一段。", StoryArchiveDigestBuilder.extractQuote("开头。\n中间。\n最后一段。\n\n  "))
    }

    @Test
    fun 摘句_剥沉浸标签() {
        assertEquals("他转身离开。", StoryArchiveDigestBuilder.extractQuote("[mood:sad]他转身离开。[/mood]"))
    }

    @Test
    fun 摘句_超长末段从末尾回取整句() {
        // 末段 > 60 字：回取末尾 60 字并把开头半句掐到句末标点后。
        val longPara = "从前有一座很大的城，城里住着很多很多的人，他们日出而作日落而息年复一年地过着平凡的日子。" +
            "后来战火烧到了这里。所有人都记得那个雨夜，她撑着伞站在桥头，等一个再也不会回来的人。"
        val q = StoryArchiveDigestBuilder.extractQuote(longPara)
        // 末尾 60 字 → 掐掉开头不完整半句（在末尾窗口内第一个句末标点后起头）→ 干净整句、≤60 字、以句末标点收尾。
        assert(q.length <= StoryArchiveDigestBuilder.QUOTE_MAX_CHARS) { "摘句应 ≤60 字，实际 ${q.length}" }
        assertEquals("后来战火烧到了这里。所有人都记得那个雨夜，她撑着伞站在桥头，等一个再也不会回来的人。", q)
        assert(q.endsWith("。")) { "应以句末标点收尾，实际=$q" }
    }

    @Test
    fun 摘句_空或纯标签正文返回空串() {
        assertEquals("", StoryArchiveDigestBuilder.extractQuote(""))
        assertEquals("", StoryArchiveDigestBuilder.extractQuote("[mood:warm][/mood]"))
        assertEquals("", StoryArchiveDigestBuilder.extractQuote("   \n\n  "))
    }

    // ---- build ----

    @Test
    fun 装配_章数选择数摘句结局类型逐项() {
        val s = story(createdAt = millisOf(2026, 5, 8), latestChapterCreatedAt = millisOf(2026, 6, 18), finalEndingType = "custom")
        val chapters = listOf(
            chapter(2, userChoice = "A"),
            chapter(1, userChoice = "B"),
            chapter(3, content = "尾声。他们终于重逢了。", userChoice = null),  // 末章无选择
        )
        val d = StoryArchiveDigestBuilder.build(s, chapters, zone)
        assertEquals(3, d.chapterCount)
        assertEquals(2, d.choiceCount)               // 仅两章有 userChoice
        assertEquals(41, d.dayCount)
        assertEquals("尾声。他们终于重逢了。", d.quote)  // 取 chapterNumber 最大(3)的末段（短段整段返回）
        assertEquals("custom", d.endingType)
    }

    @Test
    fun 装配_无章节回落缓存与updatedAt() {
        val s = story(createdAt = millisOf(2026, 5, 8), latestChapterCreatedAt = null, cachedLatestChapterNumber = 5)
        val d = StoryArchiveDigestBuilder.build(s, emptyList(), zone)
        assertEquals(5, d.chapterCount)              // 无章节 → 回落 cachedLatestChapterNumber
        assertEquals(0, d.choiceCount)
        assertEquals("", d.quote)
        assertEquals(0, d.dayCount)                  // endMillis 回落 updatedAt(=createdAt) → 同日 0
    }
}
