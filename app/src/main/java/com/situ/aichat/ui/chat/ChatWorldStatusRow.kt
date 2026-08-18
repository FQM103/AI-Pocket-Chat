package com.situ.aichat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.GlassBackdrop
import com.situ.aichat.ui.designsystem.OnGlass
import com.situ.aichat.ui.world.WorldSceneColors

/**
 * 聊天状态行胶囊（W13 图纸 §4.6）：顶栏下小胶囊「TA 在世界的位置」，点击跳世界地图落点。双模式外观照 ChatFloatingDate
 * 日期胶囊配方逐值（无壁纸=surface.raised 0.92+发丝边·有壁纸=毛玻璃）。[pill] 为 null（未加入/世界未建/离线态）→
 * AnimatedVisibility 收起；退出动画期间用最后一次非空 pill 渲染。
 */
@Composable
fun ChatWorldStatusRow(
    pill: ChatWorldPill?,
    hasWallpaper: Boolean,
    wallpaperFrosted: ImageBitmap?,
    wallpaperDark: Boolean,
    onOpenWorldAt: (String) -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    var lastPill by remember { mutableStateOf(pill) }
    if (pill != null) lastPill = pill
    AnimatedVisibility(
        visible = pill != null,
        enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(AppMotion.SMOOTH_MS)) + expandVertically(),
        exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(AppMotion.SMOOTH_MS)) + shrinkVertically(),
    ) {
        lastPill?.let { p -> PillContent(p, hasWallpaper, wallpaperFrosted, wallpaperDark, onOpenWorldAt) }
    }
}

@Composable
private fun PillContent(
    pill: ChatWorldPill,
    hasWallpaper: Boolean,
    wallpaperFrosted: ImageBitmap?,
    wallpaperDark: Boolean,
    onOpenWorldAt: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val a11y = stringResource(R.string.world_chat_pill_a11y)
    Box(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp)) {
        // 触达 48dp（透明点击区包住 ~28dp 视觉胶囊·图纸 §4.6）；按下 0.96 缩放。
        Box(
            modifier = Modifier
                .clickableScale(pressedScale = 0.96f, onClick = { onOpenWorldAt(pill.focusSpec) })
                .sizeIn(minHeight = 48.dp)
                .semantics { contentDescription = a11y },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (hasWallpaper) {
                GlassBackdrop(
                    blurred = wallpaperFrosted,
                    dark = wallpaperDark,
                    shape = AppShapes.full,
                    modifier = Modifier.clip(AppShapes.full),
                ) {
                    PillRow(pill, if (wallpaperDark) OnGlass.SecondaryOnDarkTopBar else OnGlass.SecondaryOnLightTopBar)
                }
            } else {
                Box(
                    Modifier
                        .clip(AppShapes.full)
                        .background(colors.surface.raised.copy(alpha = 0.92f))
                        .border(0.75.dp, colors.surface.stroke, AppShapes.full),
                ) {
                    PillRow(pill, colors.text.secondary)
                }
            }
        }
    }
}

/** 胶囊内容行：5dp 金点 → emoji → 状态文字 → ›（图纸 §4.6）。 */
@Composable
private fun PillRow(pill: ChatWorldPill, textColor: Color) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(WorldSceneColors.gold))
        Text(pill.emoji, fontSize = 12.5.sp)
        Text(pill.text, style = AppTypography.caption, color = textColor)
        Text("›", fontSize = 11.sp, color = textColor.copy(alpha = 0.6f))
    }
}
