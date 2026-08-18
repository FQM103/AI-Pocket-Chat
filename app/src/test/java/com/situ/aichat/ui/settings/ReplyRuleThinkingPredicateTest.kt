package com.situ.aichat.ui.settings

import com.situ.aichat.data.local.entity.ApiConfigEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CREATIVITY_RELOCATION D-3 提示行谓词单测：聊天功能解析配置是否思考模型。
 * 回退语义从契约独立反推（对齐 ApiConfigRepository.resolveConfigValues）：
 * 显式分配且存在 → 用它；分配失效或未分配 → active 默认；无任何配置 → false。
 */
class ReplyRuleThinkingPredicateTest {

    private fun config(uuid: String, thinkingMode: String = "auto", detected: Int = -1) = ApiConfigEntity(
        uuid = uuid,
        providerName = "p",
        apiKeyId = "k",
        baseURL = "https://example.com",
        modelName = "m",
        creationDate = 0L,
        thinkingModelModeRaw = thinkingMode,
        detectedThinkingModelType = detected,
    )

    @Test
    fun assignedThinkingConfig_returnsTrue() {
        val thinking = config("a", thinkingMode = "thinking")
        val normal = config("b", thinkingMode = "standard")
        assertTrue(ReplyRuleSettingsViewModel.chatConfigIsThinking("a", listOf(thinking, normal), active = normal))
    }

    @Test
    fun assignedStandardConfig_returnsFalse_evenIfActiveIsThinking() {
        val thinking = config("a", thinkingMode = "thinking")
        val normal = config("b", thinkingMode = "standard")
        assertFalse(ReplyRuleSettingsViewModel.chatConfigIsThinking("b", listOf(thinking, normal), active = thinking))
    }

    @Test
    fun unassigned_fallsBackToActive() {
        val thinking = config("a", thinkingMode = "thinking")
        assertTrue(ReplyRuleSettingsViewModel.chatConfigIsThinking(null, listOf(thinking), active = thinking))
    }

    @Test
    fun staleAssignment_fallsBackToActive() {
        val thinking = config("a", thinkingMode = "thinking")
        assertTrue(ReplyRuleSettingsViewModel.chatConfigIsThinking("gone", listOf(thinking), active = thinking))
    }

    @Test
    fun autoMode_usesDetection() {
        val detectedThinking = config("a", thinkingMode = "auto", detected = 1)
        val undetected = config("b", thinkingMode = "auto", detected = -1)
        assertTrue(ReplyRuleSettingsViewModel.chatConfigIsThinking("a", listOf(detectedThinking, undetected), active = null))
        assertFalse(ReplyRuleSettingsViewModel.chatConfigIsThinking("b", listOf(detectedThinking, undetected), active = null))
    }

    @Test
    fun noConfigsAtAll_returnsFalse() {
        assertFalse(ReplyRuleSettingsViewModel.chatConfigIsThinking(null, emptyList(), active = null))
    }
}
