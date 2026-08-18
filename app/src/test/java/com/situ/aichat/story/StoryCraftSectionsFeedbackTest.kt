package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-1（图纸 `2026-08-04-故事提示词人物表前置.md` §7）：读者快评段的**画像兜底**分派直测。
 *
 * 规格独立反推，不照抄实现：1 分档在「读者口味画像两层皆空」时点名的那个段并不存在，故换兜底措辞；
 * 其余档位（含「1 分有画像」）逐字不变，注入门 `in 1..3` 语义零变。锁定文本在此**重新打字为字面量**
 * 并与实现常量互钉（双保险·PITFALLS §1e）。
 */
class StoryCraftSectionsFeedbackTest {

    private val C = StoryCraftSections

    /** 1 分原行（有画像时用）——逐字重打，改实现常量必须同步改这里，否则本例红。 */
    private val line1WithProfile =
        "读者对上一章的评价：不满意。本章必须做出明显调整：换掉上一章的场景类型或推进方式；" +
            "对照「读者口味画像」检查是否偏离口味、节奏拖沓或描写重复。"

    /** 1 分兜底行（无画像时用·图纸 §4 W1）——逐字重打。 */
    private val line1NoProfile =
        "读者对上一章的评价：不满意。本章必须做出明显调整：换掉上一章的场景类型或推进方式；" +
            "检查是否节奏拖沓或描写重复。"

    private fun feedback(userRating: Int?, hasTasteProfile: Boolean): List<String> =
        mutableListOf<String>().also { C.appendReaderFeedback(it, userRating, hasTasteProfile) }

    private fun story(prompts: CustomStoryPrompts? = null) = StoryEntity(
        id = "s1", title = "书", genre = "言情", writingStyle = "古风",
        customPromptsJson = prompts?.let { CustomStoryPrompts.encode(it) },
    )

    // ── 双保险 pin：字面量 ↔ 实现常量 ──

    @Test fun 兜底行常量与逐字重打一致() {
        assertEquals(line1NoProfile, C.READER_FEEDBACK_LINE_1_NO_PROFILE)
        assertFalse("兜底行不许再点名画像段", C.READER_FEEDBACK_LINE_1_NO_PROFILE.contains("读者口味画像"))
    }

    // ── E7 / E8：1 分两态 ──

    @Test fun E7_一分且无画像_走兜底行不点名画像() {
        assertEquals(listOf(C.READER_FEEDBACK_HEADER, line1NoProfile, ""), feedback(1, hasTasteProfile = false))
    }

    @Test fun E8_一分且有画像_原措辞逐字不变() {
        assertEquals(listOf(C.READER_FEEDBACK_HEADER, line1WithProfile, ""), feedback(1, hasTasteProfile = true))
    }

    // ── E10：2/3 档与门外值不受 hasTasteProfile 影响 ──

    @Test fun 二三档措辞与画像有无无关() {
        val line3 = "读者对上一章的评价：非常满意。保持这个方向与水准。"
        val line2 = "读者对上一章的评价：一般。本章请在场面展开或剧情推进上换个思路、增强张力，不要重复上一章的写法。"
        assertEquals(listOf(C.READER_FEEDBACK_HEADER, line3, ""), feedback(3, hasTasteProfile = false))
        assertEquals(listOf(C.READER_FEEDBACK_HEADER, line3, ""), feedback(3, hasTasteProfile = true))
        assertEquals(listOf(C.READER_FEEDBACK_HEADER, line2, ""), feedback(2, hasTasteProfile = false))
        assertEquals(listOf(C.READER_FEEDBACK_HEADER, line2, ""), feedback(2, hasTasteProfile = true))
    }

    @Test fun E10_门外值两态都不注入() {
        for (hasProfile in listOf(true, false)) {
            for (rating in listOf(null, 0, 4, -1)) {
                assertTrue(
                    "rating=$rating·hasTasteProfile=$hasProfile 时整段不该出现",
                    feedback(rating, hasProfile).isEmpty(),
                )
            }
        }
    }

    // ── E9：判源必须是三态单源，本书 "" = 主动关 → 兜底行 ──

    @Test fun E9_本书画像主动关掉时判源为空_即使全局有值() {
        val s = story(CustomStoryPrompts(tasteProfile = ""))
        assertEquals("本书 \"\" = 这一层主动关闭，不落到全局", null, C.resolvedTasteProfile(s, "爱看强对抗"))
        // 与画像段不注入自洽：此时快评 1 分必须走兜底行
        val hasProfile = C.resolvedTasteProfile(s, "爱看强对抗") != null
        assertEquals(listOf(C.READER_FEEDBACK_HEADER, line1NoProfile, ""), feedback(1, hasProfile))
    }

    @Test fun 全局有值且本书跟随时判源非空_走原措辞() {
        val hasProfile = C.resolvedTasteProfile(story(), "爱看强对抗") != null
        assertTrue("本书跟随（null）+ 全局有值 → 有画像", hasProfile)
        assertEquals(listOf(C.READER_FEEDBACK_HEADER, line1WithProfile, ""), feedback(1, hasProfile))
    }

    @Test fun 两层皆空时判源为空() {
        assertEquals(null, C.resolvedTasteProfile(story(), null))
        assertEquals(null, C.resolvedTasteProfile(story(), "   "))
    }
}
