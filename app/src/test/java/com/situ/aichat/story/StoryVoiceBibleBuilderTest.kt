package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryVoiceBibleBuilder` tests (P11.1d-1), reverse-derived from iOS `Services/StoryVoiceBibleBuilder.swift`:
 * 角色类型标签 / 用户扮演行 / 关联数据各字段 / examples 截 1000 · systemPrompt 截 1200（卷一 V6）/ 空字段省略 /
 * sortedCharacters（用户优先再按名）/ 多角色 "\n\n" 拼接 / 无角色→""。
 */
class StoryVoiceBibleBuilderTest {

    private fun role(
        name: String,
        type: String = StoryRoleType.SUPPORTING,
        user: Boolean = false,
        desc: String? = null,
        charId: String? = null,
    ) = StoryCharacterRoleEntity(
        id = name, storyId = "s", roleName = name, roleType = type,
        roleDescription = desc, isUserRole = user, characterId = charId,
    )

    private fun data(
        personality: String = "",
        speaking: String = "",
        catchphrases: String = "",
        examples: String = "",
        systemPrompt: String = "",
        nickname: String = "",
        insideJoke: String = "",
        relationship: String? = null,
    ) = StoryVoiceCharacterData(
        personalityDescription = personality, speakingStyle = speaking, catchphrases = catchphrases,
        exampleDialogues = examples, systemPrompt = systemPrompt, nicknameFromChar = nickname,
        insideJoke = insideJoke, currentRelationship = relationship,
    )

    @Test fun empty_roles_yields_empty_string() {
        assertEquals("", StoryVoiceBibleBuilder.buildVoiceProfiles(emptyList(), emptyMap()))
    }

    @Test fun role_without_character_only_basics() {
        val out = StoryVoiceBibleBuilder.buildVoiceProfiles(
            listOf(role("林", type = StoryRoleType.PROTAGONIST, desc = "冷静")),
            emptyMap(),
        )
        assertEquals("【林】\n定位：主角\n设定：冷静", out)
    }

    @Test fun role_type_labels() {
        assertTrue(profileOf(role("A", type = StoryRoleType.PROTAGONIST)).contains("定位：主角"))
        assertTrue(profileOf(role("B", type = StoryRoleType.ANTAGONIST)).contains("定位：反派"))
        assertTrue(profileOf(role("C", type = StoryRoleType.SUPPORTING)).contains("定位：配角"))
        assertTrue(profileOf(role("D", type = "unknown")).contains("定位：配角")) // 默认配角
    }

    @Test fun user_role_marked() {
        assertTrue(profileOf(role("你", user = true)).contains("（用户扮演角色）"))
        assertFalse(profileOf(role("他", user = false)).contains("（用户扮演角色）"))
    }

    @Test fun character_data_fields_formatted() {
        val out = StoryVoiceBibleBuilder.buildVoiceProfiles(
            listOf(role("林", charId = "c1")),
            mapOf(
                "c1" to data(
                    personality = "外冷内热", speaking = "简短", catchphrases = "随你",
                    examples = "「嗯。」", systemPrompt = "高级设定", nickname = "笨蛋",
                    insideJoke = "奶茶梗", relationship = "恋人",
                ),
            ),
        )
        assertTrue(out.contains("性格特征：外冷内热"))
        assertTrue(out.contains("说话风格：简短"))
        assertTrue(out.contains("口头禅/习惯用语：随你"))
        assertTrue(out.contains("对话风格示例：「嗯。」"))
        assertTrue(out.contains("高级设定参考：高级设定"))
        assertTrue(out.contains("对用户的称呼：笨蛋"))
        assertTrue(out.contains("专属梗：奶茶梗"))
        assertTrue(out.contains("与用户的关系：恋人"))
    }

    @Test fun empty_data_fields_are_omitted() {
        val out = profileOf(role("林", charId = "c1"), mapOf("c1" to data()))
        // 仅基本行，无任何深度字段
        assertEquals("【林】\n定位：配角", out)
    }

    /** 卷一 V6（图纸 §4.7）：示例对话 500→1000、systemPrompt 600→1200。 */
    @Test fun examples_truncated_at_1000_and_prompt_at_1200() {
        val out = profileOf(
            role("林", charId = "c1"),
            mapOf("c1" to data(examples = "字".repeat(1_200), systemPrompt = "x".repeat(1_400))),
        )
        assertTrue(out.contains("对话风格示例：" + "字".repeat(1_000) + "…"))
        assertFalse(out.contains("字".repeat(1_001))) // 恰截 1000（±1 精度）
        assertTrue(out.contains("高级设定参考：" + "x".repeat(1_200) + "…"))
        assertFalse(out.contains("x".repeat(1_201)))
    }

    /** 未超上限的原样注入（不因放宽而多出省略号）。 */
    @Test fun under_new_limits_passes_through_without_ellipsis() {
        val out = profileOf(
            role("林", charId = "c1"),
            mapOf("c1" to data(examples = "字".repeat(900), systemPrompt = "x".repeat(1_100))),
        )
        assertTrue(out.contains("对话风格示例：" + "字".repeat(900) + "\n"))
        assertTrue(out.endsWith("高级设定参考：" + "x".repeat(1_100)))
    }

    @Test fun missing_character_data_falls_back_to_basics() {
        // 有 characterId 但 map 里查不到 → 只出基本行
        val out = profileOf(role("林", charId = "missing"), emptyMap())
        assertEquals("【林】\n定位：配角", out)
    }

    @Test fun user_role_sorted_first_then_by_name() {
        val out = StoryVoiceBibleBuilder.buildVoiceProfiles(
            listOf(role("波"), role("祖", user = true), role("阿")),
            emptyMap(),
        )
        val iZu = out.indexOf("【祖】")
        val iA = out.indexOf("【阿】")
        val iBo = out.indexOf("【波】")
        // 用户角色「祖」最前；其余按拼音 阿(a) < 波(b)
        assertTrue(iZu < iA)
        assertTrue(iA < iBo)
    }

    @Test fun multiple_roles_joined_with_blank_line() {
        val out = StoryVoiceBibleBuilder.buildVoiceProfiles(
            listOf(role("甲", type = StoryRoleType.PROTAGONIST), role("乙")),
            emptyMap(),
        )
        assertTrue(out.contains("\n\n")) // 角色间空行分隔
    }

    private fun profileOf(
        r: StoryCharacterRoleEntity,
        dataMap: Map<String, StoryVoiceCharacterData> = emptyMap(),
    ): String = StoryVoiceBibleBuilder.buildVoiceProfiles(listOf(r), dataMap)
}
