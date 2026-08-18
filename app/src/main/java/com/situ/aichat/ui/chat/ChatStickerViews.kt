package com.situ.aichat.ui.chat

import com.situ.aichat.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.situ.aichat.ui.designsystem.AppTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.situ.aichat.data.local.entity.CustomStickerEntity
import com.situ.aichat.sticker.BuiltInStickerCatalog
import com.situ.aichat.sticker.StickerImageStore
import com.situ.aichat.sticker.StickerRenderCache
import com.situ.aichat.sticker.StickerService
import com.situ.aichat.sticker.StickerSource
import com.situ.aichat.sticker.StickerTagParser
import com.situ.aichat.ui.components.rememberReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * 聊天气泡里的表情包渲染（对齐 iOS `StickerContentView` + `MixedStickerContentView`）。内置走 `assets/stickers/`，
 * 自定义走内部文件；GIF 用 Android 原生 `AnimatedImageDrawable`（无 Coil，对齐项目「不引图片库」决策），
 * 坏 GIF 自动降级首帧静态，找不到则坍缩为空（对齐 iOS `.failed` → EmptyView）。
 */
private sealed interface StickerRender {
    data class Static(val bitmap: Bitmap) : StickerRender
    data class Animated(val drawable: AnimatedImageDrawable) : StickerRender
    object Failed : StickerRender
}

/** 字节解码：动图优先 `ImageDecoder`（多帧→Animated/单帧→Static），失败回退 `BitmapFactory` 首帧。 */
private fun decodeStickerBytes(bytes: ByteArray, preferAnimated: Boolean): StickerRender {
    if (preferAnimated) {
        val drawable = runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        }.getOrNull()
        if (drawable is AnimatedImageDrawable) return StickerRender.Animated(drawable)
        if (drawable is BitmapDrawable) drawable.bitmap?.let { return StickerRender.Static(it) }
    }
    val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    return if (bmp != null) StickerRender.Static(bmp) else StickerRender.Failed
}

/**
 * 解码一个已解析来源的 sticker：静态首帧命中 [StickerRenderCache] 即同步返回（省线程切换+字节读+解码），
 * 否则在 IO 线程读字节（内置读 asset、自定义读内部文件）→ [decodeStickerBytes]，静态结果写回缓存。
 * GIF 不缓存（[StickerRenderCache] 注释：含播放状态不可共享，对齐 iOS）。
 */
private suspend fun loadStickerRender(context: Context, source: StickerSource): StickerRender {
    StickerRenderCache.get(source.cacheKey)?.let { return StickerRender.Static(it) } // 命中即同步返回
    return withContext(Dispatchers.IO) {
        StickerRenderCache.get(source.cacheKey)?.let { return@withContext StickerRender.Static(it) } // 双检
        val (bytes, isAnimated) = when (source) {
            is StickerSource.Asset ->
                runCatching { context.assets.open(source.assetPath).use { it.readBytes() } }.getOrNull() to source.isAnimated
            is StickerSource.CustomFile ->
                StickerImageStore.loadBytes(source.filePath) to source.isAnimated
        }
        if (bytes == null) return@withContext StickerRender.Failed
        val render = decodeStickerBytes(bytes, isAnimated)
        if (render is StickerRender.Static) StickerRenderCache.put(source.cacheKey, render.bitmap)
        render
    }
}

/** 单个表情包图片（默认 120dp，对齐 iOS 气泡内尺寸）。失败坍缩为 0，已知内置加载中显灰占位防跳动。 */
@Composable
fun StickerImage(stickerId: String, customStickers: List<CustomStickerEntity>, size: Dp = 120.dp) {
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()
    val knownBuiltIn = remember(stickerId) { BuiltInStickerCatalog.byId.containsKey(stickerId) }
    // 先按 id 廉价解析来源（仅列表查找、无解码）。解码只依赖解析出的来源（data class 值相等）：
    // 仅当该 id 对应的真实来源变化时才重解码——避免此前「整个 customStickers 列表当 key」导致任一贴纸
    // 导入/删除就触发全屏贴纸气泡重解（对齐 iOS「一次性解码、非持续观察」的本意）。
    val source = remember(stickerId, customStickers) { StickerService.resolveSource(stickerId, customStickers) }
    var render by remember(source) { mutableStateOf<StickerRender?>(null) }
    LaunchedEffect(source) {
        render = if (source == null) StickerRender.Failed else loadStickerRender(context, source)
    }
    when (val r = render) {
        is StickerRender.Static -> Image(
            bitmap = r.bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size),
        )
        // 动画贴纸用固定尺寸 Box 包住 AndroidView——AndroidView 在 LazyColumn 里高度是异步测量的（drawable 加载
        // 前后变化），直接 Modifier.size 会让 LazyColumn 按「贴纸未加载」缓存矮 item 高度、定位好下方打字气泡后，
        // 贴纸长出却不重测 item → 时间戳/打字气泡错位重叠（2026-06-22 真机实证）。Box(size) 提供与内容无关的确定
        // 布局，从初次组合就占满 size，AndroidView 用 matchParentSize 填满它，item 高度自始至终稳定。
        is StickerRender.Animated -> Box(Modifier.size(size)) {
            AndroidView(
                factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { iv ->
                    iv.setImageDrawable(r.drawable)
                    // 系统「减弱动画」开 → 定格首帧（不 start）；否则确保在播（对齐 VoiceWaveform/TypingDots 的 RM 处理）。
                    if (reduceMotion) {
                        if (r.drawable.isRunning) r.drawable.stop()
                    } else if (!r.drawable.isRunning) {
                        r.drawable.start()
                    }
                },
                modifier = Modifier.matchParentSize(),
            )
            // 划出屏幕（LazyColumn 回收本 item·离开组合）即停循环——省渲染线程开销 + 省电（国产 ROM 友好）；
            // 划回时重组走 update 重启（用户已确认「划回重新开始」可接受）。键 r.drawable：换图也停旧 drawable。
            DisposableEffect(r.drawable) {
                onDispose { if (r.drawable.isRunning) r.drawable.stop() }
            }
        }
        StickerRender.Failed -> Unit // 不占位（坍缩 0）
        null -> Box( // 加载中：已知内置显灰圆角占位，其余仅预留尺寸防跳动
            Modifier
                .size(size)
                .then(
                    if (knownBuiltIn) {
                        Modifier.clip(RoundedCornerShape(12.dp)).background(AppTheme.colors.surface.sunken) // 审计 T2：经桥同值换 token（12dp 圆角=有意中间档·保留）
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

/** 纯表情包消息：无气泡背景，竖排大图（spacing 4，对齐 iOS `stickerOnlyContent`）。长按转交菜单。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerStack(
    content: String,
    customStickers: List<CustomStickerEntity>,
    onLongClick: () -> Unit,
    a11yDescription: String? = null,
) {
    val ids = remember(content) { StickerTagParser.extractStickerIds(content) }
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .combinedClickable(onClick = {}, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.a11y_message_menu)) // Y2
            // P1-1：合并朗读句（「…说：[表情包]」）——否则纯贴纸消息无可读文本、或读出裸 [sticker:] 标签。
            .then(a11yDescription?.let { Modifier.semantics { contentDescription = it } } ?: Modifier),
    ) {
        ids.forEach { StickerImage(it, customStickers, 120.dp) }
    }
}
