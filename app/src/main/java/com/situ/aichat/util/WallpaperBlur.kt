package com.situ.aichat.util

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 聊天壁纸毛玻璃的「真实背景模糊」算子（契约 FABLE5_CHAT_WALLPAPER_PROPOSAL.md §4）。
 *
 * 因壁纸是自有的**静态** bitmap，模糊**只算一次**、结果小图缓存，玻璃栏每帧零模糊成本（守 HyperOS 性能铁律#5）。
 * 实现取「降采样 + 多趟盒模糊（≈高斯）」：跨 API 一致、纯 Kotlin、可单测，无 RenderEffect/HardwareRenderer 的版本碎片
 * （API 31+ 的 RenderEffect 是日后可选的更锐升级，非必需——小图被放大铺满全屏时本就自带额外柔化）。
 *
 * [boxBlur] 为 `internal` 纯函数，便于在 JVM 单测里锁定（均匀场不变 / 单点扩散 / 对称性 / 边界钳位）。
 */
object WallpaperBlur {

    /** 模糊前把最长边降到此像素（小=便宜，且放大铺屏时更柔）。 */
    private const val BLUR_TARGET_EDGE = 260
    private const val DEFAULT_RADIUS = 16
    private const val DEFAULT_PASSES = 3

    /**
     * 生成 [src] 的「磨砂」副本（降采样 + 盒模糊）。结果是一张小图（最长边 ≈ [targetEdge]），由玻璃栏放大铺满全屏。
     * 一次性成本；不回收 [src]（调用方另作清晰背景用）。
     */
    fun frost(
        src: Bitmap,
        targetEdge: Int = BLUR_TARGET_EDGE,
        radius: Int = DEFAULT_RADIUS,
        passes: Int = DEFAULT_PASSES,
    ): Bitmap {
        val longest = max(src.width, src.height)
        val scale = if (longest > targetEdge) targetEdge.toFloat() / longest else 1f
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        boxBlur(pixels, w, h, radius.coerceAtLeast(1), passes.coerceAtLeast(1))
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== out) small.recycle()
        return out
    }

    /**
     * 整图平均亮度（0..1，ITU-R BT.601：0.299R+0.587G+0.114B），用于玻璃栏「亮度自适应」选深/浅染色与字色
     * （对齐 iOS 按壁纸亮度选字色）。[sampleStep] 抽样步长降成本（壁纸亮度无需逐像素）。
     */
    fun averageLuminance(bmp: Bitmap, sampleStep: Int = 4): Float {
        val w = bmp.width
        val h = bmp.height
        if (w == 0 || h == 0) return 1f
        var sum = 0.0
        var count = 0
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                sum += 0.299 * r + 0.587 * g + 0.114 * b
                count++
                x += sampleStep
            }
            y += sampleStep
        }
        return if (count == 0) 1f else (sum / count / 255.0).toFloat()
    }

    /**
     * 可分离盒模糊，原地写回 [pixels]（横一趟 + 竖一趟为一 pass，[passes] 趟后 ≈ 高斯）。边界按「钳到边缘像素」
     * 处理（extend-edge）。纯函数，无 Android 依赖，便于单测。
     */
    internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int) {
        require(pixels.size == width * height) { "pixels 长度须等于 width*height" }
        if (width <= 0 || height <= 0 || radius < 1 || passes < 1) return
        val tmp = IntArray(pixels.size)
        repeat(passes) {
            blurPass(pixels, tmp, width, height, radius, horizontal = true)
            blurPass(tmp, pixels, width, height, radius, horizontal = false)
        }
    }

    private fun blurPass(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val window = radius * 2 + 1
        for (y in 0 until height) {
            for (x in 0 until width) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (k in -radius..radius) {
                    val sx: Int
                    val sy: Int
                    if (horizontal) {
                        sx = (x + k).coerceIn(0, width - 1)
                        sy = y
                    } else {
                        sx = x
                        sy = (y + k).coerceIn(0, height - 1)
                    }
                    val c = src[sy * width + sx]
                    a += (c ushr 24) and 0xFF
                    r += (c ushr 16) and 0xFF
                    g += (c ushr 8) and 0xFF
                    b += c and 0xFF
                }
                dst[y * width + x] =
                    ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            }
        }
    }
}
