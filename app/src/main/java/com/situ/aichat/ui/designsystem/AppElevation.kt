package com.situ.aichat.ui.designsystem

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fable-5 海拔 token（设计语言 v2 §1「光的逻辑」·全 App 单一顶光模型·「我」页 v2 首落地）。
 *
 * **浅色**：海拔 = 双层软影 + 0.5dp 发丝描边，光恒来自正上方——
 * rest（普通卡）y=2 blur=8 `#2E2925@6%`；raised（hero/浮层）另叠 y=4 blur=16 `@8%`。
 * **深色**：无投影（看不见）；海拔 = 亮度阶梯（v1 已有）+ raised 卡「月光沿」＝顶边 1dp 内高光 `@5%`。
 * **军规**：同屏影子只此两档，禁用 M3 默认 elevation 阴影参数。
 */
object AppElevation {
    /** rest 影：y 偏移 / 模糊半径 / 墨色不透明度。 */
    val restShadowY = 2.dp
    val restShadowBlur = 8.dp
    const val REST_SHADOW_ALPHA = 0.06f

    /** raised 影（叠加在 rest 之上）。 */
    val raisedShadowY = 4.dp
    val raisedShadowBlur = 16.dp
    const val RAISED_SHADOW_ALPHA = 0.08f

    /** 0.5dp 发丝描边（浅色档·深墨系）。 */
    val hairlineWidth = 0.5.dp
    const val HAIRLINE_ALPHA = 0.05f

    /** 深色 raised「月光沿」顶边内高光不透明度。 */
    const val MOONLINE_ALPHA = 0.05f

    /** 投影墨色（浅色档恒深墨——影子是环境光的缺席，不随主题 accent 变）。 */
    val shadowInk = Color(0xFF2E2925)
}

/**
 * 「呼吸白」纵向微渐变底（设计语言 v2 §3③）：顶 = 纯 `surface.raised`，底 = 向 `surface.base` 靠 35%
 * （≈ −1.5% 明度·肉眼几乎不辨，质感立判）。
 *
 * **仅浅色档**——深色改走纯 `surface.raised` 亮度阶梯（见 [appCardSurface]）。
 * 单源共用（CLAUDE.md §2「同一段逻辑只写一处」）：[appCardSurface] 浅色底 + `AppSheet` 弹层内衬双消费，
 * 表达式**只此一处**，禁复制。
 */
@Composable
fun breathingRaisedFill(): Brush {
    val colors = AppTheme.colors
    return Brush.verticalGradient(listOf(colors.surface.raised, lerp(colors.surface.raised, colors.surface.base, 0.35f)))
}

/**
 * 卡片承托一站式修饰符（影 + 底 + 发丝线 + 月光沿·设计语言 v2 §1/§3·「我」页 v2 首用）：
 *
 * - 浅色：双层软影（[raised] 决定档位）+「呼吸白」纵向微渐变底（顶亮 → 底向 surface.base 靠 35%·
 *   v2 §3③——肉眼几乎不辨，质感立判）+ 0.5dp 深墨发丝描边；
 * - 深色：无影平色底（亮度阶梯）+ `surface.stroke` 发丝描边；[raised] 卡另画顶边「月光沿」内高光。
 * - [background] 传非 null 可换底（如主角卡的陶土 container 渐变），null 用 raised 呼吸白默认。
 *
 * 宽高由调用方决定（本修饰符不含尺寸）；内部已画底并**收尾 clip**（圆角单源于 [cornerRadius]——ripple/
 * 内侧绘制由此裁圆角，调用侧**不要**再叠 Surface/tonalElevation/clip；R1 🔵-3③ 三处 16 双源收拢）。
 */
