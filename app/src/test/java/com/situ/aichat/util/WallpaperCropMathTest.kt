package com.situ.aichat.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function tests for [WallpaperCropMath] (壁纸裁剪取景数学·契约 §10). Assertions reason from the contract —
 * cover = image must always fill the frame (no empty edges); sourceRect = the source-pixel region currently framed —
 * so they catch ratio/clamp/round porting bugs that would crash `Bitmap.createBitmap` or store a mis-cropped wallpaper.
 *
 * Convention: frame 1000×2000 (portrait, ratio 0.5) unless noted; offset ≤ 0 under the cover constraint.
 */
class WallpaperCropMathTest {

    private val fw = 1000
    private val fh = 2000

    // ---- coverScale: 宽/高比取大者，保证两轴都 ≥ 框 ----

    @Test fun `coverScale same aspect equals exact fit`() {
        assertEquals(1.0f, WallpaperCropMath.coverScale(1000, 2000, fw, fh), 1e-4f)
        assertEquals(0.5f, WallpaperCropMath.coverScale(2000, 4000, fw, fh), 1e-4f) // bigger, same aspect
    }

    @Test fun `coverScale landscape source is height-limited`() {
        // 4000×2000 into 1000×2000: max(1000/4000, 2000/2000) = 1.0 (height fits, width overflows).
        assertEquals(1.0f, WallpaperCropMath.coverScale(4000, 2000, fw, fh), 1e-4f)
    }

    @Test fun `coverScale tall-thin source is width-limited`() {
        // 500×2000 into 1000×2000: max(1000/500, 2000/2000) = 2.0 (width fits, height overflows).
        assertEquals(2.0f, WallpaperCropMath.coverScale(500, 2000, fw, fh), 1e-4f)
    }

    @Test fun `coverScale degenerate dims fall back to 1`() {
        assertEquals(1.0f, WallpaperCropMath.coverScale(0, 100, fw, fh), 1e-4f)
        assertEquals(1.0f, WallpaperCropMath.coverScale(100, 100, 0, 10), 1e-4f)
    }

    // ---- centerOffset: 溢出轴居中(≤0)，正好填满的轴 offset=0 ----

    @Test fun `centerOffset centers the overflowing axis`() {
        // landscape src, scale 1.0: scaledW 4000 → x=(1000-4000)/2=-1500; scaledH 2000 → y=0.
        val o = WallpaperCropMath.centerOffset(4000, 2000, fw, fh, 1.0f)
        assertEquals(-1500f, o.x, 1e-3f)
        assertEquals(0f, o.y, 1e-3f)
    }

    @Test fun `centerOffset tall-thin centers vertically`() {
        // 500×2000 at cover 2.0: scaledW 1000 → x=0; scaledH 4000 → y=(2000-4000)/2=-1000.
        val o = WallpaperCropMath.centerOffset(500, 2000, fw, fh, 2.0f)
        assertEquals(0f, o.x, 1e-3f)
        assertEquals(-1000f, o.y, 1e-3f)
    }

    // ---- clampOffset: 缩放后源图始终盖住框 ----

    @Test fun `clampOffset pins within cover bounds`() {
        // scaledW 4000 (minX -3000), scaledH 2000 (minY 0).
        val tooRight = WallpaperCropMath.clampOffset(500f, 0f, 1.0f, 4000, 2000, fw, fh)
        assertEquals(0f, tooRight.x, 1e-3f)   // can't pan past left edge
        val tooLeft = WallpaperCropMath.clampOffset(-9999f, 0f, 1.0f, 4000, 2000, fw, fh)
        assertEquals(-3000f, tooLeft.x, 1e-3f) // pinned at right edge
        val inRange = WallpaperCropMath.clampOffset(-1500f, 50f, 1.0f, 4000, 2000, fw, fh)
        assertEquals(-1500f, inRange.x, 1e-3f)
        assertEquals(0f, inRange.y, 1e-3f)     // fitting axis forced to 0
    }

    @Test fun `clampOffset never lets a smaller-than-frame axis drift`() {
        // Defensive: scale below cover (scaledW 800 < frame 1000) → minX coerced to 0 → x pinned 0.
        val o = WallpaperCropMath.clampOffset(40f, -40f, 0.8f, 1000, 2000, fw, fh)
        assertEquals(0f, o.x, 1e-3f)
    }

    // ---- sourceRect: 当前取景 → 源像素矩形 ----

    @Test fun `sourceRect default cover landscape is center vertical strip`() {
        // 4000×2000 cover 1.0 centered (-1500,0): center 1000-wide full-height strip.
        val r = WallpaperCropMath.sourceRect(-1500f, 0f, 1.0f, 4000, 2000, fw, fh)
        assertEquals(CropRect(1500, 0, 1000, 2000), r)
    }

    @Test fun `sourceRect default cover tall-thin is center horizontal strip`() {
        // 500×2000 cover 2.0 centered (0,-1000): full width, center 1000-tall strip.
        val r = WallpaperCropMath.sourceRect(0f, -1000f, 2.0f, 500, 2000, fw, fh)
        assertEquals(CropRect(0, 500, 500, 1000), r)
    }

    @Test fun `sourceRect zoom-in shrinks the sampled region`() {
        // square-ish src 1000×2000 zoomed 2x, centered (-500,-1000): samples 500×1000 from the center.
        val r = WallpaperCropMath.sourceRect(-500f, -1000f, 2.0f, 1000, 2000, fw, fh)
        assertEquals(CropRect(250, 500, 500, 1000), r)
    }

    @Test fun `sourceRect no-zoom exact fit is whole image`() {
        val r = WallpaperCropMath.sourceRect(0f, 0f, 1.0f, 1000, 2000, fw, fh)
        assertEquals(CropRect(0, 0, 1000, 2000), r)
    }

    @Test fun `sourceRect clamps an out-of-bounds offset into source`() {
        // offsetX +50 would read leftF=-50 (off the left) → clamp to 0; right = -50+1000 = 950.
        val r = WallpaperCropMath.sourceRect(50f, 0f, 1.0f, 1000, 2000, fw, fh)
        assertEquals(0, r.left)
        assertEquals(950, r.width)
    }

    @Test fun `sourceRect degenerate scale returns full source`() {
        assertEquals(CropRect(0, 0, 1234, 5678), WallpaperCropMath.sourceRect(0f, 0f, 0f, 1234, 5678, fw, fh))
    }

    @Test fun `sourceRect never returns zero width or height`() {
        // Absurd zoom → frame/scale rounds toward 0; width/height保底 1 (防 createBitmap 崩).
        val r = WallpaperCropMath.sourceRect(0f, 0f, 100000f, 1000, 2000, fw, fh)
        assertEquals(true, r.width >= 1 && r.height >= 1)
    }
}
