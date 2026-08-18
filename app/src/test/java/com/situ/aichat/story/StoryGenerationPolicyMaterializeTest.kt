package com.situ.aichat.story

import com.situ.aichat.story.StoryGenerationPolicy.StatusDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * `StoryGenerationPolicy` 的 11.1e-5 materialize 纯逻辑测试：
 * - [decideStatus] = 状态机 + **ST11 拍板②降级**（结局请求 > 有选择 > 连载；「LLM 标结局」优先级已整条删除，
 *   isEnding 不再是本函数的输入）+ **卷二单模式化**（「满章自动扩展 ≤3 次 / 扩展用尽即完结」整条删除，
 *   chapterNumber/maxChapters/autoExtendCount 三形参随之退役——见 [only_requested_ending_completes]）
 * - [computeUnlockAt] = chase 解锁时间（今天 H:M，now 早于则取今天否则 +1 天）
 * - [buildBibleAppendix] = 圣经追加
 * - [rollbackBible] = 重写回滚
 */
class StoryGenerationPolicyMaterializeTest {

    // ── decideStatus（卷二新签名：requestedEndingType × hasChoice 两维四格真值表）──

    private fun decide(
        requestedEndingType: String? = null,
        hasChoice: Boolean = false,
    ) = StoryGenerationPolicy.decideStatus(requestedEndingType, hasChoice)

    @Test fun requested_ending_forces_completed_and_clears_flags() {
        // requestedEndingType≠null 优先级最高：即便有选择也强制完结并清结局请求字段。
        assertEquals(
            StatusDecision(StoryStatus.COMPLETED, clearRequestedEnding = true),
            decide(requestedEndingType = "open", hasChoice = true),
        )
    }

    @Test fun requested_ending_without_choice_also_completes() {
        assertEquals(
            StatusDecision(StoryStatus.COMPLETED, clearRequestedEnding = true),
            decide(requestedEndingType = "ai", hasChoice = false),
        )
    }

    @Test fun requested_ending_uses_non_null_check_not_emptiness() {
        // 判据是 `requestedEndingType != null`（非空串也算请求）——锁住不要误改成 isNullOrEmpty。
        assertEquals(
            StatusDecision(StoryStatus.COMPLETED, clearRequestedEnding = true),
            decide(requestedEndingType = ""),
        )
    }

    /**
     * **ST11 拍板② + 卷二拍板①的看门狗**：期望从图纸 §3.1/J9 判定链独立反推——
     * 落库状态机里 **completed 只剩一条来源：用户请求结局**（含终章弧末章转正后的那一章）。
     * 「AI 说完结」与「写满 N 章」都不再是完结路（前者归建议卡请用户盖章，后者随有限模式退役）。
     */
    @Test fun only_requested_ending_completes() {
        for (hasChoice in listOf(false, true)) {
            val d = decide(requestedEndingType = null, hasChoice = hasChoice)
            assertNotEquals("未请求结局绝不许完结（hasChoice=$hasChoice）", StoryStatus.COMPLETED, d.status)
            assertEquals("非请求结局路不许清一次性结局字段", false, d.clearRequestedEnding)
        }
    }

    /** AI 自标结局 + 无选项的章：状态机只看 hasChoice → serializing（书照常连载，等用户盖章）。 */
    @Test fun ai_suggested_ending_chapter_without_choice_keeps_serializing() {
        assertEquals(
            StatusDecision(StoryStatus.SERIALIZING, clearRequestedEnding = false),
            decide(hasChoice = false),
        )
    }

    /** AI 自标结局 + 有选项的矛盾输出：选项保留 → waitingChoice（ST11 图纸 §3.1 矛盾输出口径）。 */
    @Test fun ai_suggested_ending_chapter_with_choice_waits_for_choice() {
        assertEquals(
            StatusDecision(StoryStatus.WAITING_CHOICE, clearRequestedEnding = false),
            decide(hasChoice = true),
        )
    }

    // ── computeUnlockAt ──

    private val zone = ZoneId.of("Asia/Shanghai") // 国行目标时区，无 DST，确定性

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    @Test fun unlock_before_today_time_uses_today() {
        // now 10:00 早于今天 20:00 → 解锁=今天 20:00。
        assertEquals(
            at(2026, 6, 5, 20, 0),
            StoryGenerationPolicy.computeUnlockAt(at(2026, 6, 5, 10, 0), 20, 0, zone),
        )
    }

    @Test fun unlock_after_today_time_uses_tomorrow() {
        // now 21:00 晚于今天 20:00 → 解锁=明天 20:00。
        assertEquals(
            at(2026, 6, 6, 20, 0),
            StoryGenerationPolicy.computeUnlockAt(at(2026, 6, 5, 21, 0), 20, 0, zone),
        )
    }

