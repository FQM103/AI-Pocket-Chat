package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节奏偏好两锚注入 T2（卷三 V2·图纸 §3 数据流 + §5 E3）。
 *
 * 两锚 = ①创作 prompt 的 `appendStorySetup`（首/续章共用单锚）②弧线大纲装配（普通弧/终章弧共用）。
 * 断言两件事：**填了就各多一行 labeled 行**、**没填时两条 prompt 与卷二逐字节零变化**（回归钉）。
 */
class StoryPacingPreferenceInjectionTest {

    private val prefix = "节奏偏好（用户指定）："

    private fun story(pacing: String?, otherCustom: Boolean = false) = StoryEntity(
        genre = "悬疑",
        writingStyle = "古风",
        worldSetting = "民国上海",
        plotDirection = "查清旧案",
        customPromptsJson = if (pacing == null && !otherCustom) {
            null
        } else {
            CustomStoryPrompts.encode(
                CustomStoryPrompts(
                    writerIdentity = if (otherCustom) "你是悬疑大师" else null,
                    pacingPreference = pacing,
                ),
            )
        },
    )

    private fun creationPrompt(story: StoryEntity): String = StoryGenerationPromptBuilder.buildFirstChapterCreationPrompt(
        story = story,
        roles = emptyList(),
        characterData = emptyMap(),
        voiceProfiles = "",
        protagonistSpectrum = null,
        protagonistQuality = null,
    )

    private fun outlinePrompt(story: StoryEntity, isFinale: Boolean = false): String =
        StoryGenerationPromptBuilder.buildOutlinePrompt(story, emptyList(), emptyMap(), isFinale = isFinale)

    // ── 有值：两锚各多一行 ──

    @Test fun 创作prompt_填了节奏就多一行labeled行() {
        val text = creationPrompt(story("慢热，多写日常"))
        assertTrue("注入行逐字出现", text.contains("${prefix}慢热，多写日常"))
        assertEquals("恰一行、不重复注入", 1, text.lines().count { it.startsWith(prefix) })
        // 位置：紧跟「剧情方向：」之后（设定区一等公民，与剧情方向并列而非混写）。
        val lines = text.lines()
        val plotIndex = lines.indexOfFirst { it.startsWith("剧情方向：") }
        assertTrue("有剧情方向行", plotIndex >= 0)
        assertEquals("紧跟剧情方向行之后", "${prefix}慢热，多写日常", lines[plotIndex + 1])
    }

    @Test fun 弧线大纲prompt_填了节奏就多一行() {
        val text = outlinePrompt(story("快节奏，别拖"))
        assertTrue(text.contains("${prefix}快节奏，别拖"))
        assertEquals(1, text.lines().count { it.startsWith(prefix) })
    }

    @Test fun 终章弧大纲prompt_同一条注入行() {
        // 终章弧与普通弧共用同一段设定装配，节奏偏好同样到位。
        val text = outlinePrompt(story("留白多一点"), isFinale = true)
        assertTrue(text.contains("${prefix}留白多一点"))
    }

    // ── E3 回归钉：没填 = 逐字节零变化 ──

    @Test fun 没填节奏_创作prompt与无JSON时逐字节相同() {
        val noJson = creationPrompt(story(pacing = null))
        val emptyPacing = creationPrompt(story(pacing = ""))
        val blankPacing = creationPrompt(story(pacing = "   "))
        assertEquals("空串不注入", noJson, emptyPacing)
        assertEquals("纯空白不注入", noJson, blankPacing)
        assertFalse("整条 prompt 不含标签", noJson.contains(prefix))
    }

    @Test fun 没填节奏_弧线大纲prompt与无JSON时逐字节相同() {
        val noJson = outlinePrompt(story(pacing = null))
        assertEquals("空串不注入", noJson, outlinePrompt(story(pacing = "")))
        assertEquals("纯空白不注入", noJson, outlinePrompt(story(pacing = "   ")))
        assertFalse(noJson.contains(prefix))
        val finale = outlinePrompt(story(pacing = null), isFinale = true)
        assertEquals("终章弧同样零变化", finale, outlinePrompt(story(pacing = "  "), isFinale = true))
    }

    @Test fun 老故事只有写作身份字段_节奏不注入且文风行避让() {
        val text = creationPrompt(story(pacing = null, otherCustom = true))
        assertFalse("没填节奏就没有这行", text.contains(prefix))
        // 用户拍板 2026-07-27：填了写作身份 → 文风行整行不注入（原 L4a 的尾注写法已退役）。
        assertFalse("文风整行不得出现", text.lines().any { it.startsWith("文风：") })
        assertTrue("身份段本体照旧在开头", text.startsWith("你是悬疑大师"))
    }

    @Test fun 写作身份也让弧线大纲的文风行避让() {
        val text = outlinePrompt(story(pacing = null, otherCustom = true))
        assertFalse("大纲侧同避让", text.lines().any { it.startsWith("文风：") })
        assertTrue("其余设定行照旧", text.lines().any { it == "类型：悬疑" })
        // 终章弧共用同一装配函数，同样避让。
        val finale = outlinePrompt(story(pacing = null, otherCustom = true), isFinale = true)
        assertFalse("终章弧同避让", finale.lines().any { it.startsWith("文风：") })
        // 没填身份的老故事：大纲侧文风行照旧。
        assertTrue("未填身份照常注入", outlinePrompt(story(pacing = null)).lines().any { it == "文风：古风" })
    }

    @Test fun 注入行标签是单源常量() {
        // §9 锁定文本：此处字面量为「重新打字」的独立副本，与实现常量互为双保险。
        assertEquals(prefix, StoryPromptSections.PACING_PREFERENCE_PREFIX)
    }
}
