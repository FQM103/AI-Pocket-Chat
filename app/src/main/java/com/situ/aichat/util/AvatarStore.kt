package com.situ.aichat.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Stores character avatars as downscaled JPEGs under `filesDir/avatars`, mirroring how iOS keeps
 * `AICharacter.avatarData` (resized to ~400px). The `avatarPath` column on `CharacterEntity` holds
 * the absolute file path.
 *
 * No Google / GMS dependency: the picker that feeds [save] is the system Photo Picker
 * (`ActivityResultContracts.PickVisualMedia`), which falls back to the Storage Access Framework on
 * China-ROM devices without Play Services. Bytes are read via `ContentResolver`, so no
 * READ_EXTERNAL_STORAGE / READ_MEDIA_IMAGES permission is required. Downscaling is shared with
 * moment/diary photos via [ImageScaler] (here capped at 512px).
 */
object AvatarStore {
    private const val DIR = "avatars"
    private const val MAX_EDGE = 512          // px; iOS resizes avatars to 400, we cap a bit higher
    private const val JPEG_QUALITY = 85

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /**
     * Copy + downscale a picked image into internal storage. Returns the absolute file path, or
     * null on failure (caller keeps the previous avatar). Runs on the IO dispatcher.
     */
    suspend fun save(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull() ?: return@withContext null
        saveBytes(context, bytes)
    }

    /**
     * 存**已裁好的成品图**（头像圆形取景裁剪屏交出来的方形位图·甲 1）：缩到长边 [MAX_EDGE] + 压缩落盘，
     * 返回绝对路径；失败返回 null（调用方保留旧头像·E6）。**不回收入参 [bitmap]**（调用方作用域持有），
     * 仅回收内部缩放副本。逐形对称 [WallpaperStore.save]，常量各用自家的。
     */
    suspend fun save(context: Context, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val scaled = ImageScaler.scaleToMaxEdge(bitmap, MAX_EDGE)
            val file = File(dir(context), "${UUID.randomUUID()}.jpg")
            FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (scaled !== bitmap) scaled.recycle()
            file.absolutePath
        }.getOrNull()
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

    // 头像内存缓存（等价 iOS AvatarCacheService 的 NSCache）。聊天每条 AI 气泡、联系人/朋友圈每行都渲染头像，
    // 此前每次都 BitmapFactory.decodeFile 重解（~1MB/张）：同屏同一头像解码多次、滚动反复解 → CPU + GC 抖动。
    // key = 文件路径（save/saveBytes 永远 mint 新 UUID 文件名 → 换头像即换 path = 自动失效，比 iOS 指纹方案更省）。
    // 故意不在淘汰/删除时 recycle——Compose（及语音通知）可能仍持有该 bitmap 显示中，recycle 会 use-after-free；
    // 交给 GC（同 GiftImageStore.kt 既有约束）。
    private const val CACHE_BYTES = 24 * 1024 * 1024 // 24MB；头像 ≤512px(~1MB) → 约 24 张，够覆盖联系人列表同屏量
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Decode a stored avatar for display. Returns null for a null/blank/missing path. Memory-cached by path. */
    suspend fun load(path: String?): Bitmap? {
        if (path.isNullOrEmpty()) return null
        cache.get(path)?.let { return it } // 命中即同步返回，省去线程切换与磁盘解码（最热的快路径）
        return withContext(Dispatchers.IO) { loadBlocking(path) }
    }

    /**
     * 同步解码（cache-first），供通知发出时刻取角色头像（13.8 · B3 MessagingStyle 气泡）。发出点位于精确闹钟广播
     * 接收者 / 加急 worker，无法 suspend；头像 ≤512px(~1MB) 解码极快且解后入缓存，命中即零开销。缺失 / 解码失败
     * 返回 null（调用方降级为只显示名字气泡）。
     */
    fun loadBlocking(path: String?): Bitmap? {
        if (path.isNullOrEmpty()) return null
        cache.get(path)?.let { return it }
        val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        cache.put(path, bmp)
        return bmp
    }

    /** Delete an avatar file (best-effort). Used when removing an avatar or deleting a character. */
    fun delete(path: String?) {
        if (path.isNullOrEmpty()) return
        cache.remove(path)
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    /** Shrink the avatar cache under system memory pressure (P15.2 #21; evict 不 recycle). */
    fun onTrimMemory(level: Int) = cache.trimForMemoryLevel(level)
}