@Composable
fun Modifier.appCardSurface(
    raised: Boolean = false,
    cornerRadius: Dp = 16.dp,
    background: Brush? = null,
): Modifier {
    val colors = AppTheme.colors
    val isDark = colors.isDark
    val shape = RoundedCornerShape(cornerRadius)
    val fill = background ?: if (isDark) {
        SolidColor(colors.surface.raised)
    } else {
        // 呼吸白：顶 = 纯 raised，底 = 向 base 靠 35%（≈ −1.5% 明度）——表达式单源于 [breathingRaisedFill]。
        breathingRaisedFill()
    }
    // 浅色发丝走 text.primary（契约 §9.2 口径·青花等非暖主题随主题墨色）；影子仍恒 shadowInk（环境光缺席不随主题）。
    val hairline = if (isDark) colors.surface.stroke else colors.text.primary.copy(alpha = AppElevation.HAIRLINE_ALPHA)
    val moonline = colors.text.primary.copy(alpha = AppElevation.MOONLINE_ALPHA)

    return this
        .drawWithCache {
            val radius = cornerRadius.toPx()
            // 影层 Paint 在 cache 域一次构建（BlurMaskFilter 属重对象·绝不逐帧新建）。
            val shadowPaints = if (isDark) emptyList() else buildList {
                fun paintOf(alpha: Float, blur: Dp) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = AppElevation.shadowInk.copy(alpha = alpha).toArgb()
                    maskFilter = android.graphics.BlurMaskFilter(
                        blur.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                }
                add(paintOf(AppElevation.REST_SHADOW_ALPHA, AppElevation.restShadowBlur) to AppElevation.restShadowY.toPx())
                if (raised) {
                    add(paintOf(AppElevation.RAISED_SHADOW_ALPHA, AppElevation.raisedShadowBlur) to AppElevation.raisedShadowY.toPx())
                }
            }
            val moonWidth = 1.dp.toPx()
            onDrawBehind {
                shadowPaints.forEach { (paint, dy) ->
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        native.translate(0f, dy)
                        native.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
                        native.restore()
                    }
                }
                drawRoundRect(brush = fill, cornerRadius = CornerRadius(radius, radius))
                if (isDark && raised) {
                    // 月光沿：顶边内高光，两端从圆角起笔（不出弧）。
                    drawLine(
                        color = moonline,
                        start = Offset(radius, moonWidth / 2f),
                        end = Offset(size.width - radius, moonWidth / 2f),
                        strokeWidth = moonWidth,
                    )
                }
            }
        }
        .border(AppElevation.hairlineWidth, hairline, shape)
        .clip(shape)
}

/** 分段卡位置：LazyColumn 逐行 item 拼一张连续卡（钱包账本首用）。 */
enum class CardSegment { Top, Middle, Bottom }

/**
 * 分段卡承托（[appCardSurface] 同族·钱包账本首用）：LazyColumn 逐行 item 各画一段，拼成一张连续圆角卡。
 *
 * - **形状**：[CardSegment.Top] 圆上两角 / [CardSegment.Middle] 全方 / [CardSegment.Bottom] 圆下两角，收尾 clip 单源。
 * - **底**：浅深两模一律**纯色** `surface.raised`（SolidColor）——呼吸白纵向渐变无法跨段连续，J1 降级为纯色
 *   （契约自述该渐变「肉眼几乎不辨」，纯色为可接受降级·留用户装机复审）。
 * - **浅色影**：仅 rest 档（[AppElevation] 既有 token，不新拷值）；每段各画，绘制顺序由 LazyColumn 保证后段
 *   不透明底盖前段接缝下缘影。深色无影、无月光沿（rest 档）。
 * - **发丝线**：手画外轮廓开放路径（**禁 [border]**——会在段接缝画横线）；色同 [appCardSurface]。
 */
