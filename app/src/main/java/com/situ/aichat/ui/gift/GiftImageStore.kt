package com.situ.aichat.ui.gift

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.situ.aichat.util.ImageScaler
import com.situ.aichat.util.trimForMemoryLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 礼物目录图片加载器（9.2d d-1，等价 iOS `GiftImageView` 的 `preparingThumbnail` + `GiftThumbnailCache`）。
 *
 * 46 张 1024×1024 JPEG 打进 `assets/giftimages/<giftItemId>.jpg`。若直接全尺寸解码，LazyVerticalGrid 首屏一齐解码
 * 会 OOM/卡顿——故沿用项目既有的 [ImageScaler] 两段式 `inSampleSize` 降采样（仿 ContentImageStore，**不引 Coil**），
 * 在 IO 线程把图下采样到目标显示像素，主线程不拆全尺寸 bitmap。
 *
 * 缓存：`LruCache` 按 `"id@px"` 缓存已降采样 bitmap（等价 iOS `GiftThumbnailCache` NSCache，countLimit=100 /
 * totalCostLimit=50MB）。**故意不在淘汰时 recycle**——Compose 可能仍持有该 bitmap 显示中，recycle 会 use-after-free；
 * 交给 GC（iOS NSCache 同语义，SwiftUI 持自己的 Image 拷贝）。
 *
 * DIY 礼物（id 前缀 "diy_"）无对应 asset，[load] 直接返回 null，由 [GiftImage] 走 Material Icon 兜底。
 */
object GiftImageStore {
    private const val ASSET_DIR = "giftimages"
    private const val CACHE_BYTES = 50 * 1024 * 1024 // 50MB，对齐 iOS GiftThumbnailCache.totalCostLimit

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * 加载礼物目录图，降采样到长边约 [targetPx] 像素。DIY / 缺失资源 → null（调用方兜底图标）。
     * @param targetPx 目标显示像素（由 dp×density 算出，等价 iOS points×displayScale）。
     */
    suspend fun load(context: Context, giftItemId: String, targetPx: Int): Bitmap? {
        if (giftItemId.startsWith("diy_")) return null
        val key = "$giftItemId@$targetPx"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = runCatching {
                context.assets.open("$ASSET_DIR/$giftItemId.jpg").use { it.readBytes() }
            }.getOrNull() ?: return@withContext null
            val decoded = ImageScaler.decodeSampled(bytes, targetPx) ?: return@withContext null
            val scaled = ImageScaler.scaleToMaxEdge(decoded, targetPx)
            if (scaled !== decoded) decoded.recycle()
            cache.put(key, scaled)
            scaled
        }
    }

    /** Shrink the gift-thumbnail cache under system memory pressure (P15.2 #21; evict 不 recycle). */
    fun onTrimMemory(level: Int) = cache.trimForMemoryLevel(level)
}
