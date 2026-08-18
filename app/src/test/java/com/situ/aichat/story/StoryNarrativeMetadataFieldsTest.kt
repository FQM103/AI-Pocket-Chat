package com.situ.aichat.story

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * METADATA 三个新可选字段的「五面同步」T1（图纸 §7 T1-3 / T1-4·E1）——**D-1 红线修订的单源锁测试**。
 *
 * 五个面（改任一面不改其余 = 静默失效，本文件当场红）：
 * ① `StoryFormatRules` 输出格式的字段说明与示例 ② 结构化 prompt 两模板 ③ `StoryMetadataParser` 归一表与解析
 * ④ `StoryGenerationParsing.buildPayload` → `StoryChapterPayload` ⑤ 尾部识别白名单（读 ③ 的表，自动跟随）。
 *
 * 断言从提案 §4.1 物料 D 与图纸 §3.5 规格独立反推：三字段全可选，缺失 / 「无」/ 老章一律 null 且零异常；
 * 既有 13 字段的解析行为一个字不动。
 */
class StoryNarrativeMetadataFieldsTest {

    private val newFields = listOf("intimacyUpdates", "sceneEndState", "sceneTag")

    private fun outputFormat(choicesEnabled: Boolean = true): String =
        mutableListOf<String>().also { appendStoryCreationOutputFormat(it, choicesEnabled) }.joinToString("\n")

    private fun chapterWithMeta(metaLines: String) =
        "正文正文。\n\n---METADATA---\ntitle: 第七章\nmood: tense\n$metaLines"

    // ── ③ 解析：camel / snake / 中文三种写法 ──

    @Test fun 三字段_camel写法可解析() {
        val r = StoryMetadataParser.parse(
            chapterWithMeta(
                "intimacyUpdates: [里程碑]第一次接吻; [近况]她开始喊他名字\n" +
                    "sceneEndState: 她的公寓客厅｜两人并肩坐着\n" +
                    "sceneTag: 初吻·公寓·沙发上",
            ),
        )
        assertEquals("[里程碑]第一次接吻; [近况]她开始喊他名字", r.intimacyUpdates)
        assertEquals("她的公寓客厅｜两人并肩坐着", r.sceneEndState)
        assertEquals("初吻·公寓·沙发上", r.sceneTag)
        assertEquals("既有字段不受影响", "第七章", r.title)
        assertEquals("正文正文。", r.content)
    }

    @Test fun 三字段_snake写法可解析() {
        val r = StoryMetadataParser.parse(
            chapterWithMeta(
                "intimacy_updates: [近况]牵手\nscene_end_state: 车里｜副驾\nscene_tag: 雨夜·车里·牵手",
            ),
        )
        assertEquals("[近况]牵手", r.intimacyUpdates)
        assertEquals("车里｜副驾", r.sceneEndState)
        assertEquals("雨夜·车里·牵手", r.sceneTag)
    }

    @Test fun 三字段_中文写法可解析() {
        val r = StoryMetadataParser.parse(
            chapterWithMeta("亲密史新增: [近况]牵手\n章末场景状态: 车里｜副驾\n场面标签: 雨夜·车里"),
        )
        assertEquals("[近况]牵手", r.intimacyUpdates)
        assertEquals("车里｜副驾", r.sceneEndState)
        assertEquals("雨夜·车里", r.sceneTag)
    }

    // ── E1：缺失 / 「无」/ 老章 ──

    @Test fun 老章无三字段_解析得null且既有字段照常() {
        val r = StoryMetadataParser.parse(
            chapterWithMeta("summary: 摘要\nhasChoice: true\nchoiceA: 甲\nchoiceB: 乙"),
        )
        assertNull("模型没写 = null（落库口按「缺失」处理）", r.intimacyUpdates)
        assertNull(r.sceneEndState)
        assertNull(r.sceneTag)
        assertEquals("摘要", r.summary)
        assertEquals(listOf("甲", "乙"), r.choiceOptions)
    }

