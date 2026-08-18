package com.situ.aichat.ui.gift

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.gift.GiftItem

/**
 * 礼物图片显示组件（9.2d d-1，1:1 iOS `GiftImageView`）。
 *
 * - 正方形图，奶油纸渐变背景 + 圆角裁切 + 可选软阴影。
 * - 异步从 `assets/giftimages/` 降采样加载（[GiftImageStore]）；加载中显示纸底，加载完无图时显示琥珀金 Material 兜底图标。
 * - DIY / 资源缺失 → Material Icon 优雅降级（等价 iOS SF Symbol 兜底）。
 *
 * @param giftItemId 礼物目录 ID（如 "gift_boba_tea"）或 DIY 标识（"diy_..."）。
 * @param fallbackSymbol 兜底 SF Symbol 名（经 [GiftSymbolMapping] 映射 Material Icon）。
 * @param size 边长（正方形）。
 * @param backgroundColor 非空时替换奶油纸渐变作图区底（Fable-5 聊天礼物卡传 surface.sunken 主题自适应；
 *   礼物店/收礼盒等未换装屏不传保持原样）。
 */
@Composable
fun GiftImage(
    giftItemId: String,
    fallbackSymbol: String,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    showsShadow: Boolean = true,
    backgroundColor: Color? = null,
) {
    val context = LocalContext.current
    val targetPx = with(LocalDensity.current) { size.roundToPx() }
    var bitmap by remember(giftItemId, targetPx) { mutableStateOf<Bitmap?>(null) }
    var loadFinished by remember(giftItemId, targetPx) { mutableStateOf(false) }

    LaunchedEffect(giftItemId, targetPx) {
        bitmap = GiftImageStore.load(context, giftItemId, targetPx)
        loadFinished = true
    }

    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .then(if (showsShadow) Modifier.shadow(elevation = 6.dp, shape = shape, clip = false) else Modifier)
            .clip(shape)
            .then(
                if (backgroundColor != null) {
                    Modifier.background(backgroundColor)
                } else {
                    Modifier.background(Brush.linearGradient(listOf(GiftColors.PaperStart, GiftColors.PaperEnd)))
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null, // 装饰图，外层卡片/详情页已承担语义（对齐 iOS accessibilityHidden）
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            loadFinished -> Icon(
                imageVector = GiftSymbolMapping.materialIcon(fallbackSymbol),
                contentDescription = null,
                tint = GiftColors.FallbackIcon,
                modifier = Modifier.size(size * 0.38f), // iOS: font size = size * 0.38
            )
            // 加载中：纸底占位（对齐 iOS Color.clear over paperBackground）
        }
    }
}

/** 从 [GiftItem] 构造，自动注入 fallbackSymbol（1:1 iOS `init(item:)`）。 */
@Composable
fun GiftImage(
    item: GiftItem,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    showsShadow: Boolean = true,
) = GiftImage(item.id, item.fallbackSymbol, size, modifier, cornerRadius, showsShadow)

/**
 * 从 [GiftRecordEntity] 构造（1:1 iOS `init(record:)`）：
 * - DIY 礼物用 paintbrush 图标；
 * - 预置礼物查目录 fallbackSymbol，查不到用 gift.fill（终极兜底）。
 */
@Composable
fun GiftImage(
    record: GiftRecordEntity,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    showsShadow: Boolean = true,
) {
    val symbol = if (record.isDIY) "paintbrush.fill"
    else GiftCatalog.find(record.giftItemId)?.fallbackSymbol ?: "gift.fill"
    GiftImage(record.giftItemId, symbol, size, modifier, cornerRadius, showsShadow)
}
