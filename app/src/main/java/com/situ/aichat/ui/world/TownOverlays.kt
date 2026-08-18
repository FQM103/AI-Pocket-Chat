package com.situ.aichat.ui.world

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppShapes

/**
 * 小镇盒景 Compose 覆盖层（W9c 图纸 §4.4）：地点标签（暖纸签·与大陆「点+签」不同族）+ 选中金环（44dp 脉冲）+
 * 萤火（6 只·仅精修城·reduce/static 隐）。位置由 [TownSceneView] 投影后经 modifier 定位；色值单源 [WorldSceneColors]。
 */

/**
 * 地点标签（demo:L20-23 暖纸签）：胶囊 [AppShapes].full·底 labelPaper·11sp #2E2925·内边距 10/3·投影 (0,2dp,8dp)
 * rgba(20,26,44,.3)·锚 = 投影点底部中心（BottomCenter·[TownSceneView] offset 到 p.y-高度）·可点（48dp 触达·a11y）。
 */
@Composable
internal fun TownPlaceLabel(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val a11y = stringResource(R.string.world_place_marker_a11y, name)
    Box(
        modifier
            .clickableScale(role = Role.Button, onClick = onClick)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp) // 48dp 最小触达（§4.4·9b R1 🟡-1 教训）
            .clearAndSetSemantics { contentDescription = a11y },
        contentAlignment = Alignment.BottomCenter, // 底部中心锚 = translate(-50%,-100%)
    ) {
        Box(
            Modifier
                .shadow(4.dp, AppShapes.full, spotColor = Color(0x4D141A2C), ambientColor = Color(0x4D141A2C))
                .background(WorldSceneColors.labelPaper)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(name, color = WorldSceneColors.sheetTitle, fontSize = 11.sp)
        }
    }
}

/**
 * 选中金环（demo:L35-38）：44dp 圆环 1.5dp gold #E8C57E α0.9·锚 (p.x, p.top×0.5, p.z)·脉冲 2.2s EaseOut 无限
 * （scale 0.5→1.4·alpha 0.95→0）；[reduceMotion] 无脉冲（常显 α0.5 静环）。
 */
@Composable
internal fun TownSelectedRing(reduceMotion: Boolean, modifier: Modifier = Modifier) {
    if (reduceMotion) {
        Box(modifier.size(44.dp).border(1.5.dp, WorldSceneColors.gold.copy(alpha = 0.5f), CircleShape))
        return
    }
    val transition = rememberInfiniteTransition(label = "townRing")
    val frac by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = AppMotion.EaseOut), RepeatMode.Restart),
        label = "ringPulse",
    )
    Box(
        modifier
            .size(44.dp)
            .graphicsLayer {
                val s = 0.5f + 0.9f * frac // 0.5 → 1.4
                scaleX = s; scaleY = s
                alpha = 0.95f * (1f - frac) // 0.95 → 0
            }
            .border(1.5.dp, WorldSceneColors.gold.copy(alpha = 0.9f), CircleShape),
    )
}

/**
 * 萤火 6 只（demo:L32-34·DOM→Compose）：3dp 金点 + 光晕（6dp·α0.5）·屏幕百分比定位·各 6s 无限循环
 * （alpha 0.1→0.95→0.1·位移 (0,0)→(6dp,−10dp)→回·EaseInOut·相位错开 0.9s×i）。仅精修城调用·reduce/static 不调用。
 */
@Composable
internal fun TownFireflies(modifier: Modifier = Modifier) {
    val positions = listOf(
        0.18f to 0.62f, 0.30f to 0.70f, 0.62f to 0.66f, 0.78f to 0.58f, 0.46f to 0.74f, 0.70f to 0.76f,
    )
    val transition = rememberInfiniteTransition(label = "townFlies")
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth; val h = maxHeight
        positions.forEachIndexed { i, (fx, fy) ->
            val frac by transition.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(3000, easing = AppMotion.EaseInOut), RepeatMode.Reverse, StartOffset(900 * i),
                ),
                label = "fly$i",
            )
            Box(
                Modifier
                    .offset(x = w * fx + 6.dp * frac, y = h * fy - 10.dp * frac)
                    .size(6.dp)
                    .alpha(0.1f + 0.85f * frac),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.matchParentSize().drawBehind {
                        drawCircle(
                            Brush.radialGradient(
                                listOf(WorldSceneColors.gold.copy(alpha = 0.5f), Color.Transparent),
                                radius = size.minDimension / 2f,
                            ),
                            radius = size.minDimension / 2f,
                        )
                    },
                )
                Box(Modifier.size(3.dp).clip(CircleShape).background(WorldSceneColors.gold))
            }
        }
    }
}
