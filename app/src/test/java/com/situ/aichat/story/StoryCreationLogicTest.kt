package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryCreationLogic` + `StoryCreationCatalog` 测试，反推 iOS `StoryCreationView+CreationLogic.swift`
 * / `StoryCreationCatalog`（canCreate / resolvedMaxChapters / defaultStoryTitle / coverColorScheme）。
 */
class StoryCreationLogicTest {

    @Test fun normalized_text_trims_and_nulls_empty() {
        assertEquals("hi", StoryCreationLogic.normalizedText("  hi  "))
        assertNull(StoryCreationLogic.normalizedText("   \n "))
        assertNull(StoryCreationLogic.normalizedText(""))
    }

    @Test fun can_create_preset_needs_a_role() {
        assertTrue(StoryCreationLogic.canCreateStory(false, "", includeUserRole = true, selectedCharacterCount = 0))
        assertTrue(StoryCreationLogic.canCreateStory(false, "", includeUserRole = false, selectedCharacterCount = 1))
        assertFalse(StoryCreationLogic.canCreateStory(false, "", includeUserRole = false, selectedCharacterCount = 0))
    }

    @Test fun can_create_custom_needs_role_and_genre_name() {
        assertTrue(StoryCreationLogic.canCreateStory(true, "修仙", includeUserRole = true, selectedCharacterCount = 0))
        assertFalse(StoryCreationLogic.canCreateStory(true, "  ", includeUserRole = true, selectedCharacterCount = 0)) // 空类型名
        assertFalse(StoryCreationLogic.canCreateStory(true, "修仙", includeUserRole = false, selectedCharacterCount = 0)) // 无角色
    }

    /**
     * D-7 拍板（2026-08-01·图纸三 §3.4 / E16-E17）：本书专属角色也算「有角色」——
     * 一个非空名的自建角色就够开书；**空名不算**（与「空名不落库」同口径，否则会开出一本没有任何角色的书）。
     * 期望从拍板文本独立反推，不看实现。
     */
    @Test fun can_create_with_custom_roles_only() {
        assertTrue(
            "仅 1 个非空名专属角色即可开书",
            StoryCreationLogic.canCreateStory(false, "", includeUserRole = false, selectedCharacterCount = 0, customRoleCount = 1),
        )
        assertFalse(
            "E16：0 聊天角色 + 不入场 + 只有空名草稿（计数 0）→ 仍不可开书",
            StoryCreationLogic.canCreateStory(false, "", includeUserRole = false, selectedCharacterCount = 0, customRoleCount = 0),
        )
        assertTrue(
            "自定义类型：专属角色 + 类型名齐了才行",
            StoryCreationLogic.canCreateStory(true, "修仙", includeUserRole = false, selectedCharacterCount = 0, customRoleCount = 2),
        )
        assertFalse(
            "自定义类型缺类型名：有专属角色也不行",
            StoryCreationLogic.canCreateStory(true, "  ", includeUserRole = false, selectedCharacterCount = 0, customRoleCount = 2),
        )
        assertTrue(
            "默认参兜住模板开书流（不传第五参 = 老行为）",
            StoryCreationLogic.canCreateStory(false, "", includeUserRole = true, selectedCharacterCount = 0),
        )
    }

    // 卷二·单模式化（用户拍板①）：`resolvedMaxChapters` 与 `StorySerialMode` 枚举整体退役，
    // 原「预设四档 / 自定义章数解析」两例随之删除**属预期**——新书的 maxChapters 恒 null（无限连载），
    // 该不变量的看门狗在 StoryCreationViewModelTest.创建故事恒无连载上限。

    @Test fun resolved_genre() {
        assertEquals("修仙", StoryCreationLogic.resolvedGenre(true, "修仙", "言情"))
        assertEquals("自定义", StoryCreationLogic.resolvedGenre(true, "  ", "言情")) // 空类型名兜底
        assertEquals("言情", StoryCreationLogic.resolvedGenre(false, "修仙", "言情"))
    }

    @Test fun default_title_is_always_genre_plus_story() {
        // 用户拍板 2026-07-13（去角色名）：一律「{类型}故事」，不再拼 AI 角色名/用户名/「你的」
        assertEquals("言情故事", StoryCreationLogic.defaultStoryTitle("言情"))
        assertEquals("武侠故事", StoryCreationLogic.defaultStoryTitle("武侠"))
        assertEquals("自定义故事", StoryCreationLogic.defaultStoryTitle("自定义"))
    }

    @Test fun cover_color_scheme_mapping() {
        assertEquals("rose", StoryCreationCatalog.coverColorScheme("言情"))
        assertEquals("crimson", StoryCreationCatalog.coverColorScheme("恐怖"))
        assertEquals("sky", StoryCreationCatalog.coverColorScheme("日常"))
        assertEquals("sky", StoryCreationCatalog.coverColorScheme("自定义未知")) // 兜底
    }

    @Test fun catalog_defaults_match_ios() {
        assertEquals(10, StoryCreationCatalog.genres.size)
        assertEquals("言情", StoryCreationCatalog.genres.first())
        assertEquals(6, StoryCreationCatalog.writingStyles.size)
        assertEquals(StoryChatInfluenceWeight.MEDIUM, StoryCreationCatalog.DEFAULT_CHAT_INFLUENCE)
        assertEquals(StoryChatInfluenceWeight.MEDIUM, StoryCreationCatalog.chatInfluenceWeights[2]) // dropFirst(2).first
    }

    @Test fun style_overridden_hint_only_for_custom_genre_with_identity() {
        // 生效链事实源：resolvedWriterIdentity 只看「身份填没填」，不看类型——但预设类型创建时身份不落库
        // （composeForCreation 仅 isCustomGenre 合成），故提示条件 = 自定义类型 && 身份非空白。
        assertTrue(StoryCreationLogic.styleOverriddenByWriterIdentity(true, "你是修仙大师"))
        assertFalse("身份空串不提示（文风照常生效）", StoryCreationLogic.styleOverriddenByWriterIdentity(true, ""))
        assertFalse("身份纯空白不提示（落库时归 null）", StoryCreationLogic.styleOverriddenByWriterIdentity(true, "   \n "))
        assertFalse("预设类型残留身份草稿不落库，不提示", StoryCreationLogic.styleOverriddenByWriterIdentity(false, "你是修仙大师"))
    }

    @Test fun resolved_title_prefers_preset_else_default() {
        // 模板开书：presetTitle 非空即用（外部行为不变）
        assertEquals("与你重逢的第七年", StoryCreationLogic.resolvedTitle("与你重逢的第七年", "言情"))
        // 空白 preset 回落自动拼名（现为「{类型}故事」）
        assertEquals("言情故事", StoryCreationLogic.resolvedTitle("  ", "言情"))
        // null preset（= 自定义创建流）等同 defaultStoryTitle
        assertEquals(StoryCreationLogic.defaultStoryTitle("科幻"), StoryCreationLogic.resolvedTitle(null, "科幻"))
        assertEquals("科幻故事", StoryCreationLogic.resolvedTitle(null, "科幻"))
    }
}
