package com.situ.aichat.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.LocalAppHaptics

/**
 * Fable-5 滑块（**包壳 M3 [Slider]**·2026-07-12「白瓷拇指」A 案拍板换肤，
 * 方案对比 mockup = `fable5_artifacts/mockups/slider_thumb_redesign_mockup.html`）。
 *
 * 延续设计语言 §5「行为重组件包壳 M3 调参数不重写」：完整保留 M3 [Slider] 的拖动手势 / a11y / 键盘 /
 * [steps] 吸附行为，**皮肤两槽全自绘**：
 * - **拇指 = 白瓷**：22dp 圆（浅=纯白·比呼吸白卡面亮一阶才「浮」得起来；深=暖白 `text.primary`）+
 *   双层软影微缩版（浅 y1.5/blur4 墨@28% + y3/blur8 墨@12%；深单层黑@50%）+ 0.5dp 发丝环——
 *   手影同 [AppElevation] 顶光逻辑（BlurMaskFilter 自绘，**不用** M3 elevation 影·军规）。
 * - **轨道 = 干净一条**：4dp 全圆角，active 陶土（[AppColors.accent] primary）/ inactive
 *   [AppColors.surface] sunken；**去 M3 拇指缺口、轨末端点、刻度 tick 三件残留**（那是给谷歌竖杆
 *   把手配的）。active 填充端与拇指中心的偏差恒 ≤ 拇指半径，永远被拇指盖住，无需对齐补偿。
 *
 * **行为零改**：[value]/[onValueChange]/[valueRange]/[steps]/[onValueChangeFinished] 原样透传 M3——
 * 含「松手才生效」[onValueChangeFinished]（语音灵敏度 / 底栏透明度）与 [steps] 吸附。
 * [thumbColor]/[activeColor] 可覆写（null=默认白瓷/陶土；DIYGift 礼物价格走经济金）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    thumbColor: Color? = null,
    activeColor: Color? = null,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    // 上一次的判定快照。用当前值初始化（而非哨兵）→ 首个拖动事件即与真实起点比较，不会凭空先响一记。
    val lastSnapshot = remember { mutableStateOf(sliderHapticSnapshot(value, valueRange, steps)) }
    val isDark = colors.isDark
    // 白瓷拇指面：浅=纯白（在呼吸白卡面上再亮一阶），深=暖白 token；调用方覆写优先（DIYGift 金拇指）。
    val porcelain = thumbColor ?: if (isDark) colors.text.primary else Color.White
    val thumbFill = if (enabled) porcelain else porcelain.copy(alpha = 0.4f)
    val thumbRing = if (isDark) Color.Black.copy(alpha = 0.45f) else AppElevation.shadowInk.copy(alpha = 0.10f)
    val active = (activeColor ?: colors.accent.primary).let { if (enabled) it else it.copy(alpha = 0.4f) }
    val inactive = colors.surface.sunken.let { if (enabled) it else it.copy(alpha = 0.4f) }
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f

    Slider(
        value = value,
        onValueChange = { next ->
            // 有吸附格→逐格「嗒」；滑到值域两头→「咚」一记撞墙。连续滑动中途不震（高频事件震动会糊成一片）。
            // onValueChangeFinished（松手）不震：那不是新信息。
            val snapshot = sliderHapticSnapshot(next, valueRange, steps)
            when (sliderHapticEffect(lastSnapshot.value, snapshot)) {
                SliderHapticEffect.Edge -> haptics.medium()
                SliderHapticEffect.Detent -> haptics.selection()
                SliderHapticEffect.None -> Unit
            }
            lastSnapshot.value = snapshot
            onValueChange(next)
        },
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            Box(
                Modifier
                    .size(22.dp)
                    .drawWithCache {
                        // 拇指软影 Paint 在 cache 域一次构建（BlurMaskFilter 重对象·照 AppElevation 写法）；
                        // 禁用态不投影（扁平示弱）。
                        val layers = if (!enabled) {
                            emptyList()
                        } else if (isDark) {
                            listOf(Triple(Color.Black.copy(alpha = 0.50f), 4.dp.toPx(), 1.5.dp.toPx()))
                        } else {
                            listOf(
                                Triple(AppElevation.shadowInk.copy(alpha = 0.28f), 4.dp.toPx(), 1.5.dp.toPx()),
                                Triple(AppElevation.shadowInk.copy(alpha = 0.12f), 8.dp.toPx(), 3.dp.toPx()),
                            )
                        }
                        val paints = layers.map { (tint, blur, dy) ->
                            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                color = tint.toArgb()
                                maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
                            } to dy
                        }
                        onDrawBehind {
                            paints.forEach { (paint, dy) ->
                                drawIntoCanvas { canvas ->
                                    canvas.nativeCanvas.drawCircle(size.width / 2f, size.height / 2f + dy, size.minDimension / 2f, paint)
                                }
                            }
                        }
                    }
                    .background(thumbFill, CircleShape)
                    .border(AppElevation.hairlineWidth, thumbRing, CircleShape),
            )
        },
        track = {
            // 干净一条 4dp 全圆角轨：active 从起点铺到 fraction（端差恒被拇指盖住，见 KDoc）。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(inactive),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(active),
                )
            }
        },
    )
}
