package com.situ.aichat.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Stores moment / diary photos as downscaled JPEGs under `filesDir/content_images`, mirroring how
 * iOS keeps `MomentPost.imageDataArray` / `DiaryEntry.imageDataArray` as external-storage `[Data]`
 * resized to 1024×1024. The entity holds a JSON list of absolute file paths (see
 * `data/model` accessors), not BLOBs.
 *
 * Shared by both Moments (M06) and Diary (M07). Multi-image: [saveAll] copies a batch of picked
 * URIs, skipping any that fail. Same no-GMS path as [AvatarStore]: bytes read via `ContentResolver`
 * (system Photo Picker → SAF fallback on China ROMs), no storage permission, no 3rd-party library.
 */
object ContentImageStore {
    private const val DIR = "content_images"
    private const val MAX_EDGE = 1024          // iOS resizes moment/diary images to 1024
    private const val JPEG_QUALITY = 85

    // 解码内存缓存（P15.2 #1，等价 iOS 外存图解码缓存 + 与 AvatarStore/GiftImageStore 同构）。
    // key = "path@px"：同一图按不同显示尺寸各缓存一份。store 永远 mint 新 UUID 文件名 → 换图即换 path = 自动失效。
    // 故意不在淘汰/删除时 recycle——Compose 可能仍持有显示中（同 AvatarStore/GiftImageStore 既有约束），交给 GC。
    private const val CACHE_BYTES = 32 * 1024 * 1024 // 32MB；内容图 1024px(~4MB)/缩略图(~1MB) 混存
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Copy + downscale one picked image into internal storage. Returns the path, or null on failure. */
    suspend fun save(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull() ?: return@withContext null
        saveBytes(context, bytes)
    }

    /** Copy a batch of picked images, preserving order; failures are dropped (best-effort). */
    suspend fun saveAll(context: Context, uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        uris.mapNotNull { save(context, it) }
    }

    /** Store raw image bytes (e.g. restored from a backup) the same way as a picked image. */
    suspend fun saveBytes(context: Context, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        runCatching {
            val decoded = ImageScaler.decodeSampled(bytes, MAX_EDGE) ?: return@runCatching null
            val scaled = ImageScaler.scaleToMaxEdge(decoded, MAX_EDGE)
            if (scaled !== decoded) decoded.recycle()
            val file = File(dir(context), "${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            scaled.recycle()
            file.absolutePath
        }.getOrNull()
    }

    /**
     * Decode a stored image for display, downsampled to ~[targetPx] on the longest edge and held in
     * an [LruCache] keyed `path@px` (same construct as [AvatarStore] / GiftImageStore; previously a
     * bare per-call `BitmapFactory.decodeFile` of the full 1024px JPEG — the hot path that moment
     * grids / diary thumbnails re-decoded on every scroll). [targetPx] defaults to [MAX_EDGE] (full
     * stored size) so non-thumbnail callers are unchanged but now memory-cached. `decodeSampled`
     * never upscales, so a thumbnail caller passing its small cell px gets a real memory win.
     * Returns null for null/blank/missing path. Evicted bitmaps are NOT recycled (Compose may hold them).
     */
    suspend fun load(path: String?, targetPx: Int = MAX_EDGE): Bitmap? {
        if (path.isNullOrEmpty()) return null
        val key = "$path@$targetPx"
        cache.get(key)?.let { return it } // 命中即同步返回，省线程切换 + 磁盘解码（滚动最热路径）
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = File(path).takeIf { it.exists() }?.readBytes() ?: return@runCatching null
                val decoded = ImageScaler.decodeSampled(bytes, targetPx) ?: return@runCatching null
                val scaled = ImageScaler.scaleToMaxEdge(decoded, targetPx)
                if (scaled !== decoded) decoded.recycle()
                cache.put(key, scaled)
                scaled
            }.getOrNull()
        }
    }

    /** Delete one image file (best-effort) and evict any cached decodes of it. */
    fun delete(path: String?) {
        if (path.isNullOrEmpty()) return
        evictPath(path)
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    /** Delete a batch of image files (best-effort), e.g. when a moment/diary entry is removed. */
    fun delete(paths: List<String>) {
        paths.forEach { delete(it) }
    }

    /** Drop all cached `path@*` decodes for one path (called on [delete]; keys differ only by px). */
    private fun evictPath(path: String) {
        val prefix = "$path@"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
    }

    /** Shrink the decode cache under system memory pressure (called by AIChatApplication.onTrimMemory). */
    fun onTrimMemory(level: Int) = cache.trimForMemoryLevel(level)
}