    @Test fun unlock_exactly_at_time_uses_tomorrow() {
        // now == 今天 20:00:00 → 非「早于」→ 取明天（iOS `now < unlockTime` 在相等时为 false）。
        assertEquals(
            at(2026, 6, 6, 20, 0),
            StoryGenerationPolicy.computeUnlockAt(at(2026, 6, 5, 20, 0, 0), 20, 0, zone),
        )
    }

    @Test fun unlock_seconds_in_now_push_past_zero_second_unlock() {
        // 解锁时刻秒=0；now 20:00:30 晚于 20:00:00 → 明天。锁住「解锁时分秒归零 + now 秒参与比较」。
        assertEquals(
            at(2026, 6, 6, 20, 0),
            StoryGenerationPolicy.computeUnlockAt(at(2026, 6, 5, 20, 0, 30), 20, 0, zone),
        )
    }

    @Test fun unlock_respects_non_zero_minute() {
        // unlockMinute=30：now 20:15 早于 20:30 → 今天 20:30。
        assertEquals(
            at(2026, 6, 5, 20, 30),
            StoryGenerationPolicy.computeUnlockAt(at(2026, 6, 5, 20, 15), 20, 30, zone),
        )
    }

    @Test fun unlock_invalid_hour_returns_null() {
        // 时分非法 → null（等价 iOS `if let` 失败，unlockAt 保持 nil=立即可读）。
        assertNull(StoryGenerationPolicy.computeUnlockAt(at(2026, 6, 5, 10, 0), 24, 0, zone))
    }

    // ── buildBibleAppendix ──

    @Test fun bible_appendix_both_fields() {
        assertEquals(
            "第3章角色：小明:开心\n第3章伏笔：宝箱未开\n",
            StoryGenerationPolicy.buildBibleAppendix(3, "小明:开心", "宝箱未开"),
        )
    }

    @Test fun bible_appendix_states_only_skips_empty_threads() {
        assertEquals("第3章角色：小明:开心\n", StoryGenerationPolicy.buildBibleAppendix(3, "小明:开心", null))
        assertEquals("第3章角色：小明:开心\n", StoryGenerationPolicy.buildBibleAppendix(3, "小明:开心", ""))
    }

    @Test fun bible_appendix_threads_only() {
        assertEquals("第7章伏笔：宝箱未开\n", StoryGenerationPolicy.buildBibleAppendix(7, null, "宝箱未开"))
    }

    @Test fun bible_appendix_both_empty_returns_blank() {
        assertEquals("", StoryGenerationPolicy.buildBibleAppendix(3, null, null))
        assertEquals("", StoryGenerationPolicy.buildBibleAppendix(3, "", ""))
    }

    // ── rollbackBible ──

    @Test fun rollback_null_bible_stays_null() {
        assertNull(StoryGenerationPolicy.rollbackBible(null, 3))
    }

    @Test fun rollback_removes_only_target_chapter_lines() {
        val bible = "第1章角色：A\n第2章角色：B\n第3章角色：C\n第3章伏笔：D\n第2章伏笔：E"
        assertEquals(
            "第1章角色：A\n第2章角色：B\n第2章伏笔：E",
            StoryGenerationPolicy.rollbackBible(bible, 3),
        )
    }

    @Test fun rollback_to_empty_returns_null() {
        // 删完所有行 → trim 后空 → null。
        assertNull(StoryGenerationPolicy.rollbackBible("第3章角色：C\n第3章伏笔：D", 3))
    }

    @Test fun rollback_trims_trailing_newline_after_removal() {
        // 末尾换行被 trim：["第3章角色：C", "第1章角色：A", ""] 删首 → join → "第1章角色：A\n" → trim。
        assertEquals(
            "第1章角色：A",
            StoryGenerationPolicy.rollbackBible("第3章角色：C\n第1章角色：A\n", 3),
        )
    }

    @Test fun rollback_prefix_is_exact_not_numeric_prefix() {
        // 第30章不应被第3章的回滚误删（前缀「第3章角色：」不匹配「第30章角色：」）。
        assertEquals(
            "第30章角色：X",
            StoryGenerationPolicy.rollbackBible("第3章角色：C\n第30章角色：X", 3),
        )
    }

    @Test fun rollback_no_matching_lines_returns_trimmed_unchanged() {
        assertEquals("第1章角色：A", StoryGenerationPolicy.rollbackBible("第1章角色：A", 3))
    }
}
