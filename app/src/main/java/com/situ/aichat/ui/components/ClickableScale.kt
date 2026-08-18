package com.situ.aichat.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/**
 * 点按缩放反馈（Chunk 1·参照 Telegram ButtonBounce）：按下 60ms 快缩到 [pressedScale]、松手 350ms 过冲回弹，
 * 给卡片/方框/横条这类大可点元素一点「按得动 + 弹回来」的手感。ripple 仍走 [LocalIndication]（Phase 0 品牌色）。
 *
 * 用 [AppMotion.pressDownSpec]/[AppMotion.pressReleaseSpec] + [rememberReduceMotion]（系统关动画时不缩放·仅保留 ripple）。
 * 自管 [MutableInteractionSource] 并接管点击，替代裸 `Modifier.clickable { }`。可选 [onClickLabel]/[role]
 * 透传底层 clickable（给屏读正确的动作标签与角色·默认不设）。
 *
 * [onLongClick] 非空时换用 `combinedClickable`（长按菜单类卡片用），**缩放与 ripple 行为一字不变**；
 * 默认 null 时走的还是原来那条 `clickable` 分支，既有调用点逐字节零变化。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.clickableScale(
    pressedScale: Float = 0.97f,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val reduceMotion = rememberReduceMotion()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) pressedScale else 1f,
        animationSpec = if (pressed) AppMotion.pressDownSpec else AppMotion.pressReleaseSpec,
        label = "clickableScale",
    )
    val indication = LocalIndication.current
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(
            if (onLongClick == null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = indication,
                    onClickLabel = onClickLabel,
                    role = role,
                    onClick = onClick,
                )
            } else {
                Modifier.combinedClickable(
                    interactionSource = interaction,
                    indication = indication,
                    onClickLabel = onClickLabel,
                    role = role,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
            },
        )
}
