package com.situ.aichat.ui.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import kotlin.math.roundToInt

private val TRACK_HEIGHT = 48.dp
private val THUMB_INSET = 4.dp

/**
 * Fable-5 分段控件 = 凹槽 + 滑动陶土药丸（按钮族重构 2026-06-19·D1 柔陶软填充用户过审）。
 *
 * 替代裸 M3 `SingleChoiceSegmentedButtonRow` + `SegmentedButton` 的「硬描边 + 分隔线 + 选中滑入对勾」骨架：
 * 暖色软填充凹槽（[AppColors.surface] sunken·[AppShapes.full]·**无描边**）+ 选中段一枚陶土药丸
 * （[AppColors.accent] container·随选中索引 [AppMotion.calmSpring] 横向滑行）+ 选中标签陶土
 * （[AppColors.accent] onContainer·Medium 字重）、未选 [AppColors.text] secondary。**无对勾、无分隔线**——
 * 填充 + 字色已表达选中。与 [AppBottomNav] 滑动药丸、[AppDropdownMenuItem] 选中项同源（设计语言 §5 路子）。
 *
 * 等宽 N 段（≥2·药丸宽 = 轨宽/段数）→ 药丸位置确定，无测量竞态。每段点按缩放 0.96 + [LocalAppHaptics] selection
 * （EFFECT_TICK）；全 [rememberReduceMotion] 门控（关动画时滑动/缩放瞬时落位·色彩走效果轴永不过冲）。
 *
 * a11y：[selectableGroup] + 每段 `Role.Tab` + `selected` 语义（TalkBack 读「<label>·标签页·已选中」）；
 * 段高 48dp = 最小触达。[label] 为 `@Composable` 故调用方可用 `stringResource`。
 *
 * **测量**：用自绘 [Layout] 而非 `BoxWithConstraints` 拿轨宽——后者是 `SubcomposeLayout`，被放进
 * 任何用 `IntrinsicSize`（典型 = M3 `DropdownMenu` 内容列以 `width(IntrinsicSize.Max)` 对齐宽度）测量的
 * 容器时会抛 `IllegalStateException` 而闪退；自绘 Layout 支持内禀测量（宽度无界时回退到标签行的自然宽），
 * 故本件可安全放进下拉菜单 / 弹窗。药丸用 placement 相位定位（读 [animIndex] 只重摆位不重测量·60fps 平滑）。
 *
 * **边界**：段数过多撑不下的场景（如贴纸多分类）不适用本等宽件，调用方另裁（可滚动 tab 或保留 M3）。
 */
@Composable
fun <T> AppSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (T) -> String,
) {
    val colors = AppTheme.colors
    val reduceMotion = rememberReduceMotion()
    val haptics = LocalAppHaptics.current
    val segments = options.size.coerceAtLeast(1)
    val selectedIndex = options.indexOfFirst { it == selected }.coerceAtLeast(0)
    val animIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (reduceMotion) snap() else AppMotion.calmSpring(),
        label = "segmentSlide",
    )
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(AppShapes.full)
            .background(colors.surface.sunken),
        content = {
            // 滑动药丸（measurables[0]·先摆=绘于标签后面）。尺寸由父测量按段宽下发，不带 size 修饰符。
            Box(
                modifier = Modifier
                    .clip(AppShapes.full)
                    .background(colors.accent.container)
                    .border(width = 0.5.dp, color = colors.accent.primary.copy(alpha = 0.22f), shape = AppShapes.full),
            )
            // 标签行（measurables[1]）。
            Row(
                modifier = Modifier.selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEach { option ->
                    SegmentTab(
                        text = label(option),
                        selected = option == selected,
                        enabled = enabled,
                        reduceMotion = reduceMotion,
                        onClick = { haptics.selection(); onSelect(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val insetPx = THUMB_INSET.roundToPx()
        // 高度恒为轨高（.height(TRACK_HEIGHT)）；内禀高度查询时无界 → 回退轨高。
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else TRACK_HEIGHT.roundToPx()
        val thumbMeasurable = measurables[0]
        val rowMeasurable = measurables[1]
        // 内禀宽度查询（如 DropdownMenu 的 IntrinsicSize.Max）→ 宽度无界 → 回退到标签行自然宽，避免无穷约束。
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else rowMeasurable.maxIntrinsicWidth(height)

        val segWidth = if (segments > 0) width / segments else width
        val thumb = thumbMeasurable.measure(
            Constraints.fixed(
                width = (segWidth - insetPx * 2).coerceAtLeast(0),
                height = (height - insetPx * 2).coerceAtLeast(0),
            ),
        )
        val row = rowMeasurable.measure(Constraints.fixed(width, height))
        layout(width, height) {
            // 药丸先摆（绘于底层）→ 标签压上。药丸 x 用浮点段宽 * 动画索引求平滑滑行（place=绝对·对齐旧 offset{}）。
            val segWidthF = if (segments > 0) width.toFloat() / segments else width.toFloat()
            val thumbX = (segWidthF * animIndex).roundToInt() + insetPx
            thumb.place(x = thumbX, y = insetPx)
            row.place(x = 0, y = 0)
        }
    }
}

@Composable
private fun SegmentTab(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.accent.onContainer else colors.text.secondary,
        animationSpec = if (reduceMotion) snap() else AppMotion.effectMediumSpring(),
        label = "segmentColor",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.96f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "segmentPress",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (selected) AppTypography.bodyEmphasis else AppTypography.body,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
