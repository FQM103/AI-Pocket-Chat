package com.situ.aichat.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.OnGlass
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.designsystem.GlassBackdrop

/**
 * Fable-5 输入区（契约 §3.3）：无边框 sunken 暖底胶囊——底层仍是 foundation BasicTextField（IME/光标/
 * 选择手柄零重写），只换视觉装饰；光标=陶土玫；占位符=text.secondary（资源化·清挂账②）。
 */
@Composable
internal fun ChatInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    wallpaperFrosted: ImageBitmap? = null,
    wallpaperDark: Boolean = false,
    /** M3b ④发送飞入：握手/飞行期抑制占位符视觉（防「说点什么…」在飞行文字下闪现）；语义节点保留。 */
    hidePlaceholder: Boolean = false,
    /** C8·P6 聚焦微提亮的 reduceMotion 门控（直切无动画）。 */
    reduceMotion: Boolean = false,
) {
    val colors = AppTheme.colors
    val glass = wallpaperFrosted != null
    // C8·P6 聚焦微提亮（输入排契约 §2·落实 REDESIGN §3.3 过审决议「聚焦反馈=明度提亮一档」）：
    // 无壁纸态胶囊底聚焦时由 sunken 向 base 方向极轻插值（≈150ms 效果轴 tween·失焦回落）；
    // 玻璃态不做（玻璃对比已足、染色动了会破五要素配比）；reduceMotion 直切。
    var focused by remember { mutableStateOf(false) }
    val focusBrighten by animateFloatAsState(
        targetValue = if (focused) FOCUS_BRIGHTEN_FRACTION else 0f,
        animationSpec = if (reduceMotion) snap() else tween(FOCUS_BRIGHTEN_MS),
        label = "inputFocusBrighten",
    )
    // 有壁纸：胶囊底改毛玻璃、打字/占位色按底部壁纸亮度自适应；无壁纸：原 sunken 暖底 + 原 token，逐像素现状。
    // 审计 T1：玻璃上内容色换 OnGlass 单源（值逐位同·真机调观感只改单源）。
    val typedColor = if (glass) { if (wallpaperDark) OnGlass.PrimaryOnDark else OnGlass.PrimaryOnLight } else colors.text.primary
    val hintColor = if (glass) { if (wallpaperDark) OnGlass.SecondaryOnDarkInput else OnGlass.SecondaryOnLightInput } else colors.text.secondary
    // 审计 Y5①：空态给编辑框挂占位文案语义——读屏聚焦不再只听到裸「编辑框」（占位 Text 是装饰盒独立节点，读屏须另划一停才够到）。
    val placeholderCd = stringResource(R.string.chat_input_placeholder)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused } // C8：与调用处 onFocusChanged 并存（各自独立回调）
            .semantics { if (value.isEmpty()) contentDescription = placeholderCd },
        textStyle = AppTypography.body.copy(color = typedColor),
        maxLines = 4,
        cursorBrush = SolidColor(colors.accent.primary),
        decorationBox = { innerTextField ->
            val inner: @Composable () -> Unit = {
                Box(
                    modifier = Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && !hidePlaceholder) {
                        Text(stringResource(R.string.chat_input_placeholder), style = AppTypography.body, color = hintColor)
                    }
                    innerTextField()
                }
            }
            if (glass) {
                GlassBackdrop(blurred = wallpaperFrosted, dark = wallpaperDark, shape = RoundedCornerShape(InputCapsuleCorner), modifier = Modifier.clip(RoundedCornerShape(InputCapsuleCorner))) { inner() }
            } else {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(InputCapsuleCorner))
                        .background(lerp(colors.surface.sunken, colors.surface.base, focusBrighten)), // C8 聚焦微提亮
                ) { inner() }
            }
        },
    )
}

/** 气泡最大宽=屏宽 74% 钳 420dp（契约 §3.2·替 300dp 定死；横屏/折叠展开自适应不顶满）。 */
@Composable
internal fun rememberBubbleMaxWidth(): Dp {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    return remember(screenWidthDp) { (screenWidthDp * 0.74f).dp.coerceAtMost(420.dp) }
}

/**
 * 有壁纸时把瞬态托盘内容（引用预览 / 语音草稿 / 录音浮层——它们自身无底色）包一层悬浮玻璃保可读；无壁纸则原样直出
 * （托盘实心底已够）。**不重构托盘结构**（录音中右侧语音键手势 owner 跨态不卸载，见调用处注释）。
 */
@Composable
internal fun MaybeTrayGlass(
    frosted: ImageBitmap?,
    dark: Boolean,
    corner: Dp = 20.dp,
    content: @Composable () -> Unit,
) {
    if (frosted != null) {
        GlassBackdrop(
            blurred = frosted,
            dark = dark,
            shape = RoundedCornerShape(corner),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(corner)),
        ) { content() }
    } else {
        content()
    }
}

/** 输入胶囊圆角（审计 T2 具名单源·22dp 现值保留；托盘玻璃默认 20dp 与此有意两档=B5 口径议题）。 */
internal val InputCapsuleCorner = 22.dp

/** C8·P6 聚焦提亮时长（毫秒·效果轴 tween ≈150ms·契约 §2 动效分解表）。 */
private const val FOCUS_BRIGHTEN_MS = 150

/** C8·P6 提亮幅度：聚焦时胶囊底自 sunken 向 base 插值的比例（极轻一档）。 */
private const val FOCUS_BRIGHTEN_FRACTION = 0.5f
