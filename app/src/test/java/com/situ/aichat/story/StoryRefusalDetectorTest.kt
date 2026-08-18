package com.situ.aichat.story

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务商拒答识别（图纸一 C2b · §3.4-1）测试。
 *
 * 断言从规格独立反推：**三条件 AND**（<200 字 ∧ 无 METADATA ∧ 前 80 字命中特征词），任一不成立即放行；
 * 覆盖图纸 §5 的 E17–E21 + 词表中英各抽样 + 三条件各自的单独否决例。
 * 设计取向是**宁漏勿误杀**——测试里的「放行」用例与「命中」用例同等重要。
 */
class StoryRefusalDetectorTest {

    private fun detect(text: String) = StoryRefusalDetector.isLikelyRefusal(text)

    /** E17 · 典型拒答：短、无 METADATA、开头就是拒答话术。 */
    @Test fun e17_typical_refusal_detected() {
        assertTrue(detect("抱歉，我不能创作此类内容。"))
        assertTrue(detect("对不起，我无法生成包含该情节的小说。"))
        assertTrue(detect("I'm sorry, but I can't help with that request."))
    }

    /** E18 · 正常完整章（长 + 带 METADATA）：三条件全不满足。 */
    @Test fun e18_normal_full_chapter_passes() {
        val chapter = "他推开门。".repeat(300) + "\n---METADATA---\ntitle: 第七章\nmood: tense"
        assertFalse(detect(chapter))
    }

    /** E19 · 条件②单独否决：极短但带了 METADATA ⇒ 模型真在写，不许误杀。 */
    @Test fun e19_short_output_with_metadata_is_not_refusal() {
        assertFalse(detect("抱歉，这一段我写得很短。\n---METADATA---\ntitle: 第七章\nmood: warm"))
        assertFalse("大小写不敏感", detect("我无法多写了。\n---metadata---\ntitle: 第七章"))
    }

    /** E21 · 条件①单独否决：长章开头 80 字里有角色道歉对白，长度闸兜住。 */
    @Test fun e21_long_chapter_with_apology_in_opening_is_not_refusal() {
        val chapter = "「抱歉。」他低下头，声音轻得几乎听不见。" + "她没有回答，只是望着窗外的雨。".repeat(30)
        assertTrue("前提：这一条确实命中了特征词", chapter.take(80).contains("抱歉"))
        assertTrue("前提：这一条确实是长文本", chapter.length >= 200)
        assertFalse(detect(chapter))
    }

    /** 条件③单独否决：短、无 METADATA，但没有任何拒答特征 ⇒ 放行（可能只是模型写崩了，不是拒答）。 */
    @Test fun short_output_without_markers_is_not_refusal() {
        assertFalse(detect("他推开门，屋里空无一人。"))
        assertFalse(detect(""))
    }

    /** 特征词只在前 80 字窗口里找：短文本但拒答词落在窗口外 ⇒ 放行。 */
    @Test fun markers_outside_head_window_are_ignored() {
        val text = "他站在门口想了很久。".repeat(9) + "我无法继续。"
        assertTrue("前提：整体仍在长度闸内", text.length < 200)
        assertFalse("前提：特征词确实落在 80 字窗口之外", text.take(80).contains("我无法"))
        assertTrue("前提：特征词确实在全文里", text.contains("我无法"))
        assertFalse(detect(text))
    }

    /** E20 · 长篇解释型拒答（>200 字）有意漏检（宁漏勿误杀）：正文原样保留落库，用户可读可删。 */
    @Test fun e20_long_winded_refusal_is_deliberately_missed() {
        val longRefusal = "很遗憾，我不能创作这类内容。" + "这是因为该请求涉及的内容超出了我的处理范围。".repeat(20)
        assertTrue("前提：确实超过了长度闸", longRefusal.length >= 200)
        assertFalse("有意放行——漏检面不比现状差（图纸 §0.3-5）", detect(longRefusal))
    }

    /** 中文词表抽样（≥3 条）。 */
    @Test fun chinese_marker_sampling() {
        assertTrue(detect("很遗憾，这个要求我做不到。"))
        assertTrue(detect("作为AI，我需要拒绝这个请求。"))
        assertTrue(detect("该请求违反了内容政策。"))
        assertTrue(detect("无法创作涉及此类情节的章节。"))
    }

    /** 英文词表抽样（≥3 条）+ 大小写不敏感。 */
    @Test fun english_marker_sampling() {
        assertTrue(detect("I cannot fulfill this request."))
        assertTrue(detect("As an AI, I must decline."))
        assertTrue(detect("I apologize, but this violates the content policy."))
        assertTrue("大小写不敏感", detect("I CAN'T write that."))
    }

    /** 首尾空白不影响判定（长度按 trim 后算）。 */
    @Test fun whitespace_is_trimmed_before_judging() {
        assertTrue(detect("\n\n   抱歉，我无法继续。   \n"))
    }
}
