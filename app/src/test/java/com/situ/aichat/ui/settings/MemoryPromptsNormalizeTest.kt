package com.situ.aichat.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [normalizeCustomPrompt] 单测——断言反推 iOS MemoryPromptSettingsView.onChange：
 * 文本等于默认模板 → 存空串（=用默认，且「未自定义」可被识别）；否则存原文（自定义生效）。
 */
class MemoryPromptsNormalizeTest {

    private val default = "你是一个记忆提取助手……（默认模板）"

    @Test fun equalsDefault_storesEmpty() {
        assertEquals("", normalizeCustomPrompt(default, default))
    }

    @Test fun differsFromDefault_storesText() {
        val custom = "我的自定义提取指令"
        assertEquals(custom, normalizeCustomPrompt(custom, default))
    }

    @Test fun emptyInput_staysEmpty() {
        // 用户清空 → 非默认 → 存空（消费方仍回落默认，与「等于默认存空」殊途同归）。
        assertEquals("", normalizeCustomPrompt("", default))
    }

    @Test fun whitespaceTweak_isCustom_notDefault() {
        // 仅尾随空格之差也算自定义（精确字符串比对·对齐 iOS == 语义）。
        assertEquals("$default ", normalizeCustomPrompt("$default ", default))
    }
}
