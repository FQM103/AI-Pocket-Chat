package com.situ.aichat.ui.world

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.clickableScale
import kotlin.random.Random

/**
 * 室内盒景 Compose 覆盖层（W9d 图纸 §4.6B/§4.10·interior demo:L22-53 逐值）：纸片立绘卡（pcard）+ 杯口热气 +
 * 选中环（46dp 脉冲）+ 页级雨/雪覆盖层。位置由 [InteriorSceneView] 投影后经 modifier 定位；色单源 [WorldSceneColors]。
 */

/**
 * 纸片立绘卡（demo:L22-44）：暖纸面 14dp 圆角·178° 渐变·描边 1.5dp·投影 (0,7dp,18dp)·内边距 上7/横9/下6·底部 45°
 * 尾巴·CharacterAvatar 38dp + 名字 11sp + 卡下状态行 10sp·呼吸 3.6s·入场光晕 halo 播一轮（§4.6B·[reduceMotion] 静）。
 * 锚定 = 底部中心（translate(-50%,-100%)·SceneView offset 到 p.y-高度）。[isPet] → 🐾 徽章代替头像。
 */
@Composable
internal fun InteriorPcard(
    name: String,
    avatarPath: String?,
    statusLine: String,
    isPet: Boolean,
    a11y: String,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 呼吸（3.6s·translateY -3dp + scale 1.015）。
    val breathe = if (reduceMotion) 0f else {
        val t = rememberInfiniteTransition(label = "pcardBreathe")
        val f by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = AppMotion.EaseInOut), RepeatMode.Reverse), label = "breathe")
        f
    }
    // 入场光晕（2.6s 播一轮后停·三角峰在 50%）。
    val halo = remember { Animatable(0f) }
    LaunchedEffect(name) { if (!reduceMotion) { halo.snapTo(0f); halo.animateTo(1f, tween(2600, easing = AppMotion.EaseInOut)) } }
    val haloP = if (halo.value < 0.5f) halo.value * 2f else (1f - halo.value) * 2f

    Box(
        modifier
            .clickableScale(role = Role.Button, onClick = onClick)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clearAndSetSemantics { contentDescription = a11y },
        contentAlignment = Alignment.BottomCenter, // = translate(-50%,-100%)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.TopCenter) {
                // halo（58dp 径向·锚卡面上部·仅入场一轮）。
                if (halo.value in 0.001f..0.999f) {
                    Box(
                        Modifier
                            .size(58.dp)
                            .offset(y = 6.dp)
                            .graphicsLayer { val s = 0.9f + 0.35f * haloP; scaleX = s; scaleY = s; alpha = 0.25f + 0.65f * haloP }
                            .drawBehind {
                                drawCircle(
                                    Brush.radialGradient(listOf(WorldSceneColors.halo.copy(alpha = 0.5f), Color.Transparent), radius = size.minDimension / 2f * 0.65f),
                                    radius = size.minDimension / 2f,
                                )
                            },
                    )
                }
                Box(
                    Modifier.graphicsLayer { translationY = -3.dp.toPx() * breathe; val s = 1f + 0.015f * breathe; scaleX = s; scaleY = s },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // 底部 45° 尾巴（10×10dp·bottom -7dp·继承底色与描边·demo:L34-36）。
                    Box(
                        Modifier
                            .offset(y = 7.dp)
                            .size(10.dp)
                            .rotate(45f)
                            .background(WorldSceneColors.pcardPaperBottom)
                            .border(1.5.dp, WorldSceneColors.cardStroke),
                    )
                    Column(
                        Modifier
                            .shadow(9.dp, RoundedCornerShape(14.dp), spotColor = Color(0x6B0A0E1A), ambientColor = Color(0x6B0A0E1A))
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.verticalGradient(listOf(WorldSceneColors.pcardPaperTop, WorldSceneColors.pcardPaperBottom)))
                            .border(1.5.dp, WorldSceneColors.cardStroke, RoundedCornerShape(14.dp))
                            .padding(start = 9.dp, end = 9.dp, top = 7.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (isPet) {
                            Box(Modifier.size(38.dp).clip(CircleShape).background(WorldSceneColors.petBadge), contentAlignment = Alignment.Center) {
                                Text("🐾", fontSize = 18.sp)
                            }
                        } else {
                            CharacterAvatar(name = name, avatarPath = avatarPath, size = 38.dp)
                        }
                        Text(name, color = WorldSceneColors.sheetTitle, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (statusLine.isNotEmpty()) {
                // 🟡-3：状态行浮于 3D 场景上·加文字投影（§4.6B (0,1dp,6dp) rgba(10,14,26,.9)·Compose 等价 TextStyle.shadow）。
                val statusShadow = with(LocalDensity.current) { Shadow(Color(0xE60A0E1A), Offset(0f, 1.dp.toPx()), 6.dp.toPx()) }
                Text(
                    statusLine,
                    color = WorldSceneColors.pcardStatus,
                    fontSize = 10.sp,
                    style = TextStyle(shadow = statusShadow),
                    modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics { },
                )
            }
        }
    }
}

/**
 * 杯口热气（demo:L46-49）：5dp 圆点 blur 2.5dp·3.2s 无限（位移 (0,0)→(3dp,-26dp)·α 0→0.8@30%→0）·相位按 [index]×0.7s。
 */
@Composable
internal fun InteriorSteam(index: Int, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "steam")
    val f by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(3200, easing = AppMotion.EaseInOut), RepeatMode.Restart, StartOffset((index * 700))),
        label = "rise",
    )
    val a = when { f < 0.3f -> f / 0.3f * 0.8f; else -> 0.8f * (1f - (f - 0.3f) / 0.7f) }
    Box(
        modifier
            .offset(x = 3.dp * f, y = (-26).dp * f)
            .size(5.dp)
            .blur(2.5.dp)
            .alpha(a.coerceIn(0f, 0.8f))
            .clip(CircleShape)
            .background(WorldSceneColors.steam),
    )
}

