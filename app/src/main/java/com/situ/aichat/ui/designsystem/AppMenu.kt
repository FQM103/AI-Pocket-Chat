package com.situ.aichat.ui.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.LocalAppHaptics

/**
 * Fable-5 浮层菜单「玻璃小笺」（M3 清零卷一·总契约 §2.3·2026-07-17 草图过审）。
 *
 * **母版 = 已过审的故事玻璃菜单（ST10-4）**，数值逐字推广到全库：包壳 M3 [DropdownMenu]，
 * [AppShapes.overlay] 20dp 圆角 + `surface.raised` 94% 垫底 + tonalElevation 0 + shadowElevation 8dp +
 * 0.75dp 发丝描边（[appMenuHairline]）+ 默认宽 216dp（阅读器档传 240）+ [offset] 透传。
 * 入场动画 = [DropdownMenu] 内建缩放淡入（母版现状），零外加。
 *
 * 已过审实现**无真实背景模糊**（94% 半透明近似玻璃·Compose DropdownMenu 也做不了 backdrop blur），
 * 推广照旧——不要「顺手」加模糊。
 */
@Composable
fun AppMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 216.dp,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        shape = AppShapes.overlay,
        containerColor = AppTheme.colors.surface.raised.copy(alpha = 0.94f),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(0.75.dp, appMenuHairline()),
        modifier = modifier.width(width),
        content = content,
    )
}

/**
 * 菜单项（母版书架样式逐字）：文字 [AppTypography.body]·`text.primary`，[leadingIcon] 槽 20dp
 * tint `accent.text`，行高 ≥48dp（触达军规）。[danger] = true 时文字与图标一并走 `status.onError`
 * （删除 / 清除 / 移除类动作）。点击先 `haptics.light()` 再回调（与 [AppButton] 同约定）。
 */
@Composable
fun AppMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val contentColor = if (danger) colors.status.onError else colors.text.primary
    val iconTint = if (danger) colors.status.onError else colors.accent.text
    DropdownMenuItem(
        text = { Text(text, style = AppTheme.typography.body, color = contentColor) },
        onClick = { haptics.light(); onClick() },
        modifier = modifier.heightIn(min = 48.dp),
        leadingIcon = leadingIcon?.let {
            { Icon(it, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp)) }
        },
        enabled = enabled,
    )
}

/** 菜单内分组分隔（0.5dp 发丝·与描边同源色·左右 11dp 内缩）。 */
@Composable
fun AppMenuDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = appMenuHairline(),
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
    )
}

/** 玻璃小笺的发丝色：深色走 `surface.stroke`，浅色走 10% 主文字色（描边与分隔条同源）。 */
@Composable
fun appMenuHairline(): Color {
    val colors = AppTheme.colors
    return if (colors.isDark) colors.surface.stroke else colors.text.primary.copy(alpha = 0.10f)
}
