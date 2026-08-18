package com.situ.aichat.ui.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [findMacroRanges] 纯函数单测：宏 `{{...}}` 区间检测 = 宏高亮 VisualTransformation 的着色依据。
 * 断言区间**等长于宏字符数**——这是 `OffsetMapping.Identity`（光标/选择不漂移）的前提。
 * 引擎差异（ICU vs JVM）由模拟器真机渲染走查兜底；此处锁基础匹配契约。
 */
class AppTextAreaMacroTest {

    @Test
    fun singleMacro_matchesWholeToken() {
        // "{{char}}" = 8 chars (0..7)
        assertEquals(listOf(0..7), findMacroRanges("{{char}}"))
    }

    @Test
    fun multipleMacros_withChineseAndText() {
        // "[{{char}}的记忆]{{记忆内容}}"：{{char}}=1..8，{{记忆内容}}=13..20
        assertEquals(listOf(1..8, 13..20), findMacroRanges("[{{char}}的记忆]{{记忆内容}}"))
    }

    @Test
    fun adjacentMacros_areEqualLengthRanges() {
        // 等长前提：{{a}}=0..4(5 chars)，{{b}}=5..9
        val ranges = findMacroRanges("{{a}}{{b}}")
        assertEquals(listOf(0..4, 5..9), ranges)
        ranges.forEach { assertEquals(5, it.last - it.first + 1) }
    }

    @Test
    fun incompleteOrPlain_matchNothing() {
        assertEquals(emptyList<IntRange>(), findMacroRanges("{{char"))
        assertEquals(emptyList<IntRange>(), findMacroRanges("没有任何宏的纯文本"))
        assertEquals(emptyList<IntRange>(), findMacroRanges(""))
    }

    @Test
    fun emptyBraces_notHighlighted() {
        // "{{}}" 中间无字符 → 不当宏（[^{}]+ 要求至少一个非括号字符）
        assertEquals(emptyList<IntRange>(), findMacroRanges("{{}}"))
    }

    @Test
    fun realInjectionPrompt_findsAllMacros() {
        val prompt = "[{{char}}的记忆]\n以下是你对过往互动的记忆。\n{{记忆内容}}"
        // 两个宏：{{char}} 与 {{记忆内容}}
        assertEquals(2, findMacroRanges(prompt).size)
    }
}