/**
 * 室内选中环（demo:L50-53）：46dp 圆环 1.5dp gold α0.9·脉冲 2.2s EaseOut（scale 0.5→1.4·α0.95→0）·[reduceMotion] 静环。
 */
@Composable
internal fun InteriorSelectedRing(reduceMotion: Boolean, modifier: Modifier = Modifier) {
    if (reduceMotion) {
        Box(modifier.size(46.dp).border(1.5.dp, WorldSceneColors.gold.copy(alpha = 0.5f), CircleShape))
        return
    }
    val t = rememberInfiniteTransition(label = "intRing")
    val frac by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = AppMotion.EaseOut), RepeatMode.Restart), label = "pulse")
    Box(
        modifier
            .size(46.dp)
            .graphicsLayer { val s = 0.5f + 0.9f * frac; scaleX = s; scaleY = s; alpha = 0.95f * (1f - frac) }
            .border(1.5.dp, WorldSceneColors.gold.copy(alpha = 0.9f), CircleShape),
    )
}

/**
 * 页级雨/雪覆盖层（§4.10·demo:L10-13/L83-87 转译）：雨 = 14 条竖线线性下落 0.9-1.7s；雪 = 18 粒圆点下落 4-7s +
 * 水平漂 ±6dp。x/时长/延迟/α 由 `Random(0x9D)`/`Random(0x9E)` 定。[reduceMotion]/static 不调用（调用方门控）。
 */
@Composable
internal fun InteriorPagePrecip(snow: Boolean, modifier: Modifier = Modifier) {
    val rnd = remember(snow) { Random(if (snow) 0x9E else 0x9D) }
    val specs = remember(snow) {
        val n = if (snow) 18 else 14
        List(n) {
            PrecipSpec(
                x = rnd.nextFloat(),
                durMs = if (snow) (4000 + rnd.nextInt(3000)) else (900 + rnd.nextInt(800)),
                delayMs = rnd.nextInt(if (snow) 4000 else 1800),
                alpha = if (snow) 1f else (0.35f + rnd.nextFloat() * 0.4f),
                drift = if (snow) (rnd.nextFloat() * 2f - 1f) else 0f,
            )
        }
    }
    val t = rememberInfiniteTransition(label = "pagePrecip")
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth; val h = maxHeight
        specs.forEach { s ->
            val f by t.animateFloat(
                0f, 1f,
                // 🟡-3：§4.10「线性下落」——雨/雪竖向下落用 LinearEasing（雪的水平漂 sin 独立·不受影响）。
                infiniteRepeatable(tween(s.durMs, easing = LinearEasing), RepeatMode.Restart, StartOffset(s.delayMs)),
                label = "drop",
            )
            if (snow) {
                Box(
                    Modifier
                        .offset(x = w * s.x + 6.dp * s.drift * kotlin.math.sin(f * 6.283f), y = h * f - h * 0.1f)
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(WorldSceneColors.onGlass.copy(alpha = 0.85f)),
                )
            } else {
                Box(
                    Modifier
                        .offset(x = w * s.x, y = h * f - h * 0.1f)
                        .size(width = 1.dp, height = h * 0.09f)
                        .alpha(s.alpha)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x47B4C8EB), Color(0x73D2E1FA)))),
                )
            }
        }
    }
}

private class PrecipSpec(val x: Float, val durMs: Int, val delayMs: Int, val alpha: Float, val drift: Float)
