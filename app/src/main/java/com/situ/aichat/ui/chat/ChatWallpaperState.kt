package com.situ.aichat.ui.chat

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.situ.aichat.util.WallpaperBlur
import com.situ.aichat.util.WallpaperStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val WALLPAPER_DARK_THRESHOLD = 0.55f

/**
 * 聊天壁纸内存态（chunk3·契约 §3/§4）：清晰图(全屏背景) + 磨砂图(毛玻璃栏复用) + 顶/底条带亮度（玻璃栏「亮度自适应」
 * 按背后那块壁纸选深/浅染色与字色）。仅在 chatWallpaperPath 非空时构造；为空则整族不渲染、聊天逐像素保持现状（§3.4）。
 */
internal data class ChatWallpaper(
    val sharp: ImageBitmap,
    val frosted: ImageBitmap,
    val topDark: Boolean,
    val bottomDark: Boolean,
)

internal suspend fun loadChatWallpaper(path: String): ChatWallpaper? = withContext(Dispatchers.IO) {
    val sharp = WallpaperStore.load(path) ?: return@withContext null
    val frosted = WallpaperStore.loadFrosted(path) ?: return@withContext null
    assembleChatWallpaper(sharp, frosted)
}

/**
 * 同步组装暖缓存壁纸（清晰+磨砂均已缓存才返回；否则 null 交异步 [loadChatWallpaper]）——过渡丝滑化·B3。
 * 亮度条带是廉价抽样运算，仅缓存命中时在组合线程跑一次（每次开会话一次·非每帧），可接受。
 */
internal fun peekChatWallpaper(path: String?): ChatWallpaper? {
    if (path.isNullOrEmpty()) return null
    val sharp = WallpaperStore.peekSharp(path) ?: return null
    val frosted = WallpaperStore.peekFrosted(path) ?: return null
    return assembleChatWallpaper(sharp, frosted)
}

/** 由清晰+磨砂位图组装 [ChatWallpaper]（含顶/底条带亮度）——[loadChatWallpaper] 与 [peekChatWallpaper] 共用。 */
private fun assembleChatWallpaper(sharp: Bitmap, frosted: Bitmap): ChatWallpaper {
    val stripH = (sharp.height * 0.16f).toInt().coerceAtLeast(1)
    val topStrip = Bitmap.createBitmap(sharp, 0, 0, sharp.width, stripH)
    val bottomStrip = Bitmap.createBitmap(sharp, 0, sharp.height - stripH, sharp.width, stripH)
    val topDark = WallpaperBlur.averageLuminance(topStrip) < WALLPAPER_DARK_THRESHOLD
    val bottomDark = WallpaperBlur.averageLuminance(bottomStrip) < WALLPAPER_DARK_THRESHOLD
    topStrip.recycle()
    bottomStrip.recycle()
    return ChatWallpaper(sharp.asImageBitmap(), frosted.asImageBitmap(), topDark, bottomDark)
}
