package com.situ.aichat.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.OnGlass
import com.situ.aichat.util.DateFormatters
import kotlin.math.roundToInt

/**
 * ④ 发送飞入·飞行渲染层（契约 FABLE5_CHAT_TELEGRAM_MOTION_PROPOSAL.md §4·M3b·用户拍板「流畅美观」最高优先）。
 *
 * 机制照 Telegram TextMessageEnterTransition 考古：**单一 250ms 线性进度**在渲染期派生三条曲线——横轴
 * [AppMotion.TgFlightX]（双嵌套极快到位）、纵轴 [AppMotion.TgMessageY]（与列表推入同源）、淡入斜坡
 * [flightAlphaRamp]（前 40%≈100ms 完成圆角/颜色/时间戳过渡）。**移动靶**：终点=目标气泡实时窗口边界
 * （随列表位移弹簧逐帧活取·比 Telegram 同参法更抗键盘变化——契约 §4.4 拍板）。
 *
 * 较 Telegram 的两处有意升级（契约 §4.2）：真圆角 lerp（22dp 输入胶囊→16dp 气泡·graphicsLayer clip）+
 * 真颜色过渡（无壁纸=sunken 底淡出/壁纸=玻璃留在原位文字直接起飞；用户渐变淡入）。文字=**双层全程共动
 * crossfade**（源排版=输入宽·目标排版=气泡宽·前 40% 换血·两层同轨平移=Telegram 830-848 机制，单行时两层
 * 排版相同、观感即单层）。
 *
 * 流畅底线：逐帧读取（progress/targetBounds）全部压在 layout / drawBehind / graphicsLayer 相位——飞行全程
 * **零逐帧重组**。真实行同期由 ChatMessageList 抑制（alpha 0+入场动画停播），落地帧交还=像素级无缝。
 */

/** 输入胶囊内容内边距（= ChatInputField decorationBox 的 16/10）。 */
private val InputPadH = 16.dp
private val InputPadV = 10.dp

/** 气泡内容内边距（= Bubble 的 12/8）。 */
private val BubblePadH = 12.dp
private val BubblePadV = 8.dp

/** 气泡圆角（= AppShapes.bubble 16dp·此处参与 lerp 故取值常量）。 */
private val BubbleCorner = 16.dp

/** 气泡与时间戳行间距（= MessageRow Column spacedBy(4dp)）。 */
private val TimestampGap = 4.dp