    @Test fun 值为无_解析端如实带回不做归一() {
        // 归一（「无」→ null）是落库口 StoryLedgers.normalizeMeta 的职责，解析端只如实搬运，
        // 因为 sceneEndState 的「缺失=沿用旧值」与「无=清空」两分正靠这个区别（图纸 J5）。
        val r = StoryMetadataParser.parse(chapterWithMeta("intimacyUpdates: 无\nsceneEndState: 无\nsceneTag: 无"))
        assertEquals("无", r.intimacyUpdates)
        assertEquals("无", r.sceneEndState)
        assertEquals("无", r.sceneTag)
    }

    @Test fun 空输入与无元数据块_三字段恒null() {
        val empty = StoryMetadataParser.parse("")
        assertNull(empty.intimacyUpdates)
        assertNull(empty.sceneEndState)
        assertNull(empty.sceneTag)

        val plain = StoryMetadataParser.parse("[mood:tense]只有正文，没有任何元数据。")
        assertNull(plain.intimacyUpdates)
        assertNull(plain.sceneEndState)
        assertNull(plain.sceneTag)
    }

    // ── ⑤ 尾部识别白名单（读 directMap，自动随表扩） ──

    @Test fun 尾部识别_新字段行被认成字段行() {
        val body = "他终于开口。"
        val r = StoryMetadataParser.parse(
            "$body\ntitle: 第七章\nmood: tense\nintimacyUpdates: [近况]她主动挽住他\nsceneTag: 雨夜·车里",
        )
        assertEquals("新字段行必须被吸进尾块，否则会被当正文留给用户看见", body, r.content)
        assertEquals("[近况]她主动挽住他", r.intimacyUpdates)
        assertEquals("雨夜·车里", r.sceneTag)
    }

    // ── 归一表机器点数（防「加了 camel 忘了 snake」）──

    @Test fun directMap恰35键且新键六个齐全() {
        assertEquals("directMap 键数（29 既有 + 3 字段 × camel/snake）", 35, StoryMetadataParser.directMap.size)
        listOf(
            "intimacyupdates" to "intimacyupdates",
            "intimacy_updates" to "intimacyupdates",
            "sceneendstate" to "sceneendstate",
            "scene_end_state" to "sceneendstate",
            "scenetag" to "scenetag",
            "scene_tag" to "scenetag",
        ).forEach { (key, canonical) ->
            assertEquals("归一表缺键 $key", canonical, StoryMetadataParser.directMap[key])
        }
    }

    // ── 五面对表（任一面漏改即红） ──

    @Test fun 五面同步_三字段在每一面都出现() {
        val format = outputFormat()
        val formatClosed = outputFormat(choicesEnabled = false)
        val structuring = buildStoryStructuringPrompt("原文")
        val metaStructuring = buildStoryMetadataStructuringPrompt("原文")
        val payloadJson = Json.encodeToString(
            StoryChapterPayload.serializer(),
            StoryChapterPayload(
                title = "第一章", mood = "tense", content = "正文", hasChoice = false,
                intimacyUpdates = "[近况]x", sceneEndState = "客厅｜两人", sceneTag = "初吻·公寓",
            ),
        )
        // 示例锚（图纸 §4）：两分支逐字相同的三行示例
        val exampleLines = mapOf(
            "intimacyUpdates" to "intimacyUpdates: [近况]她开始主动整理他的衣领",
            "sceneEndState" to "sceneEndState: 无",
            "sceneTag" to "sceneTag: 无",
        )
        // 字段说明（提案 §4.1 物料 D·逐字重打，不从实现引用）
        val descriptionLines = mapOf(
            "intimacyUpdates" to
                "intimacyUpdates: [里程碑]或[近况]开头的 0–3 条本章人物关系新进展，分号分隔；多位主要角色时条目以角色名开头；无新进展写 无",
            "sceneEndState" to "sceneEndState: 一行章末场景状态，格式「地点｜在场人物及状态要点」；章末已离开该场景写 无",
            "sceneTag" to "sceneTag: 一行本章重点场景标签，格式「场景·地点·要点」；本章无重点场景写 无",
        )

        assertTrue("① 新小节标题缺失", format.contains("### 关系叙事字段（叙事连续性）"))
        newFields.forEach { field ->
            assertTrue("① 字段说明缺 $field（物料 D 逐字）", format.contains(descriptionLines.getValue(field)))
            assertTrue("① 开启态示例块缺 $field", format.contains(exampleLines.getValue(field)))
            assertTrue("① 关闭态示例块缺 $field", formatClosed.contains(exampleLines.getValue(field)))
            assertTrue("② 全文结构化模板缺 $field", structuring.contains("\"$field\""))
            assertTrue("② 紧凑结构化模板缺 $field", metaStructuring.contains("\"$field\""))
            assertTrue("③ 归一表缺 $field", StoryMetadataParser.directMap.containsKey(field.lowercase()))
            assertTrue("④ payload 序列化键缺 $field", payloadJson.contains("\"$field\""))
        }
    }

