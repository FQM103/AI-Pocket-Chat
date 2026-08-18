package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 第三级·元数据 JSON 解码与合并（图纸一 §3.3-2 / §3.3-3）测试。
 *
 * 断言从图纸规格独立反推：解码复用候选串管线（围栏 / think / 花括号子串）、空串折叠 null、mood 过
 * [StoryMoods] 归一、垃圾→null；合并 **content 恒取 base**、**title/mood 必须参与合并**（这正是与
 * [StoryGenerationParsing.mergeMetadataCompletion] 的本质区别，漏掉即 LLM 整理白做）。
 */
class StoryGenerationParsingMetadataFieldsTest {

    private val P = StoryGenerationParsing

    private fun result(
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

    // ── decodeMetadataFields ──

    @Test fun decodes_compact_single_line_json() {
        val decoded = P.decodeMetadataFields(
            """{"title":"雨夜的真相","teaser":"真相浮出水面","mood":"tense","hasChoice":true,""" +
                """"choicePrompt":"你决定...","choiceOptions":["追上去","装作没看见"],"summary":"她发现了秘密",""" +
                """"currentArc":"关系面临考验","characterStates":"林悦（震惊）","openThreads":"未读消息",""" +
                """"nextChapterBeats":"若选「追上去」→ 爆发争执","isEnding":false}""",
        )!!

        assertEquals("雨夜的真相", decoded.title)
        assertEquals("真相浮出水面", decoded.teaser)
        assertEquals("tense", decoded.mood)
        assertEquals(true, decoded.hasChoice)
        assertEquals("你决定...", decoded.choicePrompt)
        assertEquals(listOf("追上去", "装作没看见"), decoded.choiceOptions)
        assertEquals("她发现了秘密", decoded.summary)
        assertEquals("关系面临考验", decoded.currentArc)
        assertEquals("林悦（震惊）", decoded.characterStates)
        assertEquals("未读消息", decoded.openThreads)
        assertEquals("若选「追上去」→ 爆发争执", decoded.nextChapterBeats)
        assertEquals(false, decoded.isEnding)
        assertEquals("正文恒由代码切分侧提供，解码侧只留空串占位", "", decoded.content)
    }

    @Test fun decodes_json_wrapped_in_code_fence() {
        val decoded = P.decodeMetadataFields("```json\n{\"title\":\"第七章\",\"mood\":\"warm\"}\n```")!!
        assertEquals("第七章", decoded.title)
        assertEquals("warm", decoded.mood)
    }

    @Test fun decodes_json_after_thinking_tags() {
        val decoded = P.decodeMetadataFields("<think>先想想字段怎么填</think>\n{\"title\":\"第七章\",\"mood\":\"dark\"}")!!
        assertEquals("第七章", decoded.title)
        assertEquals("dark", decoded.mood)
    }

    @Test fun folds_empty_strings_to_null() {
        val decoded = P.decodeMetadataFields("""{"title":"","teaser":"","summary":"","choiceOptions":[]}""")!!
        assertNull(decoded.title)
        assertNull(decoded.teaser)
        assertNull(decoded.summary)
        assertNull("空选项列表折叠 null", decoded.choiceOptions)
    }

    @Test fun normalizes_mood_and_drops_unknown_value() {
        assertEquals("tense", P.decodeMetadataFields("""{"mood":"紧张"}""")!!.mood)
        assertEquals("warm", P.decodeMetadataFields("""{"mood":"WARM"}""")!!.mood)
        assertNull("词表外的怪值丢弃（交 buildPayload 兜 peaceful）", P.decodeMetadataFields("""{"mood":"超级紧张"}""")!!.mood)
    }

    @Test fun returns_null_for_garbage() {
        assertNull(P.decodeMetadataFields("我没办法把这段整理成 JSON。"))
        assertNull(P.decodeMetadataFields(""))
        assertNull(P.decodeMetadataFields("{ 这不是合法 JSON "))
    }

    // ── mergeStructuredMetadata ──

    @Test fun merge_takes_title_and_mood_from_structured_when_base_lacks_them() {
        val base = result(content = "代码切出来的正文。", summary = "代码解析到的摘要")
        val structured = result(content = "", title = "第七章", mood = "tense", summary = "LLM 的摘要", isEnding = true)

        val merged = P.mergeStructuredMetadata(base, structured)

        assertEquals("base 缺 title ⇒ 采用 LLM 值", "第七章", merged.title)
        assertEquals("base 缺 mood ⇒ 采用 LLM 值", "tense", merged.mood)
        assertEquals("base 已有的字段不被覆盖", "代码解析到的摘要", merged.summary)
        assertEquals("base 缺的可选字段照样补上", true, merged.isEnding)
    }

    @Test fun merge_always_keeps_base_content() {
        val base = result(content = "代码切出来的正文，一个字都不能少。")
        val structured = result(content = "模型改写过的正文（绝不许出现在章节里）", title = "第七章")

        val merged = P.mergeStructuredMetadata(base, structured)

        assertEquals("正文恒取 base——LLM 文本永不进 content", "代码切出来的正文，一个字都不能少。", merged.content)
        assertEquals("第七章", merged.title)
    }

    @Test fun merge_base_wins_on_every_field_it_already_has() {
        val base = result(
            content = "正文", title = "base 标题", teaser = "base 预告", mood = "warm", hasChoice = false,
            choicePrompt = "base 提示", choiceOptions = listOf("base A"), summary = "base 摘要",
            currentArc = "base 弧线", characterStates = "base 状态", openThreads = "base 伏笔",
            nextChapterBeats = "base 方向", isEnding = false,
        )
        val structured = result(
            content = "", title = "llm 标题", teaser = "llm 预告", mood = "dark", hasChoice = true,
            choicePrompt = "llm 提示", choiceOptions = listOf("llm A"), summary = "llm 摘要",
            currentArc = "llm 弧线", characterStates = "llm 状态", openThreads = "llm 伏笔",
            nextChapterBeats = "llm 方向", isEnding = true,
        )

        assertEquals(base, P.mergeStructuredMetadata(base, structured))
    }
}
