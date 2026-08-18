package com.situ.aichat.ui.settings

import com.situ.aichat.data.model.ToolSupportLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1 for [resolveToolDetectionKind] —— 锁住 §9 配置页状态块的判定语义：检测中优先、UNKNOWN≠不支持、
 * UNSUPPORTED→兼容兜底。断言从 §9 规格反推，非照搬实现。
 */
class ToolDetectionStatusTest {

    @Test
    fun `detecting overrides every persisted level`() {
        // 重测期间一律转圈，绝不把上一次的旧结论叠在转圈上。
        ToolSupportLevel.entries.forEach { level ->
            assertEquals(
                "level=$level while detecting should show Detecting",
                ToolDetectionStatusKind.Detecting,
                resolveToolDetectionKind(level, detecting = true),
            )
        }
    }

    @Test
    fun `full level maps to full support`() {
        assertEquals(
            ToolDetectionStatusKind.FullSupport,
            resolveToolDetectionKind(ToolSupportLevel.FULL, detecting = false),
        )
    }

    @Test
    fun `basic level maps to basic support`() {
        assertEquals(
            ToolDetectionStatusKind.BasicSupport,
            resolveToolDetectionKind(ToolSupportLevel.BASIC, detecting = false),
        )
    }

    @Test
    fun `unsupported maps to compatible fallback — never an alarm state`() {
        assertEquals(
            ToolDetectionStatusKind.UnsupportedFallback,
            resolveToolDetectionKind(ToolSupportLevel.UNSUPPORTED, detecting = false),
        )
    }

    @Test
    fun `unknown maps to not detected — never conflated with unsupported`() {
        assertEquals(
            ToolDetectionStatusKind.NotDetected,
            resolveToolDetectionKind(ToolSupportLevel.UNKNOWN, detecting = false),
        )
    }
}
