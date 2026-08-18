package com.situ.aichat.util

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 壁纸裁剪取景的纯数学（契约 FABLE5_CHAT_WALLPAPER_PROPOSAL.md §10·C1/C2「所见即所得」）：源图在「取景框」内
 * 以 [scale] 缩放 + offset 平移显示，约束 = 图必须**始终铺满取景框**（cover·不露边）；点完成时把当前取景
 * 换算成**源图像素矩形** [sourceRect] 去裁出成品图存盘（C2）。
 *
 * 全部纯函数（无 Android/Compose 依赖·JVM 可单测·边界/钳位/舍入是稳定性关键）。坐标单位 = 取景框像素；
 * offset = 缩放后源图左上角相对取景框左上角的位移（cover 约束下两值恒 ≤ 0）。
 */
object WallpaperCropMath {

    /** 铺满（cover·= `ContentScale.Crop`）所需最小缩放：宽/高比取大者，保证缩放后两轴都 ≥ 取景框。 */
    fun coverScale(srcW: Int, srcH: Int, frameW: Int, frameH: Int): Float {
        if (srcW <= 0 || srcH <= 0 || frameW <= 0 || frameH <= 0) return 1f
        return max(frameW.toFloat() / srcW, frameH.toFloat() / srcH)
    }

    /** 居中铺满的初始 offset（= `ContentScale.Crop` 居中取景·两值恒 ≤ 0）。 */
    fun centerOffset(srcW: Int, srcH: Int, frameW: Int, frameH: Int, scale: Float): CropOffset =
        CropOffset((frameW - srcW * scale) / 2f, (frameH - srcH * scale) / 2f)

    /** 钳 offset 使缩放后源图始终盖住取景框（左/上 ≤ 0、右/下 ≥ 框边）。[scale] 应 ≥ [coverScale]。 */
    fun clampOffset(x: Float, y: Float, scale: Float, srcW: Int, srcH: Int, frameW: Int, frameH: Int): CropOffset {
        val minX = (frameW - srcW * scale).coerceAtMost(0f)
        val minY = (frameH - srcH * scale).coerceAtMost(0f)
        return CropOffset(x.coerceIn(minX, 0f), y.coerceIn(minY, 0f))
    }

    /**
     * 当前取景 → 源图像素裁剪矩形（喂 `Bitmap.createBitmap`）。四角先按 1/scale 反算到源坐标、四舍五入，
     * 再钳进 [0,srcW]×[0,srcH]，宽高保底 ≥ 1（防退化空矩形崩 createBitmap）。
     */
    fun sourceRect(offsetX: Float, offsetY: Float, scale: Float, srcW: Int, srcH: Int, frameW: Int, frameH: Int): CropRect {
        if (scale <= 0f || srcW <= 0 || srcH <= 0) return CropRect(0, 0, srcW.coerceAtLeast(1), srcH.coerceAtLeast(1))
        val leftF = -offsetX / scale
        val topF = -offsetY / scale
        val left = leftF.roundToInt().coerceIn(0, srcW)
        val top = topF.roundToInt().coerceIn(0, srcH)
        val right = (leftF + frameW / scale).roundToInt().coerceIn(0, srcW)
        val bottom = (topF + frameH / scale).roundToInt().coerceIn(0, srcH)
        return CropRect(left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1))
    }
}

/** 纯数据 2D 位移（避开 Compose `Offset` 依赖·便于 JVM 单测）。 */
data class CropOffset(val x: Float, val y: Float)

/** 源图像素裁剪矩形（左上 + 宽高·= `Bitmap.createBitmap(src, left, top, width, height)` 参数）。 */
data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)
