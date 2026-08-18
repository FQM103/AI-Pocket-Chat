package com.situ.aichat.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * Fable-5 动作标签（按钮族重构 Phase 2 chunk C·过审草图）。
 *
 * 替代裸 M3 `AssistChip` 的「方角描边 + 中性字」骨架：暖色软填充胶囊（[AppShapes.full]·**无描边**）——
 * [AppColors.accent] container 极浅陶土填充 + onContainer 陶土字/图标（与 [AppChoiceChip] 选中态、
 * [AppBottomNav] 药丸同源「戴陶土」语言）。
 *
 * 是 [AppChoiceChip] 的**动作孪生**：语义是「点一下执行动作」（添加配图 / 加载预设 / AI 帮写…）而非
 * 「选中切换」，故用 `Role.Button` + [onClick]、**无 `selected` 态**。交互沿用 chip 族（[AppChoiceChip]/
 * [AppSegmentedControl]）：点按缩放 0.96（[AppMotion.calmSpring]·[rememberReduceMotion] 门控）+
 * [LocalAppHaptics] selection 触感（非 ripple），保持全族手感一致。
 *
 * 14sp/Medium 紧凑标签 + 可空 [leading] 图标槽（自动取 onContainer 染色）。a11y：`Role.Button` +
 * [minimumInteractiveComponentSize] 保 48dp 触达（视觉胶囊约 36dp·上下补不可见 margin）。[enabled]=false
 * 降透明且不可点。常用在动态发布 / 提示词预设 / 日记 AI 帮写等动作行。
 */
@Composable
fun AppActionChip(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduceMotion) 0.96f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "actionChipPress",
    )
    val contentColor = colors.accent.onContainer
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = { haptics.selection(); onClick() },
            )
            .minimumInteractiveComponentSize()
            .clip(AppShapes.full)
            .background(colors.accent.container)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leading != null) {
                CompositionLocalProvider(LocalContentColor provides contentColor) {
                    leading()
                }
            }
            Text(
                text = label,
                style = AppTypography.label,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
