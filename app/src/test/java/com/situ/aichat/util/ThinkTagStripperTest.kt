package com.situ.aichat.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ThinkTagStripper] 三条规则的 T1（断言从 2026-07-11 拍板规格独立反推）：
 * ① 闭合块整段移除；② 孤闭合标签连前文一起删（R1 类模板开标签在提示词侧）；
 * ③ 未闭合开标签删到串尾（思考中途截断，正文从未产出）。规则 ②/③ 剥空 = 纯思考响应信号。
 */
class ThinkTagStripperTest {

    private fun strip(s: String) = ThinkTagStripper.strip(s)

    // ── 规则 ①：闭合块 ──

    @Test fun closedBlock_removed() {
        assertEquals("正文", strip("<think>思考</think>正文"))
        assertEquals("正文", strip("<thinking>盘算\n多行</thinking>正文"))
        assertEquals("头中尾", strip("头<think>a</think>中<thinking>b</thinking>尾"))
    }

    // ── 规则 ②：孤闭合连前文删 ──

    @Test fun loneCloser_dropsEverythingBefore() {
        assertEquals("正式答案", strip("这里全是推理过程</think>正式答案"))
        assertEquals("答案", strip("推理</thinking>答案"))
    }

    @Test fun loneCloser_multiple_takesLast() {
        assertEquals("答案", strip("推理一</think>推理二</think>答案"))
    }

    @Test fun trailingLoneCloser_emptiesAll() {
        // 闭合在串尾 = 整串都是思考 → 剥空（调用方走失败/重试路）
        assertEquals("", strip("从头到尾都是推理</think>"))
    }

    // ── 规则 ③：未闭合删到底 ──

    @Test fun unclosedOpener_dropsToEnd() {
        assertEquals("正文", strip("正文<think>思考被截断了没有闭合"))
        assertEquals("正文", strip("正文<thinking>另一种标签截断"))
    }

    @Test fun pureUnclosedThink_emptiesAll() {
        // 开头就进思考且被截断 = 正文从未产出 → 剥空
        assertEquals("", strip("<think>只想到一半就被字数上限掐断"))
    }

    // ── 组合与不误伤 ──

    @Test fun closedThenUnclosed_bothHandled() {
        assertEquals("正文", strip("<think>a</think>正文<think>又开始想被截断"))
    }

    @Test fun loneCloserThenUnclosedOpener_bothHandled() {
        assertEquals("答", strip("推理</thinking>答<think>再想被截断"))
    }

    @Test fun plainText_untouched() {
        val plain = "普通正文，含冒号行：\nsummary: 不是标签\n【长期事实】也原样"
        assertEquals(plain.trim(), strip(plain))
    }

    // ── 🔵3：大小写不敏感（兜大写变体模型·三条规则全覆盖） ──

    @Test fun uppercaseTags_allThreeRulesApply() {
        assertEquals("正文", strip("<THINK>思考</THINK>正文"))
        assertEquals("答案", strip("推理过程</THINK>答案"))
        assertEquals("正文", strip("正文<Thinking>混合大小写被截断"))
        assertEquals("", strip("<Think>纯思考截断"))
    }

    @Test fun idempotent_onStrippedOutput() {
        val once = strip("<think>a</think>正文<think>截断")
        assertEquals(once, strip(once))
    }

    @Test fun trims_result() {
        assertEquals("正文", strip("  <think>x</think>  正文  "))
    }
}
