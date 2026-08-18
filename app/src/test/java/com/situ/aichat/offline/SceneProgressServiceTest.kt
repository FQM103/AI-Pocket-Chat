package com.situ.aichat.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SceneProgressService` tests (P10.2c-2): the beat-state system prompt (structure / exact colons /
 * `\`-continuation joins / seed branch) and `processTensionRenewal` (张力自愈), reverse-derived from iOS
 * `SceneProgressService` + `SceneProgressCoordinator.processTensionRenewal`.
 */
class SceneProgressServiceTest {

    private fun prompt(
        chatLog: String = "[2026-06-04 18:30] 用户：你来了\n[2026-06-04 18:31] 角色：嗯",
        characterName: String = "小琳",
        userName: String = "阿哲",
        locationHint: String = "江边咖啡馆",
        tensionSeed: String? = null,
    ) = SceneProgressService.buildSystemPrompt(chatLog, characterName, userName, locationHint, tensionSeed)

    // ── buildSystemPrompt: 结构 + 精确冒号 + 续行合并 ──

    @Test fun prompt_intro_is_single_continuation_joined_line() {
        val p = prompt()
        assertTrue(
            p.startsWith(
                "你在维护一段线下见面的\"节拍状态\"。读完下面的对话后，按固定格式输出 Markdown，不要解释，不要加前言，不要用代码块。格式如下（每行一个字段）：",
            ),
        )
    }

    @Test fun prompt_field_labels_use_half_width_colons() {
        val p = prompt()
        assertTrue(p.contains("\nallow_end: true|false\n"))
        assertTrue(p.contains("\n地点: <最新地点，没变就填初始地点>\n"))
        assertTrue(p.contains("\n已发生的关键节点:\n"))
        assertTrue(p.contains("\n当前情绪基调: <一句话>\n"))
        assertTrue(p.contains("\n未解决的张力: <一句话，若无则填\"无\">\n"))
        assertTrue(p.contains("\n建议新张力: <如果\"未解决的张力\"是\"无\"，从对话中自然生长一条新的微妙张力；否则填\"无\">\n"))
    }

    @Test fun prompt_section_labels_use_full_width_colons() {
        val p = prompt()
        assertTrue(p.contains("\n规则：\n"))
        assertTrue(p.contains("\n角色名：小琳\n"))
        assertTrue(p.contains("\n用户名：阿哲\n"))
        assertTrue(p.contains("初始地点提示：江边咖啡馆"))
    }

    @Test fun prompt_rules_1_4_5_are_continuation_joined_single_lines() {
        val p = prompt()
        // rule 1: 进入 immediately followed by 可自然收束 (no line break at iOS `\`)
        assertTrue(p.contains("已经进入可自然收束的阶段（说到告别、天晚了、准备离开）时才允许 true。"))
        // rule 4: 自然长出来的 + 新张力, and 场景里 + 某个细节 (two `\` joins on one logical line)
        assertTrue(p.contains("给出从对话中自然长出来的新张力——角色某句话背后没说出口的意思"))
        assertTrue(p.contains("场景里某个细节可以引出新的情绪线。不要凭空编造不相关的事件。"))
        // rule 5
        assertTrue(p.contains("而非收束当前情节——\"路过一个有意义的地方\"而非\"把心事说清楚\"。"))
    }

    @Test fun prompt_chatlog_appended_after_record_header() {
        val p = prompt(chatLog = "[t] 用户：测试日志")
        assertTrue(p.contains("## 线下对话记录\n[t] 用户：测试日志"))
        assertTrue(p.endsWith("[t] 用户：测试日志"))
    }

    @Test fun prompt_empty_user_name_falls_back_to_default() {
        assertTrue(prompt(userName = "").contains("\n用户名：用户\n"))
    }

    // ── buildSystemPrompt: seed 双分支 ──

    @Test fun prompt_without_seed_omits_seed_line_and_rule_tail() {
        val p = prompt(tensionSeed = null)
        assertFalse(p.contains("隐藏心事种子："))
        assertFalse(p.contains("隐藏心事如果还没浮出水面"))
        // rule 1 ends right at 才允许 true。 then newline (no seed tail)
        assertTrue(p.contains("时才允许 true。\n2. 已发生的关键节点"))
        // 初始地点提示 line has no seed prefix
        assertTrue(p.contains("\n初始地点提示：江边咖啡馆\n"))
    }

    @Test fun prompt_with_seed_adds_seed_line_and_rule_tail() {
        val p = prompt(tensionSeed = "她偷偷带了礼物")
        // seed tail appended directly after rule 1
        assertTrue(p.contains("时才允许 true。隐藏心事如果还没浮出水面，一律 false。\n"))
        // seed line sits on its own line right before 初始地点提示
        assertTrue(p.contains("\n隐藏心事种子：她偷偷带了礼物\n初始地点提示：江边咖啡馆\n"))
    }

    // ── processTensionRenewal ──

    @Test fun renewal_replaces_unresolved_tension_with_suggestion_and_drops_suggested_line() {
        val input = "allow_end: false\n未解决的张力: 无\n建议新张力: 她其实想多留一会儿"
        val out = SceneProgressService.processTensionRenewal(input)
        assertEquals("allow_end: false\n未解决的张力: 她其实想多留一会儿", out)
    }

    @Test fun renewal_keeps_present_tension_but_still_drops_suggested_line() {
        val input = "未解决的张力: 她在意你昨天没回消息\n建议新张力: 无"
        val out = SceneProgressService.processTensionRenewal(input)
        assertEquals("未解决的张力: 她在意你昨天没回消息", out)
    }

    @Test fun renewal_no_replacement_when_suggestion_is_none_but_drops_line() {
        val input = "未解决的张力: 无\n建议新张力: 无"
        val out = SceneProgressService.processTensionRenewal(input)
        assertEquals("未解决的张力: 无", out)
    }

    @Test fun renewal_handles_full_width_colons() {
        val input = "未解决的张力： 无\n建议新张力： 路过老地方勾起回忆"
        val out = SceneProgressService.processTensionRenewal(input)
        assertEquals("未解决的张力： 路过老地方勾起回忆", out)
    }

    @Test fun renewal_preserves_leading_whitespace_prefix() {
        val input = "  未解决的张力: 无\n建议新张力: 新的微妙张力"
        val out = SceneProgressService.processTensionRenewal(input)
        assertEquals("  未解决的张力: 新的微妙张力", out)
    }

    @Test fun renewal_no_suggested_line_leaves_text_unchanged() {
        val input = "allow_end: true\n未解决的张力: 无"
        val out = SceneProgressService.processTensionRenewal(input)
        assertEquals(input, out)
    }

    // ── shouldTriggerUpdate（≥15 user 差 + 3min 防抖） ──

    @Test fun trigger_false_when_turn_diff_below_threshold() {
        assertFalse(SceneProgressService.shouldTriggerUpdate(offlineUserTurnCount = 14, lastTriggerCount = 0, lastUpdateAt = null, now = 0L))
    }

    @Test fun trigger_true_at_threshold_with_no_prior_update() {
        assertTrue(SceneProgressService.shouldTriggerUpdate(offlineUserTurnCount = 15, lastTriggerCount = 0, lastUpdateAt = null, now = 0L))
    }

    @Test fun trigger_false_when_within_debounce_window() {
        // diff 充足但距上次更新仅 100s（<180s）→ 不触发
        assertFalse(SceneProgressService.shouldTriggerUpdate(offlineUserTurnCount = 30, lastTriggerCount = 0, lastUpdateAt = 100_000L, now = 200_000L))
    }

    @Test fun trigger_true_after_debounce_elapsed() {
        // 距上次更新刚好 180s → 不再防抖 → 触发
        assertTrue(SceneProgressService.shouldTriggerUpdate(offlineUserTurnCount = 30, lastTriggerCount = 0, lastUpdateAt = 0L, now = 180_000L))
    }

    @Test fun trigger_uses_diff_from_last_trigger_count() {
        // 15 − 5 = 10 < 15 → 不触发（差值而非绝对值，跨重启不漏）
        assertFalse(SceneProgressService.shouldTriggerUpdate(offlineUserTurnCount = 15, lastTriggerCount = 5, lastUpdateAt = null, now = 0L))
        assertTrue(SceneProgressService.shouldTriggerUpdate(offlineUserTurnCount = 20, lastTriggerCount = 5, lastUpdateAt = null, now = 0L))
    }

    // MARK: - R5#0 forceFieldValue（写改端容错·与 extractFieldValue 解析端同套·消除「再待一会儿」静默失效）

    @Test fun forceField_canonicalLine_rewrittenToFalse() {
        val state = "节拍\nallow_end: true\n未解决的张力: 无"
        val out = SceneProgressService.forceFieldValue(state, "allow_end", "false")
        assertEquals("节拍\nallow_end: false\n未解决的张力: 无", out)
    }

    @Test fun forceField_tolerates_missingSpace() {
        // LLM 漏空格 `allow_end:true` —— 旧字面 replace 改不动；新逻辑须命中并规范化为 `allow_end: false`。
        val out = SceneProgressService.forceFieldValue("allow_end:true", "allow_end", "false")
        assertEquals("allow_end: false", out)
    }

    @Test fun forceField_tolerates_fullWidthColon_andSpaces() {
        val out = SceneProgressService.forceFieldValue("allow_end ： true", "allow_end", "false")
        assertEquals("allow_end: false", out)
    }

    @Test fun forceField_tolerates_uppercaseValue() {
        // 值大小写不影响命中（按字段名定位行，整行重写）。
        val out = SceneProgressService.forceFieldValue("allow_end: True", "allow_end", "false")
        assertEquals("allow_end: false", out)
    }

    @Test fun forceField_preservesLeadingIndent() {
        val out = SceneProgressService.forceFieldValue("  allow_end: true", "allow_end", "false")
        assertEquals("  allow_end: false", out)
    }

    @Test fun forceField_absentField_returnedUnchanged() {
        // 无该字段行 → 原样返回（不无中生有）。
        val state = "节拍\n未解决的张力: 某事"
        assertEquals(state, SceneProgressService.forceFieldValue(state, "allow_end", "false"))
    }

    @Test fun forceField_onlyFirstMatchingLine_rewritten() {
        // 只重写首个命中行（节拍状态每字段单行）。
        val out = SceneProgressService.forceFieldValue("allow_end: true\nallow_end: true", "allow_end", "false")
        assertEquals("allow_end: false\nallow_end: true", out)
    }
}
