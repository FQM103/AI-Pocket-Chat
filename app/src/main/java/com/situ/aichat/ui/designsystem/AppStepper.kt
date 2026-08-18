package com.situ.aichat.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * Fable-5 步进器（按钮族重构 Phase 2 chunk D·方案 B 凹槽药丸组·过审草图）。
 *
 * 替代 M3 `IconButton` ➖➕ 散件：一枚 [AppColors.surface] sunken 凹槽胶囊（[AppShapes.full]）内嵌
 * `[− 值 +]` 三区——值 [AppColors.accent] onContainer（tnum·居中）、+/− [AppColors.accent] text 陶土图标
 * （到边界禁用 → [AppColors.text] tertiary 灰）。凹槽与 [AppSegmentedControl]、软陶与 [AppChoiceChip] 同族。
 *
 * **功能逐字冻结**（用户硬约束）：组件内封装 `(value ± 1).coerceIn([range])` + 边界禁用
 * （− `enabled = value > range.first` / + `enabled = value < range.last`）——与原 `ReplyRuleSettingsScreen`
 * `StepperRow`、`CharacterWalletEditSheet` 内联钳**逐字等价**，min/max/step/钳位行为一字不变；改动仅限视觉
 * （散件 IconButton → 凹槽药丸组）与布局（值从右侧独立 → 移入加减之间·方案 B 过审）。每区点按缩放
 * （[AppMotion.calmSpring]·[rememberReduceMotion] 门控）+ [LocalAppHaptics] selection 触感。
 *
 * a11y：+/− 各 `Role.Button` + 48dp 触达 + 可选 [decreaseDescription]/[increaseDescription] 朗读标签
 * （到边界 `enabled=false`·TalkBack 读禁用态，与原 M3 `IconButton(enabled=…)` 等价）。
 */
@Composable
fun AppStepper(
    value: Int,
    valueText: String,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    decreaseDescription: String? = null,
    increaseDescription: String? = null,
) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .clip(AppShapes.full)
            .background(colors.surface.sunken),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperZone(
            icon = Icons.Filled.Remove,
            contentDescription = decreaseDescription,
            enabled = value > range.first,
            // 这一下会不会正好减到底：撞墙那记换 medium（乙 1·H4）。到底后本区即 enabled=false，故只响一次。
            hitsBoundary = (value - 1).coerceIn(range) == range.first,
            onClick = { onValueChange((value - 1).coerceIn(range)) },
        )
        Text(
            text = valueText,
            style = AppTypography.bodyEmphasis.copy(fontFeatureSettings = "tnum"),
            color = colors.accent.onContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(min = 40.dp)
                .padding(horizontal = 4.dp),
        )
        StepperZone(
            icon = Icons.Filled.Add,
            contentDescription = increaseDescription,
            enabled = value < range.last,
            hitsBoundary = (value + 1).coerceIn(range) == range.last,
            onClick = { onValueChange((value + 1).coerceIn(range)) },
        )
    }
}

/** 单个 +/− 触达区：48dp 方块（≥触达红线）·陶土/禁用灰图标·点按缩放 + 触感。 */
@Composable
private fun StepperZone(
    icon: ImageVector,
    contentDescription: String?,
    enabled: Boolean,
    hitsBoundary: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduceMotion) 0.86f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "stepperZonePress",
    )
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = { if (hitsBoundary) haptics.medium() else haptics.selection(); onClick() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) colors.accent.text else colors.text.tertiary,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale },
        )
    }
}
