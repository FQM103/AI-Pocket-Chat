package com.situ.aichat.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max

/**
 * Shared image downscaling used by both [AvatarStore] (character avatars, 512px) and
 * [ContentImageStore] (moment/diary photos, 1024px). The algorithm is identical — a two-pass
 * `inSampleSize` decode to avoid OOM on huge gallery images, then an exact scale so the longest
 * edge equals `maxEdge` — only the cap differs, so it lives here once (CLAUDE.md §2: one copy).
 *
 * No third-party image library; pure `BitmapFactory`. [computeInSampleSize] is `internal` + pure so
 * it can be unit-tested without a device.
 */
object ImageScaler {

    /** Two-pass decode with `inSampleSize` so a huge image never blows up memory. */
    fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * Largest power-of-two sample factor that keeps both edges >= `maxEdge` (so the decoded bitmap
     * is at least `maxEdge` on its longest side, then [scaleToMaxEdge] trims to exact).
     */
    internal fun computeInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (max(w, h) / 2 >= maxEdge) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /** Exact downscale so the longest edge == `maxEdge` (only when still larger after sampling). */
    fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
