package com.situ.aichat.prompt.diary

import com.situ.aichat.ui.diary.DIARY_MOODS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T1：`MOOD: <emoji>` 尾行解析（契约 FABLE5_DIARY_REDESIGN_PROPOSAL §2 F2）。断言从规格独立反推：
 * 只认最后一个非空行 / 命中即整行剥离 / 白名单外优雅 null / 中段 MOOD 字样不受影响。
 */
class DiaryMoodLineParserTest {

    @Test fun `basic parse and strip`() {
        val out = DiaryMoodLineParser.extract("今天很开心。\n去了海边。\nMOOD: 😊")
        assertEquals("今天很开心。\n去了海边。", out.content)
        assertEquals("😊", out.moodEmoji)
    }

    @Test fun `prefix tolerates case, chinese label and fullwidth colon`() {
        assertEquals("🎉", DiaryMoodLineParser.extract("正文\nmood：🎉").moodEmoji)
        assertEquals("😌", DiaryMoodLineParser.extract("正文\nMood : 😌").moodEmoji)
        assertEquals("🌈", DiaryMoodLineParser.extract("正文\n心情：🌈").moodEmoji)
    }

    @Test fun `emoji may carry trailing label text`() {
        val out = DiaryMoodLineParser.extract("正文\nMOOD: 😢 伤心")
        assertEquals("正文", out.content)
        assertEquals("😢", out.moodEmoji)
    }

    @Test fun `trailing blank lines after mood line are handled`() {
        val out = DiaryMoodLineParser.extract("正文第一段\n\nMOOD: 💪\n\n")
        assertEquals("正文第一段", out.content)
        assertEquals("💪", out.moodEmoji)
    }

    @Test fun `non-whitelisted emoji strips line but yields null mood`() {
        val out = DiaryMoodLineParser.extract("正文\nMOOD: 🤖")
        assertEquals("正文", out.content)
        assertNull(out.moodEmoji)
    }

    @Test fun `mood mention mid-text is untouched when last line is normal`() {
        val raw = "今天写了 MOOD: 😊 这一行代码。\n然后去睡觉了。"
        val out = DiaryMoodLineParser.extract(raw)
        assertEquals(raw, out.content)
        assertNull(out.moodEmoji)
    }

    @Test fun `no mood line returns text as-is (trailing whitespace trimmed)`() {
        val out = DiaryMoodLineParser.extract("普通日记正文。\n")
        assertEquals("普通日记正文。", out.content)
        assertNull(out.moodEmoji)
    }

    @Test fun `mood-line-only content degrades to empty content`() {
        val out = DiaryMoodLineParser.extract("MOOD: 😊")
        assertEquals("", out.content)
        assertEquals("😊", out.moodEmoji)
    }

    @Test fun `blank input degrades gracefully`() {
        val out = DiaryMoodLineParser.extract("   \n  ")
        assertEquals("", out.content)
        assertNull(out.moodEmoji)
    }

    @Test fun `whitelist stays in sync with DIARY_MOODS (single source guard)`() {
        // prompt 层不反向依赖 ui 层 → 白名单双份，由本例钉死一致性（新增/改动心情必须双侧同步）。
        assertEquals(DIARY_MOODS.map { it.emoji }.toSet(), DiaryMoodLineParser.ALLOWED_EMOJIS)
    }
}
