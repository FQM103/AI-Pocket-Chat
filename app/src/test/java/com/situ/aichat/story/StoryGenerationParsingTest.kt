package com.situ.aichat.story

import com.situ.aichat.data.local.dao.StoryChapterSummaryRow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryGenerationParsing` (11.1e-1) payload 组装层测试，反推 iOS `StoryGenerationService(.swift/+Parsing)`：
 * buildPayload 兜底 / encodeChoiceOptions / isContentTruncated 句末标点 / payloadWithContinuation /
 * shouldCompressSummary 阈值 + 区间。
 */
class StoryGenerationParsingTest {

    private val P = StoryGenerationParsing

    /** 复用单个宽松 Json 实例（避免每次用例新建，消除编译警告）。 */
    private val lenientJson = Json { ignoreUnknownKeys = true }

    private fun parseResult(
        content: String = "正文",
        title: String? = null,
        teaser: String? = null,
        mood: String? = null,
        hasChoice: Boolean? = null,
        choicePrompt: String? = null,
        choiceOptions: List<String>? = null,
        summary: String? = null,
        currentArc: String? = null,
        characterStates: String? = null,
        openThreads: String? = null,
        nextChapterBeats: String? = null,
        isEnding: Boolean? = null,
    ) = StoryMetadataParser.ParseResult(
        content = content, title = title, teaser = teaser, mood = mood, hasChoice = hasChoice,
        choicePrompt = choicePrompt, choiceOptions = choiceOptions, summary = summary, currentArc = currentArc,
        characterStates = characterStates, openThreads = openThreads, nextChapterBeats = nextChapterBeats,
        isEnding = isEnding,
    )

    // ── StoryChapterPayload @Serializable ──

    @Test fun payload_serializes_with_ios_keys() {
        val p = StoryChapterPayload(
            title = "第一章", teaser = "引子", mood = "tense", content = "正文",
            hasChoice = true, choicePrompt = "你决定", choiceOptions = listOf("A", "B"),
            summary = "摘要", currentArc = "弧线", isEnding = false,
            characterStates = "状态", openThreads = "伏笔", nextChapterBeats = "方向",
        )
        val s = Json.encodeToString(StoryChapterPayload.serializer(), p)
        // 键名与 iOS JSON 一致
        listOf(
            "\"title\"", "\"teaser\"", "\"mood\"", "\"content\"", "\"hasChoice\"", "\"choicePrompt\"",
            "\"choiceOptions\"", "\"summary\"", "\"currentArc\"", "\"isEnding\"", "\"characterStates\"",
            "\"openThreads\"", "\"nextChapterBeats\"",
        ).forEach { assertTrue("缺键 $it", s.contains(it)) }

        // 干净 JSON 可解码回原值（必填齐全 + 可选缺失走默认 null）
        val decoded = lenientJson.decodeFromString(
            StoryChapterPayload.serializer(),
            """{"title":"T","mood":"warm","content":"C","hasChoice":false}""",
        )
        assertEquals("T", decoded.title)
        assertEquals("warm", decoded.mood)
        assertFalse(decoded.hasChoice)
        assertNull(decoded.teaser)
        assertNull(decoded.choiceOptions)
        assertNull(decoded.isEnding)
    }

    // ── buildPayload ──

    @Test fun build_payload_maps_all_fields() {
        val p = P.buildPayload(
            parseResult(
                content = "正文内容", title = "标题", teaser = "引子", mood = "romantic", hasChoice = true,
                choicePrompt = "你决定", choiceOptions = listOf("A", "B"), summary = "摘", currentArc = "弧",
                characterStates = "态", openThreads = "伏", nextChapterBeats = "向", isEnding = false,
            ),
            chapterNumber = 3,
        )
        assertEquals("标题", p.title)
        assertEquals("引子", p.teaser)
        assertEquals("romantic", p.mood)
        assertEquals("正文内容", p.content)
        assertTrue(p.hasChoice)
        assertEquals(listOf("A", "B"), p.choiceOptions)
        assertEquals("向", p.nextChapterBeats)
        assertEquals(false, p.isEnding)
    }

    @Test fun build_payload_title_and_mood_fallbacks() {
        val p = P.buildPayload(parseResult(title = null, mood = null), chapterNumber = 7)
        assertEquals("第7章", p.title)
        assertEquals("peaceful", p.mood)
    }

    @Test fun build_payload_haschoice_inference() {
        // hasChoice 缺失 + 有选项 → true
        assertTrue(P.buildPayload(parseResult(hasChoice = null, choiceOptions = listOf("A")), 1).hasChoice)
        // hasChoice 缺失 + 有提示语 → true
        assertTrue(P.buildPayload(parseResult(hasChoice = null, choicePrompt = "选吧"), 1).hasChoice)
        // hasChoice 缺失 + 既无选项也无提示 → false
        assertFalse(P.buildPayload(parseResult(hasChoice = null), 1).hasChoice)
        // hasChoice 缺失 + 空选项列表 → false
        assertFalse(P.buildPayload(parseResult(hasChoice = null, choiceOptions = emptyList()), 1).hasChoice)
        // 显式 false 即便有选项 → 仍 false（非空时直接用 result.hasChoice）
        assertFalse(P.buildPayload(parseResult(hasChoice = false, choiceOptions = listOf("A", "B")), 1).hasChoice)
    }

    // ── mergeMetadataCompletion（第二级轻量补全合并） ──

    @Test fun merge_completion_base_wins_content_title_mood_and_nonnull_fields() {
        val base = parseResult(
            content = "正文", title = "基础标题", mood = "warm",
            summary = "基础摘要", // 非空 → base 胜
            hasChoice = null, teaser = null, currentArc = null, isEnding = null,
        )
        val completion = parseResult(
            content = "应忽略", title = "应忽略", mood = "应忽略",
            teaser = "补充引子", summary = "补充摘要", currentArc = "补充弧线",
            hasChoice = true, choicePrompt = "补充提示", choiceOptions = listOf("X"),
            characterStates = "补充状态", openThreads = "补充伏笔", nextChapterBeats = "补充方向", isEnding = false,
        )
        val merged = StoryGenerationParsing.mergeMetadataCompletion(base, completion)

        // content/title/mood 恒取 base，不合并
        assertEquals("正文", merged.content)
        assertEquals("基础标题", merged.title)
        assertEquals("warm", merged.mood)
        // base 非空 → base 胜
        assertEquals("基础摘要", merged.summary)
        // base 缺 → 用 completion 补
        assertEquals("补充引子", merged.teaser)
        assertEquals("补充弧线", merged.currentArc)
        assertEquals(true, merged.hasChoice)
        assertEquals("补充提示", merged.choicePrompt)
        assertEquals(listOf("X"), merged.choiceOptions)
        assertEquals("补充状态", merged.characterStates)
        assertEquals("补充伏笔", merged.openThreads)
        assertEquals("补充方向", merged.nextChapterBeats)
        assertEquals(false, merged.isEnding)
    }

    // ── encodeChoiceOptions ──

    @Test fun encode_choice_options() {
        assertNull(P.encodeChoiceOptions(null))
        assertNull(P.encodeChoiceOptions(emptyList()))
        assertEquals("""["主动打招呼","假装没看见"]""", P.encodeChoiceOptions(listOf("主动打招呼", "假装没看见")))
    }

    // ── isContentTruncated ──

    @Test fun is_content_truncated_by_ending_punctuation() {
        // 正常句末标点 → 未截断
        listOf("他笑了。", "真的吗？", "住手！", "余韵悠长…", "「再见」", "【完】", "（注）", "结束”", "结束’", "ok.", "ok!", "ok?", "end\"", "end'", "end)", "end]").forEach {
            assertFalse("应判未截断: $it", P.isContentTruncated(it))
        }
        // 非句末字符结尾 → 截断
        assertTrue(P.isContentTruncated("他正准备说"))
        assertTrue(P.isContentTruncated("半句话被切"))
        // 空 / 纯空白 → 截断
        assertTrue(P.isContentTruncated(""))
        assertTrue(P.isContentTruncated("   \n  "))
        // 尾随空白会被 trim，看 trim 后末字符
        assertFalse(P.isContentTruncated("他笑了。  \n"))
    }

    // ── payloadWithContinuation ──

    @Test fun payload_with_continuation_replaces_only_content() {
        val base = StoryChapterPayload(title = "T", mood = "warm", content = "原内容", hasChoice = true, choiceOptions = listOf("A"))
        val cont = P.payloadWithContinuation(base, "原内容续写完成。")
        assertEquals("原内容续写完成。", cont.content)
        assertEquals("T", cont.title)
        assertEquals("warm", cont.mood)
        assertTrue(cont.hasChoice)
        assertEquals(listOf("A"), cont.choiceOptions)
    }

    // ── shouldCompressSummary ──

    private fun rows(vararg pairs: Pair<Int, Int>) =
        pairs.map { (num, len) -> StoryChapterSummaryRow(num, "摘".repeat(len)) }

    @Test fun should_compress_interval_gate() {
        // 距上次压缩 < 8 → false（即便字数超阈值）
        val big = rows(1 to 400, 2 to 400, 3 to 400, 4 to 400, 5 to 400, 6 to 400, 7 to 400)
        assertFalse(P.shouldCompressSummary(lastCompressedAtChapter = 0, chapterNumber = 7, chapterSummaries = big))
    }

    @Test fun should_compress_word_threshold() {
        // 间隔达标(8)，8 章各 400 = 3200 > 3000 → true
        val over = (1..8).map { StoryChapterSummaryRow(it, "摘".repeat(400)) }
        assertTrue(P.shouldCompressSummary(0, 8, over))
        // 间隔达标，8 章各 300 = 2400 ≤ 3000 → false
        val under = (1..8).map { StoryChapterSummaryRow(it, "摘".repeat(300)) }
        assertFalse(P.shouldCompressSummary(0, 8, under))
    }

    @Test fun should_compress_counts_only_after_last_compressed() {
        // lastCompressed=4, chapterNumber=12（间隔 8）。仅 5..12 计入；第 3 章的超长摘要应被排除。
        val summaries = buildList {
            add(StoryChapterSummaryRow(3, "摘".repeat(9_999))) // 早于 lastCompressed，排除
            for (n in 5..12) add(StoryChapterSummaryRow(n, "摘".repeat(400))) // 8×400=3200>3000
        }
        assertTrue(P.shouldCompressSummary(4, 12, summaries))

        // 把 5..12 降到各 300（2400≤3000）→ 即便有第 3 章超长摘要也 false（被排除）
        val excluded = buildList {
            add(StoryChapterSummaryRow(3, "摘".repeat(9_999)))
            for (n in 5..12) add(StoryChapterSummaryRow(n, "摘".repeat(300)))
        }
        assertFalse(P.shouldCompressSummary(4, 12, excluded))
    }

    @Test fun should_compress_null_last_compressed_treated_as_zero() {
        val over = (1..8).map { StoryChapterSummaryRow(it, "摘".repeat(400)) }
        assertTrue(P.shouldCompressSummary(lastCompressedAtChapter = null, chapterNumber = 8, chapterSummaries = over))
    }

    // ── buildNewSummariesBlock（11.1e-6，1:1 iOS compressSummaryChainIfNeeded :98-104）──

    @Test fun new_summaries_block_formats_and_joins() {
        val summaries = listOf(
            StoryChapterSummaryRow(1, "开端"),
            StoryChapterSummaryRow(2, "升温"),
            StoryChapterSummaryRow(3, "转折"),
        )
        assertEquals(
            "第1章：开端\n第2章：升温\n第3章：转折",
            P.buildNewSummariesBlock(summaries, lastCompressedChapter = 0, currentChapter = 3),
        )
    }

    @Test fun new_summaries_block_filters_to_interval() {
        // 仅 (lastCompressed=2, current=4]：排除 ≤2 与 >4。
        val summaries = (1..5).map { StoryChapterSummaryRow(it, "S$it") }
        assertEquals(
            "第3章：S3\n第4章：S4",
            P.buildNewSummariesBlock(summaries, lastCompressedChapter = 2, currentChapter = 4),
        )
    }

    @Test fun new_summaries_block_skips_null_and_empty_summaries() {
        val summaries = listOf(
            StoryChapterSummaryRow(1, "有"),
            StoryChapterSummaryRow(2, null),
            StoryChapterSummaryRow(3, ""),
            StoryChapterSummaryRow(4, "也有"),
        )
        assertEquals(
            "第1章：有\n第4章：也有",
            P.buildNewSummariesBlock(summaries, lastCompressedChapter = 0, currentChapter = 4),
        )
    }

    @Test fun new_summaries_block_empty_when_no_usable_summaries() {
        // 区间内全为 null/空 → 空串（调用方据此跳过压缩）。
        val summaries = listOf(StoryChapterSummaryRow(1, null), StoryChapterSummaryRow(2, ""))
        assertEquals("", P.buildNewSummariesBlock(summaries, lastCompressedChapter = 0, currentChapter = 2))
        assertEquals("", P.buildNewSummariesBlock(emptyList(), lastCompressedChapter = 0, currentChapter = 5))
    }
}
