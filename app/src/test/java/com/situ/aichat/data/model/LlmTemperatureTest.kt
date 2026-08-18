package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 14.3b 创造力（温度）纯函数单测。默认 1.0（2026-07-11 CREATIVITY_RELOCATION D-2 拍板 0.8→1.0）+ 滑块 [0,2]；
 * 非有限值（NaN/∞）回退默认（安卓加固，iOS 滑块本身不会产生非有限值）。
 */
class LlmTemperatureTest {

    @Test
    fun default_isOnePointZero() {
        assertEquals(1.0, AppSettings().sanitizedLlmTemperature, 0.0)
    }

    @Test
    fun withinRange_passesThrough() {
        assertEquals(0.0, AppSettings(llmTemperature = 0.0).sanitizedLlmTemperature, 0.0)
        assertEquals(1.3, AppSettings(llmTemperature = 1.3).sanitizedLlmTemperature, 0.0)
        assertEquals(2.0, AppSettings(llmTemperature = 2.0).sanitizedLlmTemperature, 0.0)
    }

    @Test
    fun outOfRange_clampsToBounds() {
        assertEquals(0.0, AppSettings(llmTemperature = -0.5).sanitizedLlmTemperature, 0.0)
        assertEquals(2.0, AppSettings(llmTemperature = 5.0).sanitizedLlmTemperature, 0.0)
    }

    @Test
    fun nonFinite_fallsBackToDefault() {
        assertEquals(1.0, AppSettings(llmTemperature = Double.NaN).sanitizedLlmTemperature, 0.0)
        assertEquals(1.0, AppSettings(llmTemperature = Double.POSITIVE_INFINITY).sanitizedLlmTemperature, 0.0)
        assertEquals(1.0, AppSettings(llmTemperature = Double.NEGATIVE_INFINITY).sanitizedLlmTemperature, 0.0)
    }
}
