package com.situ.aichat.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * Fable-5 顶栏动作按钮 = 一枚**柔陶软填充圆钮**（用户过审 2026-06-19·聊天/联系人「新建」从右下角 FAB
 * 迁到顶栏右上角，避开新的悬浮胶囊底栏遮挡）。
 *
 * 质感与底栏选中药丸 / 搜索框暖填充同族：极浅陶土底（[AppColors.accent] container）+ 陶土加号
 * （[AppColors.accent] text·on 浅底 ≥4.5:1）+ 0.5dp 陶土发丝边（[AppColors.accent] primary 22%·同
 * [AppSegmentedControl] 选中药丸边）。靠明度分层浮起，不用投影（设计语言 §0）。
 *
 * 手感：按下整钮 [AppMotion.calmSpring] 轻缩到 0.92（[rememberReduceMotion] 门控·关动画时不缩）+ ripple
 * 走 [LocalIndication]（Phase 0 品牌色），与 [AppBottomNav] 按压回弹同源；ripple 在 [graphicsLayer] 之内、
 * [clip] 圆形之内绘制，故被裁成圆。
 *
 * a11y：[Role.Button] + [contentDescription] 作可读名（图标自身 `null` 装饰）；[minimumInteractiveComponentSize]
 * 把触达扩到 48dp（视觉 40dp）。
 */
@Composable
fun AppTopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.92f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "topBarActionPress",
    )
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(40.dp)
            .clip(AppShapes.full)
            .background(colors.accent.container)
            .border(0.5.dp, colors.accent.primary.copy(alpha = 0.22f), AppShapes.full)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = { haptics.light(); onClick() },
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent.text,
            modifier = Modifier.size(22.dp),
        )
    }
}
