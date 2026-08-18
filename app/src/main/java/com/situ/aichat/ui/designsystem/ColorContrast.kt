package com.situ.aichat.ui.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.2 对比度计算（唯一合规 gate·正文 4.5:1·大字与组件 3:1）。纯函数，供 `ColorContrastTest`
 * 枚举所有「文字×底」组合断言比值看门（见 [FABLE5_DESIGN_LANGUAGE.md] §1.4）。
 */
object ColorContrast {

    /** WCAG 相对亮度（sRGB 通道线性化后加权·Compose Color 通道本身已是 0..1）。 */
    fun relativeLuminance(color: Color): Double {
        fun lin(c: Float): Double {
            val d = c.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(color.red) + 0.7152 * lin(color.green) + 0.0722 * lin(color.blue)
    }

    /** 两色对比度比值（1.0–21.0）。 */
    fun ratio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
