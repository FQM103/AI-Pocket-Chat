package com.situ.aichat.story

import com.situ.aichat.data.local.dao.StoryChapterSummaryRow
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ST5 世界书注入锚点测试：worldInfoSection 传入时的落位断言（首章在**名片区**之后、「整体大纲」之前 /
 * 续章在「前情回顾」段之前·哨兵串定位），null/空时输出与不传参字节级一致（既有段与 METADATA 红线零改动）。
 * 首章锚点措辞由「『故事设定』段之后」修订为「名片区之后」= 2026-08-04 人物表前置用户拍板（契约 WORLDBOOK 已回填）。
 * 既有 prompt 内容断言见 [StoryGenerationPromptBuilderCreationTest]（本档不动它，作字节级基准）。
 */
class StoryGenerationPromptBuilderWorldInfoTest {

    private val B = StoryGenerationPromptBuilder

    private fun story(
        storyOutline: String? = "整体走向",
        pendingChapterBeats: String? = null,
        rewriteInstruction: String? = null,
    ) = StoryEntity(
        genre = "言情", writingStyle = "古风", maxChapters = 20,
        storyOutline = storyOutline, pendingChapterBeats = pendingChapterBeats,
        rewriteInstruction = rewriteInstruction,
    )

    private fun firstPrompt(story: StoryEntity, worldInfo: String?, voiceProfiles: String = "") =
        B.buildFirstChapterCreationPrompt(
            story = story, roles = emptyList(), characterData = emptyMap(), voiceProfiles = voiceProfiles,
            protagonistSpectrum = null, protagonistQuality = null, worldInfoSection = worldInfo,
        )

    private fun nextPrompt(story: StoryEntity, worldInfo: String?) = B.buildNextChapterCreationPrompt(
        story = story, chapterNumber = 5, roles = emptyList(), characterData = emptyMap(),
        voiceProfiles = "", chatInfluence = "",
        latestChapter = StoryChapterEntity(chapterNumber = 4, content = "上一章结尾", hasChoice = true, userChoice = "选A"),
        chapterSummaries = listOf(StoryChapterSummaryRow(4, "第四章摘要")),
        worldInfoSection = worldInfo,
    )

    // ── 首章：落位 = **名片区之后**（设定→角色→区分度→声音档案）、「整体大纲」之前（2026-08-04 人物表前置修订）──

    @Test
    fun first_chapter_world_info_after_name_card_block() {
        // voiceProfiles 给非空值：声音档案段真的存在，下面那条邻位钉才不是「-1 < x」式的假绿
        val out = firstPrompt(story(), "灵脉与门派的设定内容", voiceProfiles = "【甲】说话温柔")
        assertTrue(out.contains("## 世界观设定"))
        assertTrue(out.contains("灵脉与门派的设定内容"))
        assertTrue(out.indexOf("## 故事设定") < out.indexOf("## 世界观设定"))
        assertTrue(out.indexOf("## 世界观设定") < out.indexOf("## 整体大纲（参考方向，不要生硬复述大纲内容）"))
        // 人物表前置：角色段与声音档案段现在都排在世界观段**之前**（原断言 世界观 < 角色 已翻转）
        assertTrue(out.indexOf("## 角色") in 0 until out.indexOf("## 世界观设定"))
        val voiceIdx = out.indexOf("## 角色声音档案（确保每个角色说话方式有辨识度）")
        assertTrue("新锚点邻位钉：声音档案段是世界观段的直接前驱块", voiceIdx in 0 until out.indexOf("## 世界观设定"))
        // 红线：既有段与输出格式不受影响
        assertTrue(out.contains("---METADATA---"))
    }

    @Test
    fun first_chapter_null_or_blank_world_info_byte_identical() {
        val s = story()
        val baseline = B.buildFirstChapterCreationPrompt(
            story = s, roles = emptyList(), characterData = emptyMap(), voiceProfiles = "",
            protagonistSpectrum = null, protagonistQuality = null,
        )
        assertEquals(baseline, firstPrompt(s, null))
        assertEquals(baseline, firstPrompt(s, ""))
        assertFalse(baseline.contains("## 世界观设定"))
    }

    // ── 续章：落位 =「前情回顾」段之前（动态指令区之后） ──

    @Test
    fun next_chapter_world_info_before_recap_after_directives() {
        val out = nextPrompt(story(pendingChapterBeats = "方向A", rewriteInstruction = "节奏快些"), "边关烽火的设定内容")
        assertTrue(out.contains("## 世界观设定"))
        assertTrue(out.contains("边关烽火的设定内容"))
        assertTrue(out.indexOf(StoryCraftSections.DRAFT_HEADER) < out.indexOf("## 世界观设定"))
        assertTrue(out.indexOf("## 重写指令") < out.indexOf("## 世界观设定"))
        assertTrue(out.indexOf("## 世界观设定") < out.indexOf("## 前情回顾"))
        assertTrue(out.contains("---METADATA---"))
    }

    @Test
    fun next_chapter_null_or_blank_world_info_byte_identical() {
        val s = story()
        val baseline = B.buildNextChapterCreationPrompt(
            story = s, chapterNumber = 5, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "",
            latestChapter = StoryChapterEntity(chapterNumber = 4, content = "上一章结尾", hasChoice = true, userChoice = "选A"),
            chapterSummaries = listOf(StoryChapterSummaryRow(4, "第四章摘要")),
        )
        assertEquals(baseline, nextPrompt(s, null))
        assertEquals(baseline, nextPrompt(s, ""))
        assertFalse(baseline.contains("## 世界观设定"))
    }
}
