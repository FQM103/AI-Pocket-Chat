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
 * 角色聊天壁纸的文件存储（仿 [AvatarStore]）。壁纸是全屏大图，独立目录 `filesDir/wallpapers`，长边 cap [MAX_EDGE]；
 * `CharacterEntity.chatWallpaperPath` 列存绝对路径。选图走系统 PhotoPicker（`PickVisualMedia`，无 GMS，国行回退 SAF），
 * 字节经 ContentResolver 读，免 READ_MEDIA 权限；降采样复用 [ImageScaler]。
 *
 * 两层缓存：[load] 清晰全屏图（聊天/见面背景）+ [loadFrosted] 预糊磨砂小图（毛玻璃栏，[WallpaperBlur.frost] 只算一次）。
 * 换图/删除时 [delete] 清旧文件防孤儿（与 AvatarStore 同约束：淘汰不 recycle，Compose 可能仍在显示）。
 *
 * 见 FABLE5_CHAT_WALLPAPER_PROPOSAL.md §5。
 */
object WallpaperStore {
    private const val DIR = "wallpapers"
    private const val MAX_EDGE = 1920        // px；全屏竖图，平衡画质/体积（iOS 用 2000）
    private const val JPEG_QUALITY = 88

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** 选图后拷 + 降采样进内部存储，返回绝对路径；失败返回 null（调用方保留旧壁纸）。IO 线程。 */
    suspend fun save(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            .getOrNull() ?: return@withContext null
        saveBytes(context, bytes)
    }

    /**
     * 存**已裁好的成品图**（裁剪取景编辑器·契约 §10 C2「存裁好的成品图」）：缩到长边 [MAX_EDGE] + 压缩落盘，
     * 返回绝对路径；失败返回 null（调用方保留旧壁纸）。**不回收入参 [bitmap]**（调用方持有），仅回收内部缩放副本。
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

    /** 存原始字节（如备份恢复）同 [save]。 */
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

    // 清晰壁纸缓存：全屏图较大（~1080×1920 ARGB ≈ 8MB/张），容量按当前角色 + 最近切换估。
    private const val SHARP_CACHE_BYTES = 32 * 1024 * 1024
    private val sharpCache = object : LruCache<String, Bitmap>(SHARP_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // 磨砂（预糊）小图缓存：每张壁纸 frost 一次，玻璃栏复用（[WallpaperBlur] 降采样后 ≈ 260px，~0.3MB/张）。
    private const val FROST_CACHE_BYTES = 8 * 1024 * 1024
    private val frostCache = object : LruCache<String, Bitmap>(FROST_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 解码清晰壁纸（cache-first）。空/缺失路径返回 null。 */
    suspend fun load(path: String?): Bitmap? {
        if (path.isNullOrEmpty()) return null
        sharpCache.get(path)?.let { return it }
        return withContext(Dispatchers.IO) {
            sharpCache.get(path)?.let { return@withContext it }
            val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return@withContext null
            sharpCache.put(path, bmp)
            bmp
        }
    }

    /** 预糊磨砂副本（毛玻璃栏用）：按 path 缓存，缺失时从清晰图 [WallpaperBlur.frost] 一次（CPU，Default 线程）。 */
    suspend fun loadFrosted(path: String?): Bitmap? {
        if (path.isNullOrEmpty()) return null
        frostCache.get(path)?.let { return it }
        val sharp = load(path) ?: return null
        return withContext(Dispatchers.Default) {
            frostCache.get(path)?.let { return@withContext it }
            val frosted = WallpaperBlur.frost(sharp)
            frostCache.put(path, frosted)
            frosted
        }
    }

    /**
     * 同步读已缓存的清晰壁纸（命中返回·未命中返回 null·绝不触发 IO/解码）——首帧即用暖缓存，
     * 消除"壁纸晚一拍弹入"（过渡丝滑化·B3）。
     */
    fun peekSharp(path: String?): Bitmap? = if (path.isNullOrEmpty()) null else sharpCache.get(path)

    /** 同步读已缓存的磨砂壁纸（同 [peekSharp]）。 */
    fun peekFrosted(path: String?): Bitmap? = if (path.isNullOrEmpty()) null else frostCache.get(path)

    /** 删壁纸文件（best-effort）+ 清两层缓存。换图/移除壁纸/删角色时调。 */
    fun delete(path: String?) {
        if (path.isNullOrEmpty()) return
        sharpCache.remove(path)
        frostCache.remove(path)
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    /** 纯函数：[existingFiles] 中不被 [referenced] 引用的孤儿路径（裁剪重选/取消未保存遗留·便于单测·边界）。 */
    fun findOrphans(existingFiles: List<String>, referenced: Set<String>): List<String> =
        existingFiles.filter { it !in referenced }

    /**
     * 清 `filesDir/wallpapers/` 下**无任何角色引用**的孤儿文件（裁剪编辑重选中间图 / 裁完取消未保存 / 删角色残留），
     * 返回删除数。冷启维护调（off-main）。[referenced] = 全部角色 `chatWallpaperPath` 绝对路径集（DAO 取）——
     * 因只删「不在引用集」的文件，**绝不误删在用壁纸**（契约 §5.3「防孤儿」+ 备份模块孤儿清理先例）。
     */
    suspend fun purgeOrphans(context: Context, referenced: Set<String>): Int = withContext(Dispatchers.IO) {
        val files = dir(context).listFiles()?.mapNotNull { it.takeIf(File::isFile)?.absolutePath } ?: return@withContext 0
        val orphans = findOrphans(files, referenced)
        orphans.forEach { delete(it) }
        orphans.size
    }

    /** 内存压力下收缩缓存（同 AvatarStore：evict 不 recycle）。 */
    fun onTrimMemory(level: Int) {
        sharpCache.trimForMemoryLevel(level)
        frostCache.trimForMemoryLevel(level)
    }
}
