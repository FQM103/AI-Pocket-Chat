package com.situ.aichat.widget

import com.situ.aichat.util.ImageScaler
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-32 小组件位图降采样：[widgetTargetPx] 密度感知取目标像素（固定 96/144 在高密度参考机会欠采样发糊，
 * 勘察修正为 ceil(dp×density)）；附 [ImageScaler.computeInSampleSize] 两例锁解码链量级。
 */
class WidgetTargetPxTest {

    @Test
    fun `xiaomi 14 density yields 173px for 60dp avatar`() {
        // 小米 14 460dpi → density≈2.875；ceil(60×2.875)=ceil(172.5)=173。
        assertEquals(173, widgetTargetPx(60f, 2.875f))
    }

    @Test
    fun `2x density thumbnail matches original plan value`() {
        // 72dp×2=144 恰为计划原写死值——计划意图被密度感知方案包含。
        assertEquals(144, widgetTargetPx(72f, 2f))
        assertEquals(216, widgetTargetPx(72f, 3f))
    }

    @Test
    fun `feed avatar at xiaomi density`() {
        assertEquals(75, widgetTargetPx(26f, 2.875f))
    }

    @Test
    fun `zero dp clamps to 1px floor`() {
        assertEquals(1, widgetTargetPx(0f, 3f))
    }

    @Test
    fun `inSampleSize halves 512 avatar toward 173 target`() {
        // AvatarStore 存图上限 512px → 两段式第一段 inSampleSize=2（256px）再精确缩到 173。
        assertEquals(2, ImageScaler.computeInSampleSize(512, 512, 173))
    }

    @Test
    fun `inSampleSize quarters 1024 content image toward 207 target`() {
        // ContentImageStore 存图上限 1024px → inSampleSize=4（256px）再精确缩到 207。
        assertEquals(4, ImageScaler.computeInSampleSize(1024, 1024, 207))
    }
}
