package com.situ.aichat.data.remote.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CREATIVITY_RELOCATION D-4 请求体温度决策单测（断言从契约独立反推）：
 * 思考模型 → 不发 temperature（null，优先于 MiniMax 映射）；
 * MiniMax → 0..2 映射到 (0, 1.0]（既有行为不回归）；其余原样透传。
 */
class LlmTemperaturePolicyTest {

    @Test
    fun thinkingModel_dropsTemperature() {
        assertNull(LlmClient.resolveEffectiveTemperature(0.8, isMiniMax = false, isThinkingModel = true))
        // 思考优先于 MiniMax 映射
        assertNull(LlmClient.resolveEffectiveTemperature(1.6, isMiniMax = true, isThinkingModel = true))
    }

    @Test
    fun miniMax_mapsZeroTwoRange_noRegression() {
        assertEquals(0.8, LlmClient.resolveEffectiveTemperature(1.6, isMiniMax = true, isThinkingModel = false)!!, 1e-9)
        // 0 → 下限 0.01（MiniMax 不接受 0）
        assertEquals(0.01, LlmClient.resolveEffectiveTemperature(0.0, isMiniMax = true, isThinkingModel = false)!!, 1e-9)
        assertNull(LlmClient.resolveEffectiveTemperature(null, isMiniMax = true, isThinkingModel = false))
    }

    @Test
    fun normalModel_passesThrough() {
        assertEquals(1.0, LlmClient.resolveEffectiveTemperature(1.0, isMiniMax = false, isThinkingModel = false)!!, 0.0)
        assertNull(LlmClient.resolveEffectiveTemperature(null, isMiniMax = false, isThinkingModel = false))
    }
}
