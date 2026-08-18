package com.situ.aichat.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
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
 * Fable-5 选择标签（按钮族重构 2026-06-19·D1 柔陶软填充用户过审）。
 *
 * 替代裸 M3 `FilterChip` 的「方角描边 + 选中对勾前导图标」骨架：暖色软填充胶囊（[AppShapes.full]·**无描边、
 * 无对勾**）——未选 [AppColors.surface] sunken + [AppColors.text] secondary；选中 [AppColors.accent] container
 * 极浅陶土 + [AppColors.accent] onContainer 陶土字（与 [AppSegmentedControl] 选中段、[AppBottomNav] 药丸、
 * [AppDropdownMenuItem] 选中项同源·全 App「戴陶土 = 选中」语言）。填充 + 字色表达选中，不再靠对勾。
 *
 * 14sp/Medium 紧凑标签（chip 偏小·与 16sp 分段控件区分）；选中/未选同字号字重 → 无重排，纯靠底色+字色切换
 * （[AppMotion.effectMediumSpring] 效果轴永不过冲·[rememberReduceMotion] 时瞬时）。点按缩放 0.96 + 触感
 * [LocalAppHaptics] selection。[leading] 可空槽给带分类图标的 chip（如礼物分类·图标自动取 [contentColor] 染色）。
 *
 * a11y：[selectable] + `selected` 语义 + [role]（默认 [Role.RadioButton] 单选组——分类/格式/题材等互斥取一；
 * 可开可关的独立开关传 [Role.Checkbox]·TalkBack 读「单选/复选按钮，已选」与被替的 M3 `FilterChip` 等价）；
 * [minimumInteractiveComponentSize] 保 48dp 触达
 * （视觉胶囊约 36dp·触达区上下各补不可见 margin·与 M3 FilterChip 等价）。多用在 `horizontalScroll`/`LazyRow`/
 * `FlowRow` 选择行（关系标签 / 礼物分类 / 故事题材…单选组沿用调用方逻辑）。
 */
@Composable
fun AppChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val containerColor by animateColorAsState(
        targetValue = if (selected) colors.accent.container else colors.surface.sunken,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "chipContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.accent.onContainer else colors.text.secondary,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "chipContent",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.96f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "chipPress",
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .alpha(if (enabled) 1f else 0.45f)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = role,
                interactionSource = interaction,
                indication = null,
                onClick = { haptics.selection(); onClick() },
            )
            .minimumInteractiveComponentSize()
            .clip(AppShapes.full)
            .background(containerColor)
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
