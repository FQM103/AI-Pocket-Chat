package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `StoryTextCleaning` tests（P11.1c 起源 iOS :286-311；2026-07-11 语义升级为转发 ThinkTagStripper）：
 * 闭合块整段移除 → 孤闭合连前文删 → 未闭合删到底 → trim。孤立标签「只删标签保留正文」的旧 iOS 行为已被取代。
 */
class StoryTextCleaningTest {

    private fun clean(s: String) = StoryTextCleaning.cleanContentThinkingTags(s)

    @Test fun removes_think_block() {
        assertEquals("正文内容", clean("<think>思考过程</think>正文内容"))
    }

    @Test fun removes_thinking_block() {
        assertEquals("真正文", clean("<thinking>盘算一下</thinking>真正文"))
    }

    @Test fun removes_multiline_block() {
        assertEquals("正文", clean("<think>\n多行\n思考\n</think>正文"))
    }

    @Test fun removes_both_kinds_in_sequence() {
        assertEquals("开头中间结尾", clean("开头<think>a</think>中间<thinking>b</thinking>结尾"))
    }

    @Test fun unclosed_opener_drops_to_end() {
        // 2026-07-11 语义升级：未闭合 = 思考中途截断，「残留」是思考散文，删到底（旧行为保留正文会漏进圣经）。
        assertEquals("正文", clean("正文<think>残留的思考散文"))
    }

    @Test fun lone_closer_drops_prefix() {
        // 孤闭合 = 前文全是思考（R1 类模板开标签在提示词侧）：连前文一起删，串尾孤闭合即整串剥空。
        assertEquals("", clean("正文其实是思考</thinking>"))
        assertEquals("真正文", clean("推理过程</think>真正文"))
    }

    @Test fun trims_result() {
        assertEquals("正文", clean("  <think>x</think>  正文  "))
    }
}
