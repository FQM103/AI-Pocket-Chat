package com.situ.aichat.ui.character

import com.situ.aichat.data.local.entity.CharacterEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑保存两个副作用判定的纯函数单测（1:1 iOS `CharacterDetailView.save()` .edit 分支，行为反推 file:line 114-189）：
 * personaChanged = systemPrompt/性格/说话风格任一变 → 失效心意文案包；occupationChanged = 职业变 → 清 salaryInferred。
 * 关键在「不可多失效」：改 name/外貌/背景/语音等非人设字段绝不该触发失效或重推（避免浪费 token / 误重推月薪）。
 */
class CharacterEditSideEffectsTest {

    private val base = CharacterEntity(
        uuid = "c1",
        name = "小明",
        creationDate = 0L,
        systemPrompt = "你是温柔的陪伴者",
        personalityDescription = "温和内向",
        speakingStyle = "轻声细语",
        occupation = "程序员",
        appearanceDescription = "短发",
        backstory = "从小爱猫",
    )

    @Test fun no_change_triggers_nothing() {
        val e = characterEditSideEffects(base, base.copy())
        assertFalse(e.personaChanged)
        assertFalse(e.occupationChanged)
    }

    @Test fun system_prompt_change_invalidates_persona_only() {
        val e = characterEditSideEffects(base, base.copy(systemPrompt = "你是热情的伙伴"))
        assertTrue(e.personaChanged)
        assertFalse(e.occupationChanged)
    }

    @Test fun personality_change_invalidates_persona() {
        val e = characterEditSideEffects(base, base.copy(personalityDescription = "外向开朗"))
        assertTrue(e.personaChanged)
        assertFalse(e.occupationChanged)
    }

    @Test fun speaking_style_change_invalidates_persona() {
        val e = characterEditSideEffects(base, base.copy(speakingStyle = "干脆利落"))
        assertTrue(e.personaChanged)
        assertFalse(e.occupationChanged)
    }

    @Test fun occupation_change_resets_salary_only_not_persona() {
        val e = characterEditSideEffects(base, base.copy(occupation = "教师"))
        assertTrue(e.occupationChanged)
        assertFalse(e.personaChanged)
    }

    @Test fun occupation_from_empty_to_set_resets_salary() { // iOS 注释例：空 "" → "程序员"
        val e = characterEditSideEffects(base.copy(occupation = ""), base.copy(occupation = "程序员"))
        assertTrue(e.occupationChanged)
    }

    @Test fun non_persona_non_occupation_fields_trigger_nothing() {
        // 改 name/外貌/背景：既非人设三字段也非职业 → 不失效文案包、不重推月薪。
        val e = characterEditSideEffects(
            base,
            base.copy(name = "小华", appearanceDescription = "长发", backstory = "爱旅行"),
        )
        assertFalse(e.personaChanged)
        assertFalse(e.occupationChanged)
    }

    @Test fun persona_and_occupation_both_change() {
        val e = characterEditSideEffects(
            base,
            base.copy(systemPrompt = "新设定", occupation = "医生"),
        )
        assertTrue(e.personaChanged)
        assertTrue(e.occupationChanged)
    }
}