    @Test fun 三字段有意不进二级补全的字段说明与缺失清单() {
        // 图纸 §0.3：可选字段无需补全召回——进了 fieldDescriptions 反而会为可选字段多烧一次 LLM。
        val completion = StoryMetadataParser.buildCompletionPrompt("结尾摘录", newFields + listOf("summary"))
        newFields.forEach { assertTrue("$it 不该出现在二级补全 prompt 里", !completion.contains(it)) }
        assertTrue("既有质量字段照常出现", completion.contains("summary: "))

        val r = StoryMetadataParser.parse(chapterWithMeta("hasChoice: true\nisEnding: false"))
        assertTrue("三字段缺失不许进 missingQualityFieldNames", newFields.none { it in r.missingQualityFieldNames })
    }

    // ── ④ buildPayload 直传（T1-4） ──

    @Test fun buildPayload_三字段直传与缺失() {
        val withFields = StoryGenerationParsing.buildPayload(
            StoryMetadataParser.parse(
                chapterWithMeta("intimacyUpdates: [里程碑]初吻\nsceneEndState: 公寓客厅｜相拥\nsceneTag: 初吻·公寓·沙发"),
            ),
            chapterNumber = 3,
        )
        assertEquals("[里程碑]初吻", withFields.intimacyUpdates)
        assertEquals("公寓客厅｜相拥", withFields.sceneEndState)
        assertEquals("初吻·公寓·沙发", withFields.sceneTag)

        val without = StoryGenerationParsing.buildPayload(
            StoryMetadataParser.parse(chapterWithMeta("summary: 摘要")),
            chapterNumber = 3,
        )
        assertNull(without.intimacyUpdates)
        assertNull(without.sceneEndState)
        assertNull(without.sceneTag)
        assertEquals("兜底行为不受影响", "第七章", without.title)
    }

    // ── 三级结构化：解码与合并（prompt 要了三字段，解码侧就必须接得住） ──

    @Test fun 三级结构化_解码与合并带上三字段() {
        val decoded = StoryGenerationParsing.decodeMetadataFields(
            """{"title":"雨夜","mood":"tense","hasChoice":false,"intimacyUpdates":"[近况]牵手",""" +
                """"sceneEndState":"车里｜副驾","sceneTag":"雨夜·车里","isEnding":false}""",
        )!!
        assertEquals("[近况]牵手", decoded.intimacyUpdates)
        assertEquals("车里｜副驾", decoded.sceneEndState)
        assertEquals("雨夜·车里", decoded.sceneTag)

        // 合并：base 缺失才取结构化侧的值（与既有 12 字段同口径）
        val base = StoryMetadataParser.parse(chapterWithMeta("intimacyUpdates: [里程碑]base 的值"))
        val merged = StoryGenerationParsing.mergeStructuredMetadata(base, decoded)
        assertEquals("base 有值就用 base", "[里程碑]base 的值", merged.intimacyUpdates)
        assertEquals("base 缺失才取结构化侧", "车里｜副驾", merged.sceneEndState)
        assertEquals("雨夜·车里", merged.sceneTag)
    }
}