@Composable
internal fun ChatSendFlightOverlay(state: ChatSendFlightState, wallpaper: ChatWallpaper?) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 起飞门：>10 行降级（Telegram TMET:128·需目标排版故在此判）。过闸即起飞；不过=静默走普通入场
    // （该行错过首帧 fadeIn 直接显示·极长文本罕见·有意接受）。
    DisposableEffect(state, textMeasurer, density) {
        state.onLaunch = { message, targetBounds, _ ->
            val contentW = with(density) { (targetBounds.width - (BubblePadH * 2).toPx()) }.roundToInt()
            if (contentW > 0) {
                val lines = textMeasurer.measure(
                    AnnotatedString(message.content),
                    style = AppTypography.body,
                    constraints = Constraints(maxWidth = contentW),
                ).lineCount
                if (lines <= FLIGHT_MAX_LINES) state.beginFlight(message, targetBounds)
            }
        }
        onDispose { state.onLaunch = null }
    }
    val flight = state.flight ?: return

    val progress = remember(flight) { Animatable(0f) }
    LaunchedEffect(flight) {
        try {
            progress.animateTo(1f, tween(FLIGHT_MS, easing = LinearEasing))
        } finally {
            state.endFlight() // 完成或中断（离屏）都交还真实行
        }
    }

    val colors = AppTheme.colors
    // 审计 P5 同法：渐变按主题 remember 单实例——与真气泡 Bubble 的 Brush 同参数=落地像素一致。
    val gradient = remember(colors) { Brush.linearGradient(listOf(colors.bubble.userStart, colors.bubble.userEnd)) }
    val sunken = colors.surface.sunken
    val hasWallpaper = wallpaper != null
    val sourceTextColor = if (wallpaper != null) {
        if (wallpaper.bottomDark) OnGlass.PrimaryOnDark else OnGlass.PrimaryOnLight
    } else {
        colors.text.primary
    }
    val targetTextColor = colors.bubble.onUser
    val timestampText = remember(flight) { DateFormatters.hourMinute(flight.timestampMs) }
    val sourceWidthPx = remember(flight) {
        with(density) { (flight.startBounds.width - (InputPadH * 2).toPx()).roundToInt().coerceAtLeast(1) }
    }
    val targetWidthPx = remember(flight) {
        with(density) { (flight.targetBounds.width - (BubblePadH * 2).toPx()).roundToInt().coerceAtLeast(1) }
    }

    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    // 逐帧读取入口（只在布局/绘制相位调用）。
    val t = { progress.value }

    Layout(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootOffset = it.positionInWindow() },
        content = {
            // child 0：飞行气泡本体。
            Box(
                Modifier
                    .graphicsLayer {
                        val xa = AppMotion.TgFlightX.transform(t())
                        shape = RoundedCornerShape(lerp(InputCapsuleCorner, BubbleCorner, xa))
                        clip = true
                    }
                    .drawBehind {
                        val a = flightAlphaRamp(t())
                        // 无壁纸：输入胶囊 sunken 底淡出（盖住其下露出的占位符区）；壁纸：玻璃胶囊留在原位，
                        // 文字「拎起」直接起飞、渐变在飞行中显形（不复制玻璃=零成本且更轻盈）。
                        if (!hasWallpaper && a < 1f) drawRect(color = sunken, alpha = 1f - a)
                        drawRect(brush = gradient, alpha = a)
                    },
            ) {
                Layout(
                    content = {
                        Text(
                            flight.text,
                            style = AppTypography.body,
                            color = sourceTextColor,
                            modifier = Modifier.graphicsLayer { alpha = 1f - flightAlphaRamp(t()) },
                        )
                        Text(
                            flight.text,
                            style = AppTypography.body,
                            color = targetTextColor,
                            modifier = Modifier.graphicsLayer { alpha = flightAlphaRamp(t()) },
                        )
                    },
                ) { measurables, constraints ->
                    val src = measurables[0].measure(Constraints(maxWidth = sourceWidthPx))
                    val dst = measurables[1].measure(Constraints(maxWidth = targetWidthPx))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val xa = AppMotion.TgFlightX.transform(t())
                        val ya = AppMotion.TgMessageY.transform(t())
                        val px = lerp(InputPadH, BubblePadH, xa).roundToPx()
                        val py = lerp(InputPadV, BubblePadV, ya).roundToPx()
                        src.place(px, py)
                        dst.place(px, py)
                    }
                }
            }
            // child 1：时间戳 HH:mm（真行 BubbleInlineTimestamp 落地即接管；送达勾发出 1s 后才显·飞行 250ms
            // 内本就无勾故无需复制回执）。
            Text(
                timestampText,
                style = AppTypography.captionNumeric,
                color = colors.text.secondary,
                modifier = Modifier.graphicsLayer { alpha = flightAlphaRamp(t()) },
            )
        },
    ) { measurables, constraints ->
        val xa = AppMotion.TgFlightX.transform(t())
        val ya = AppMotion.TgMessageY.transform(t())
        val start = flight.startBounds.translate(-rootOffset.x, -rootOffset.y)
        val target = flight.targetBounds.translate(-rootOffset.x, -rootOffset.y)
        val frame = flightFrame(start, target, xa, ya)
        val bubble = measurables[0].measure(
            Constraints.fixed(
                frame.width.roundToInt().coerceAtLeast(1),
                frame.height.roundToInt().coerceAtLeast(1),
            ),
        )
        val ts = measurables[1].measure(Constraints())
        val gap = TimestampGap.roundToPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
            bubble.place(frame.left.roundToInt(), frame.top.roundToInt())
            ts.place((frame.right - ts.width).roundToInt(), frame.bottom.roundToInt() + gap)
        }
    }
}
