package com.situ.aichat.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.situ.aichat.ui.components.AvatarColor
import com.situ.aichat.util.ImageScaler
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 13.9 桌面小组件共用的 Glance 展示片段（角色「此刻」状态 + 最新动态等沿用同款外观，避免重复）。
 * 宠物小组件（11.3）有自带含心情色调的 surface，故不并入此处，免回归已发布的宠物组件。
 */

/** 主题底色 + 圆角 + 整块点击区（深浅皆可读）。 */
@Composable
internal fun WidgetSurface(onBodyClick: Action, content: @Composable () -> Unit) {
    Box(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground).cornerRadius(16.dp).clickable(onBodyClick),
    ) {
        content()
    }
}

/** 纯函数（P1-32）：显示 dp × 屏幕密度 → 解码目标像素。密度感知，固定值在高密度机（小米 14 ≈2.875）会欠采样发糊。 */
internal fun widgetTargetPx(displayDp: Float, density: Float): Int =
    ceil(displayDp * density).toInt().coerceAtLeast(1)

/**
 * 小组件专用头像解码（P1-32）：按目标像素降采样后再精确缩放（复用 [ImageScaler] 两段式，绝不放大）。
 * 不走 AvatarStore 缓存（其 key=裸 path 恒存全尺寸，混入缩小图会污染 App 内聊天/联系人头像渲染）、
 * 自身也不缓存——widget 刷新低频（事件驱动 + 30 分兜底），单次解码 ≤512px 文件开销可忽略。
 * 失败/缺路径返回 null → [Avatar] 回退字母图（既有行为）。
 */
internal suspend fun decodeAvatarForWidget(path: String?, targetPx: Int): Bitmap? {
    if (path.isNullOrEmpty()) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val bytes = File(path).takeIf { it.exists() }?.readBytes() ?: return@runCatching null
            val decoded = ImageScaler.decodeSampled(bytes, targetPx) ?: return@runCatching null
            val scaled = ImageScaler.scaleToMaxEdge(decoded, targetPx)
            if (scaled !== decoded) decoded.recycle()
            scaled
        }.getOrNull()
    }
}

/** 圆形头像；有图用位图，无图用「名字首字母 + 稳定取色」字母图（Glance 原生绘制，无需位图，复用 [AvatarColor]）。 */
@Composable
internal fun Avatar(name: String, bitmap: Bitmap?, size: Dp) {
    if (bitmap != null) {
        Image(provider = ImageProvider(bitmap), contentDescription = null, modifier = GlanceModifier.size(size).cornerRadius(size / 2f))
    } else {
        Box(
            modifier = GlanceModifier.size(size).cornerRadius(size / 2f).background(AvatarColor.color(name)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().take(1).uppercase().ifEmpty { "·" },
                style = TextStyle(color = ColorProvider(Color.White), fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            )
        }
    }
}
