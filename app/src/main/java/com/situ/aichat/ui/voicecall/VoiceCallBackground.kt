package com.situ.aichat.ui.voicecall

import android.graphics.Bitmap
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * 「暖夜通话」背景（FABLE5_VOICE_CALL_REDESIGN_PROPOSAL.md D-1）：暖夜底之上是 TA 的模糊剪影——
 * 取用链 **聊天壁纸（模糊）→ 头像（重模糊放大）→ 纯暖夜**，三档同压一层暖黑 scrim（顶 62%→底 92%）
 * 保上层文字/字幕对比；最上面一枚陶土光斑缓慢漂移呼吸（沿用原三光斑的驱动机制，只留一枚、换陶土色）。
 * reduce-motion 静止在首帧位（既定惯例）。
 */
@Composable
fun VoiceCallBackground(
    wallpaper: Bitmap?,
    avatar: Bitmap?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(VoiceCallPalette.base, VoiceCallPalette.baseDeep),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 氛围层：壁纸优先，其次头像（更重的模糊+放大，剪影感而非人像感）。
        val ambient = wallpaper ?: avatar
        if (ambient != null) {
            val isAvatarFallback = wallpaper == null
            Image(
                bitmap = ambient.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(if (isAvatarFallback) 1.4f else 1.15f)
                    .blur(if (isAvatarFallback) 64.dp else 40.dp)
                    .alpha(if (isAvatarFallback) 0.40f else 0.50f),
            )
        }

        // 暖黑 scrim：顶轻底重，字幕/控制排永远坐在最暗的一段上。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to VoiceCallPalette.base.copy(alpha = 0.62f),
                        0.46f to VoiceCallPalette.base.copy(alpha = 0.78f),
                        1f to VoiceCallPalette.base.copy(alpha = 0.92f),
                    ),
                ),
        )

        // 陶土光斑：驱动机制沿用原 DriftingBlob（8s ease-in-out reverse），只留一枚、换暖色。
        DriftingBlob(
            color = VoiceCallPalette.glow.copy(alpha = 0.22f),
            diameter = 280.dp, blurRadius = 90.dp,
            fromX = (-20).dp, fromY = (-150).dp, toX = 40.dp, toY = (-200).dp,
            durationMillis = 9_000, label = "clayOrb",
        )
    }
}

@Composable
private fun DriftingBlob(
    color: Color,
    diameter: Dp,
    blurRadius: Dp,
    fromX: Dp,
    fromY: Dp,
    toX: Dp,
    toY: Dp,
    durationMillis: Int,
    label: String,
) {
    // P1-23：reduceMotion 静止在 progress 0=（fromX, fromY）首帧位。
    // 条件分支建 transition 安全：reduceMotion 在组合生命周期内恒定（remember 缓存）。
    val progress = if (rememberReduceMotion()) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = label)
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = durationMillis, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "$label-progress",
        ).value
    }
    val x = fromX + (toX - fromX) * progress
    val y = fromY + (toY - fromY) * progress
    Box(
        modifier = Modifier
            .offset(x = x, y = y)
            .size(diameter)
            .blur(blurRadius)
            .background(
                brush = Brush.radialGradient(colors = listOf(color, Color.Transparent)),
                shape = CircleShape,
            ),
    )
}
