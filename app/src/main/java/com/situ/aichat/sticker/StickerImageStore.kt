package com.situ.aichat.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import com.situ.aichat.util.ImageScaler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Stores custom sticker images under `filesDir/stickers/<uuid>.<ext>` (path kept in
 * [com.situ.aichat.data.local.entity.CustomStickerEntity.imagePath]; never a BLOB in SQLite). Mirrors
 * iOS `StickerImportView` save: a static sticker is downscaled to ≤512px PNG; a GIF is stored raw
 * (no recompress) so the animation survives. Built-in stickers are packed assets and are NOT handled
 * here — the renderer loads them straight from `assets/stickers/`.
 *
 * GIF detection is Android-native via `ImageDecoder` (the 1:1 equivalent of iOS `isAnimatedGIFData`'s
 * "magic byte + decodable + multi-frame" — a single-frame or corrupt GIF decodes as a plain
 * `BitmapDrawable`/throws, so it is treated as static). No third-party library, no GMS.
 */
object StickerImageStore {
    private const val DIR = "stickers"
    private const val MAX_EDGE = 512 // iOS StickerImportView resizes static stickers to 512px

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /**
     * Strictly detect an animated GIF (1:1 iOS `isAnimatedGIFData`): GIF magic prefilter, then
     * `ImageDecoder` must decode it AND yield an [AnimatedImageDrawable] (multi-frame). Single-frame
     * GIFs and corrupt data return false → handled as static, never blank.
     */
    fun isAnimatedGif(bytes: ByteArray): Boolean {
        if (!StickerService.looksLikeGifHeader(bytes)) return false
        return try {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
            ImageDecoder.decodeDrawable(source) is AnimatedImageDrawable
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Persist imported sticker bytes. GIF → raw `.gif`; static → downscaled 512px `.png`.
     * Returns the absolute path, or null on failure (e.g. a static image that cannot be decoded).
     */
    suspend fun save(context: Context, bytes: ByteArray, isAnimated: Boolean): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (isAnimated) {
                val file = File(dir(context), "${UUID.randomUUID()}.gif")
                file.writeBytes(bytes)
                file.absolutePath
            } else {
                val decoded = ImageScaler.decodeSampled(bytes, MAX_EDGE) ?: return@runCatching null
                val scaled = ImageScaler.scaleToMaxEdge(decoded, MAX_EDGE)
                if (scaled !== decoded) decoded.recycle()
                val file = File(dir(context), "${UUID.randomUUID()}.png")
                FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                scaled.recycle()
                file.absolutePath
            }
        }.getOrNull()
    }

    /** Decode a stored static sticker bitmap (custom). Null for blank/missing path. */
    suspend fun loadBitmap(path: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (path.isNullOrEmpty()) return@withContext null
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    /** Read raw bytes (custom GIF playback via `AnimatedImageDrawable`). Null for blank/missing path. */
    suspend fun loadBytes(path: String?): ByteArray? = withContext(Dispatchers.IO) {
        if (path.isNullOrEmpty()) return@withContext null
        runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()
    }

    /** Delete a sticker image file (best-effort), e.g. when the custom sticker is removed. */
    fun delete(path: String?) {
        if (path.isNullOrEmpty()) return
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }
}
