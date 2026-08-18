package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文字忌口三层取值 [StoryPromptSections.resolvedBannedExpressions] 的规格测试（原图纸 §7 T1-2 + 卷二 J1 九宫格）。
 *
 * 期望值从优先级表**独立反推**（不看实现分支）。**卷二 J1 起本书层是真三态**（口径与场面节拍统一）：
 *
 * | 本故事 | 全局 | 结果 |
 * |---|---|---|
 * | 非空文本 | 任意 | 本故事文本 |
 * | `""`/纯空白（= 本书关闭） | 任意 | **不注入**（null）← J1 有意行为变化 |
 * | null（跟随全局） | null（从未设置） | 内置默认 |
 * | null | `""`（主动清空） | **不注入**（null） |
 * | null | 非空 | 全局文本 |
 *
 * 外加边界 E6（老故事 customPromptsJson == null）、E15（首装 DataStore 空）。
 */
class StoryBannedExpressionsResolutionTest {

    private val builtInDefault = StoryWritingTechniques.bannedExpressionsBaseline

    private fun story(customPromptsJson: String? = null) =
        StoryEntity(genre = "言情", writingStyle = "古风", customPromptsJson = customPromptsJson)

    private fun storyWithBanned(text: String?) =
        story(CustomStoryPrompts.encode(CustomStoryPrompts(bannedExpressions = text)))

    // ── 优先级表逐行 ──

    @Test fun row1_per_story_wins_over_any_global_state() {
        val s = storyWithBanned("本书忌口：不许写雨")
        // 全局三态全试一遍，本故事文本恒胜出
        assertEquals("本书忌口：不许写雨", StoryPromptSections.resolvedBannedExpressions(s, null))
        assertEquals("本书忌口：不许写雨", StoryPromptSections.resolvedBannedExpressions(s, ""))
        assertEquals("本书忌口：不许写雨", StoryPromptSections.resolvedBannedExpressions(s, "全局忌口"))
    }

    @Test fun row2_never_set_global_falls_back_to_builtin_default() {
        assertEquals(builtInDefault, StoryPromptSections.resolvedBannedExpressions(story(), null))
        assertEquals(builtInDefault, StoryPromptSections.resolvedBannedExpressions(storyWithBanned(null), null))
    }

    @Test fun row3_globally_cleared_injects_nothing() {
        // "" = 用户主动全删后确认 → 整段不注入；**绝不许**回退成内置默认（否则用户永远删不掉）
        assertNull(StoryPromptSections.resolvedBannedExpressions(story(), ""))
        assertNull(StoryPromptSections.resolvedBannedExpressions(storyWithBanned(null), ""))
        // 全局只剩空白字符同样视作「不注入」（空白不是有效指令）
        assertNull(StoryPromptSections.resolvedBannedExpressions(story(), "   "))
    }

    @Test fun row4_global_custom_text_used_when_story_has_no_override() {
        assertEquals("全局忌口：少写心理旁白", StoryPromptSections.resolvedBannedExpressions(story(), "全局忌口：少写心理旁白"))
    }

    // ── 边界 ──

    /**
     * J1 **有意行为变化**（卷二·产品定位拍板「须可整本关」是刚需）：本书层存了空串 / 纯空白 = 本书关闭，
     * 三层链到此为止，**不再落到全局或内置默认**。变化前这三格分别产出「全局值 / 内置默认 / null」。
     */
    @Test fun j1_blank_per_story_override_turns_the_section_off_for_this_book() {
        for (blank in listOf(storyWithBanned(""), storyWithBanned("   　\n "))) {
            assertNull("本书关闭时全局有值也不许注入", StoryPromptSections.resolvedBannedExpressions(blank, "全局值"))
            assertNull("本书关闭时不许回退内置默认", StoryPromptSections.resolvedBannedExpressions(blank, null))
            assertNull(StoryPromptSections.resolvedBannedExpressions(blank, ""))
        }
    }

    /**
     * J1 九宫格：本书 {null / 空白 / 文本} × 全局 {null / "" / 文本}，逐格钉死。
     * 「本书 null」与「本书文本」六格 = 变化前后**逐字节相同**的回归钉；「本书空白」三格 = 有意变化格。
     */
    @Test fun j1_full_matrix_three_by_three() {
        val globals = listOf(null, "", "全局忌口")
        // 行 1：本书 null（跟随全局）
        val follow = listOf(builtInDefault, null, "全局忌口")
        globals.forEachIndexed { i, g ->
            assertEquals("本书 null × 全局[$i]", follow[i], StoryPromptSections.resolvedBannedExpressions(storyWithBanned(null), g))
            assertEquals("无 JSON × 全局[$i]", follow[i], StoryPromptSections.resolvedBannedExpressions(story(), g))
        }
        // 行 2：本书空白（本书关闭·有意变化格）
        globals.forEach { g ->
            assertNull("本书空白 × 全局[$g] 必须整段不注入", StoryPromptSections.resolvedBannedExpressions(storyWithBanned(" "), g))
        }
        // 行 3：本书文本（本书覆盖，全局三态一律让位）
        globals.forEach { g ->
            assertEquals("本书忌口", StoryPromptSections.resolvedBannedExpressions(storyWithBanned("本书忌口"), g))
        }
    }

    @Test fun e6_legacy_story_without_custom_prompts_json_does_not_crash() {
        assertEquals(builtInDefault, StoryPromptSections.resolvedBannedExpressions(story(null), null))
        // 老 JSON（只有前三字段，无 bannedExpressions 键）同样跟随全局
        val legacy = """{"genreTechniques":"技法","writerIdentity":"身份","writingRules":"规则"}"""
        assertEquals("全局值", StoryPromptSections.resolvedBannedExpressions(story(legacy), "全局值"))
        // 解码失败的脏 JSON 也不许崩，退到全局/默认
        assertEquals(builtInDefault, StoryPromptSections.resolvedBannedExpressions(story("not json"), null))
    }

    @Test fun e15_first_launch_empty_datastore_reads_null_and_gets_default() {
        // DataStore 无键 → AppSettings.storyBannedExpressions == null → 内置默认（不是空串、不是不注入）
        val fresh = StoryPromptSections.resolvedBannedExpressions(story(), null)
        assertTrue(fresh!!.startsWith("### 别写出 AI 味"))
    }

    // ── J2 正交性：忌口不再拼进写作规则 ──

    @Test fun writing_rules_no_longer_carry_banned_expressions() {
        // 默认路径
        val preset = StoryPromptSections.resolvedWritingRules(story())
        assertTrue(preset.startsWith("### 写作铁律"))
        assertFalse(preset.contains("### 别写出 AI 味"))

        // 用户接管路径：整段就是用户文本，不再尾附忌口（J2 = 双重注入的根治写法）
        val custom = story(CustomStoryPrompts.encode(CustomStoryPrompts(writingRules = "自定义铁律")))
        assertEquals("自定义铁律", StoryPromptSections.resolvedWritingRules(custom))

        // 纯空白规则视同没填（与 effectiveWriterIdentity 同口径）
        val blank = story(CustomStoryPrompts.encode(CustomStoryPrompts(writingRules = "   ")))
        assertTrue(StoryPromptSections.resolvedWritingRules(blank).startsWith("### 写作铁律"))
    }
}