@Composable
fun Modifier.appCardSegmentSurface(
    segment: CardSegment,
    cornerRadius: Dp = 16.dp,
): Modifier {
    val colors = AppTheme.colors
    val isDark = colors.isDark
    val fillColor = colors.surface.raised
    val hairline = if (isDark) colors.surface.stroke else colors.text.primary.copy(alpha = AppElevation.HAIRLINE_ALPHA)
    val shape = when (segment) {
        CardSegment.Top -> RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)
        CardSegment.Middle -> RoundedCornerShape(0.dp)
        CardSegment.Bottom -> RoundedCornerShape(bottomStart = cornerRadius, bottomEnd = cornerRadius)
    }
    return this
        .drawWithCache {
            val r = cornerRadius.toPx()
            val topR = if (segment == CardSegment.Top) r else 0f
            val botR = if (segment == CardSegment.Bottom) r else 0f
            // rest 影 Paint 在 cache 域一次构建（BlurMaskFilter 属重对象·绝不逐帧新建·照 appCardSurface 写法）。
            val restPaint = if (isDark) {
                null
            } else {
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = AppElevation.shadowInk.copy(alpha = AppElevation.REST_SHADOW_ALPHA).toArgb()
                    maskFilter = android.graphics.BlurMaskFilter(
                        AppElevation.restShadowBlur.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                }
            }
            val restDy = AppElevation.restShadowY.toPx()
            val hairPx = AppElevation.hairlineWidth.toPx()
            // 三条几何路径同在 cache 域一次构建（尺寸/段位变才重建·R1 🔵-1）：绘制块只剩纯 draw。
            val shadowPath = if (restPaint == null) {
                null
            } else {
                android.graphics.Path().apply {
                    addRoundRect(
                        0f, 0f, size.width, size.height,
                        floatArrayOf(topR, topR, topR, topR, botR, botR, botR, botR),
                        android.graphics.Path.Direction.CW,
                    )
                }
            }
            // 纯色 raised 底（J1 降级·非呼吸白渐变）；drawWithCache 在 clip 前 → 自绘圆角轮廓。
            val fillPath = Path().apply {
                addRoundRect(
                    RoundRect(
                        rect = Rect(0f, 0f, size.width, size.height),
                        topLeft = CornerRadius(topR, topR),
                        topRight = CornerRadius(topR, topR),
                        bottomRight = CornerRadius(botR, botR),
                        bottomLeft = CornerRadius(botR, botR),
                    ),
                )
            }
            // 发丝线：只画外轮廓（禁 border·防段接缝横线）；线沿边缘内半线宽落笔，圆角用 arcTo。
            val i = hairPx / 2f
            val hairPath = Path()
            when (segment) {
                CardSegment.Top -> {
                    hairPath.moveTo(i, size.height)
                    hairPath.lineTo(i, i + topR)
                    hairPath.arcTo(Rect(i, i, i + 2 * topR, i + 2 * topR), 180f, 90f, false)
                    hairPath.lineTo(size.width - i - topR, i)
                    hairPath.arcTo(Rect(size.width - i - 2 * topR, i, size.width - i, i + 2 * topR), 270f, 90f, false)
                    hairPath.lineTo(size.width - i, size.height)
                }
                CardSegment.Middle -> {
                    hairPath.moveTo(i, 0f)
                    hairPath.lineTo(i, size.height)
                    hairPath.moveTo(size.width - i, 0f)
                    hairPath.lineTo(size.width - i, size.height)
                }
                CardSegment.Bottom -> {
                    hairPath.moveTo(i, 0f)
                    hairPath.lineTo(i, size.height - i - botR)
                    hairPath.arcTo(Rect(i, size.height - i - 2 * botR, i + 2 * botR, size.height - i), 180f, -90f, false)
                    hairPath.lineTo(size.width - i - botR, size.height - i)
                    hairPath.arcTo(Rect(size.width - i - 2 * botR, size.height - i - 2 * botR, size.width - i, size.height - i), 90f, -90f, false)
                    hairPath.lineTo(size.width - i, 0f)
                }
            }
            val hairStroke = Stroke(width = hairPx)
            onDrawBehind {
                // 段形软影（浅色·rest 档）：后段不透明底盖前段接缝下缘影（rest 影 y=+2 向下投）。
                if (restPaint != null && shadowPath != null) {
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        native.translate(0f, restDy)
                        native.drawPath(shadowPath, restPaint)
                        native.restore()
                    }
                }
                drawPath(fillPath, color = fillColor)
                drawPath(hairPath, color = hairline, style = hairStroke)
            }
        }
        .clip(shape)
}
