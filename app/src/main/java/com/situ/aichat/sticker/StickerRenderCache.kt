package com.situ.aichat.sticker

import android.graphics.Bitmap
import android.util.LruCache

/**
 * 表情包【静态】首帧 bitmap 的内存缓存（等价 iOS `StickerService.imageCache` 的 NSCache，
 * countLimit 50 / totalCostLimit 30MB）。聊天列表是 LazyColumn——贴纸气泡滚出即 dispose、滚回即重组，
 * 此前每次都重读字节 + 解码（内置 20–90KB 资源解码后约 1MB/张 ARGB），滚动反复付。
 *
 * key = [StickerSource.cacheKey]（内置 asset 路径 / 自定义文件路径；路径含 UUID，导入/删除都换新文件 → 自动失效，
 * 不会读到陈旧图）。**只缓存静态 bitmap**：GIF 的 `AnimatedImageDrawable` 含播放状态，跨多个 ImageView 复用同一
 * 实例会动画错乱/崩，故不入缓存（对齐 iOS：GIF 走原始字节每次解码，不缓存解码产物）。淘汰/移除时**不 recycle**
 * ——Compose 可能仍持有该 bitmap 显示中（同 [com.situ.aichat.ui.gift.GiftImageStore] 既有约束），交给 GC。
 */
object StickerRenderCache {
    private const val CACHE_BYTES = 30 * 1024 * 1024 // 30MB，对齐 iOS StickerService.imageCache.totalCostLimit

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}
