package com.situ.aichat.offline

import com.situ.aichat.offline.OfflineNarrativePreset.DetailLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OfflineNarrativePreset` tests (P10.2b-1): detail-level resolution, custom-factory rules, and
 * `buildPrompt`/`buildLocationBlock` structure — sentinel-based, reverse-derived from iOS
 * `OfflineNarrativePreset` (incl. the iOS `\`-continuation that joins rule 8 directly to "9.").
 */
class OfflineNarrativePresetTest {

    // ── DetailLevel resolution ──

    @Test fun detail_level_from_raw_with_plain_fallback() {
        assertEquals(DetailLevel.PLAIN, DetailLevel.fromRaw("plain"))
        assertEquals(DetailLevel.NORMAL, DetailLevel.fromRaw("normal"))
        assertEquals(DetailLevel.DETAILED, DetailLevel.fromRaw("detailed"))
        assertEquals(DetailLevel.CUSTOM, DetailLevel.fromRaw("custom"))
        assertEquals(DetailLevel.PLAIN, DetailLevel.fromRaw("garbage")) // iOS default "plain"
    }

    @Test fun resolve_maps_level_to_preset() {
        assertEquals(DetailLevel.PLAIN, OfflineNarrativePreset.resolve(DetailLevel.PLAIN, "", "", "").level)
        assertEquals(DetailLevel.DETAILED, OfflineNarrativePreset.resolve(DetailLevel.DETAILED, "", "", "").level)
        assertEquals(DetailLevel.CUSTOM, OfflineNarrativePreset.resolve(DetailLevel.CUSTOM, "s", "d", "e").level)
    }

    // ── custom() factory ──

    @Test fun custom_with_style_clears_builtin_style_rules() {
        val p = OfflineNarrativePreset.custom(style = "说人话就行", directive = "指令1\n指令2\n", emotion = "底色A")
        assertEquals("", p.rule12)
        assertEquals("", p.rule13)
        assertEquals("说人话就行", p.extraStyleRules)
        assertEquals(listOf("指令1", "指令2"), p.narrativeTechniquePool)
        assertEquals(listOf("底色A"), p.emotionalRegisterPool)
        // rule8 / seedConstraint fall back to normal.
        assertEquals(OfflineNarrativePreset.NORMAL.rule8, p.rule8)
    }

    @Test fun custom_without_style_falls_back_to_normal_rules() {
        val p = OfflineNarrativePreset.custom(style = "   ", directive = "", emotion = "")
        assertEquals(OfflineNarrativePreset.NORMAL.rule12, p.rule12)
        assertEquals(OfflineNarrativePreset.NORMAL.rule13, p.rule13)
        assertEquals(OfflineNarrativePreset.NORMAL.extraStyleRules, p.extraStyleRules)
        assertTrue(p.narrativeTechniquePool.isEmpty())
    }

    // ── buildPrompt structure ──

    private fun prompt(seed: String?, progress: String?, directive: String?) =
        OfflineNarrativePreset.buildPrompt(
            currentTimeText = "2026-06-03 15:30",
            characterCity = null, characterWeather = null, userCity = null, userWeather = null,
            tensionSeed = seed, sceneProgress = progress, perTurnDirective = directive,
            preset = OfflineNarrativePreset.PLAIN,
        )

    @Test fun build_prompt_has_header_tags_and_rules() {
        val p = prompt(seed = null, progress = null, directive = null)
        assertTrue(p.startsWith("【当前处于线下见面模式】"))
        assertTrue(p.contains("使用以下 9 种标签包裹所有内容"))
        assertTrue(p.contains("11. 每次回复第一个内容块永远不是 [对话]"))
        // iOS `\` continuation: rule 8 is immediately followed by "9." with no line break.
        assertTrue(p.contains("${OfflineNarrativePreset.PLAIN.rule8}9. 每次回复 4-6 个内容块"))
        // plain has rule17="" so extraStyleRules numbers as 17.
        assertTrue(p.contains("17. 写作风格：用最日常的语气写"))
    }

    @Test fun build_prompt_always_carries_real_time_line() {
        val p = prompt(seed = null, progress = null, directive = null)
        assertTrue(p.contains("当前真实时间：2026-06-03 15:30"))
        assertTrue(p.contains("14. 结合当前的真实时间来推进线下场景"))
    }

    @Test fun build_prompt_appends_seed_directive_sceneprogress_in_order() {
        val p = prompt(seed = "她今天有心事", progress = "allow_end: false", directive = "【本轮叙事指令】\n· 补一个情绪")
        assertTrue(p.contains("【今日场景种子】\n她今天有心事\n${OfflineNarrativePreset.PLAIN.seedConstraint}"))
        assertTrue(p.contains("【本轮叙事指令】\n· 补一个情绪"))
        // sceneProgress is appended LAST.
        assertTrue(p.endsWith("【节拍状态】\nallow_end: false"))
        // order: seed before directive before the INJECTED scene-progress block.
        // (rule 15 mentions "末尾【节拍状态】的…" inline, so use the injected block's full sentinel.)
        assertTrue(p.indexOf("【今日场景种子】") < p.indexOf("【本轮叙事指令】"))
        assertTrue(p.indexOf("【本轮叙事指令】") < p.indexOf("【节拍状态】\nallow_end: false"))
    }

    @Test fun build_prompt_omits_optional_blocks_when_absent() {
        val p = prompt(seed = null, progress = null, directive = null)
        assertFalse(p.contains("【今日场景种子】"))
        assertFalse(p.contains("【本轮叙事指令】"))
        // base's rule 15 references "【节拍状态】的…" inline; the INJECTED block is "【节拍状态】\n…".
        assertFalse(p.contains("【节拍状态】\n"))
    }

    // ── B5 人设一致性 + 线上线下连续性（§3.7 逐字·所有档位一致） ──

    @Test fun build_prompt_inserts_consistency_blocks_verbatim_before_seed() {
        val p = prompt(seed = "她今天有心事", progress = null, directive = null)
        // 两块逐字（块首各带一个空行·§3.7/§9 禁改）。
        assertTrue(
            p.contains(
                "\n\n【人设一致性】\n[情绪][内心][动作] 必须贴合角色人设与你们当前的关系阶段：外向自来熟的角色不会无端紧张，内向慢热的角色不会突然过分热络；「紧张」「害羞」这类情绪只有在人设或当下情境真正支撑时才出现，不要默认套用。\n\n【线上线下连续性】\n这次见面是你们平时线上聊天的自然延续。你记得系统提示里的共同记忆与过往见面回忆，对话中可以自然地提起（比如「上次你说…」「上回来这儿…」），但不要生硬堆砌回忆，更不要表现得像第一次认识。",
            ),
        )
        // 顺序：一致性块在 base 规则之后、seedBlock 之前。
        assertTrue(p.indexOf("11. 每次回复第一个内容块永远不是 [对话]") < p.indexOf("【人设一致性】"))
        assertTrue(p.indexOf("【人设一致性】") < p.indexOf("【今日场景种子】"))
    }

    @Test fun build_prompt_consistency_blocks_present_all_tiers_even_without_optional() {
        // 所有档位一致（DETAILED 档也含）+ 无可选块时仍注入。
        val p = OfflineNarrativePreset.buildPrompt(
            currentTimeText = "x",
            characterCity = null, characterWeather = null, userCity = null, userWeather = null,
            tensionSeed = null, sceneProgress = null, perTurnDirective = null,
            preset = OfflineNarrativePreset.DETAILED,
        )
        assertTrue(p.contains("【人设一致性】"))
        assertTrue(p.contains("【线上线下连续性】"))
    }

    // ── buildLocationBlock ──

    @Test fun location_block_both_cities_with_weather() {
        val block = OfflineNarrativePreset.buildLocationBlock(
            characterCity = "北京",
            characterWeather = OfflineWeatherSnapshot("☀️", "晴", 12.4, 18.6),
            userCity = "上海",
            userWeather = OfflineWeatherSnapshot("🌧️", "小雨", 10.0, 15.0, "傍晚转阴"),
        )
        assertTrue(block.startsWith("\n\n【双方位置和天气】\n"))
        assertTrue(block.contains("你住在北京，你那边的天气是☀️晴，12°~19°。")) // rounding 12.4→12, 18.6→19
        assertTrue(block.contains("用户住在上海，用户那边是🌧️小雨，10°~15°（傍晚转阴）。"))
        assertTrue(block.contains("根据对话中的情境自然决定见面地点"))
    }

    @Test fun location_block_empty_when_no_city() {
        assertEquals("", OfflineNarrativePreset.buildLocationBlock(null, null, null, null))
        assertEquals("", OfflineNarrativePreset.buildLocationBlock("", null, "", null))
    }

    @Test fun location_block_city_without_weather() {
        val block = OfflineNarrativePreset.buildLocationBlock("北京", null, null, null)
        assertTrue(block.contains("你住在北京。"))
        // no weather CLAUSE (the trailing instruction line legitimately contains the word 天气).
        assertFalse(block.contains("你那边的天气是"))
    }
}
