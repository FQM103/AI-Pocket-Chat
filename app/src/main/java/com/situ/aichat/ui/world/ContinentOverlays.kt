package com.situ.aichat.ui.world

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.world.continent.ContinentSite

/** 大区切换器一项（VM 由 WorldRegions.ALL 一次算出·isHome = id=="yunze"·[flavor] 供大陆 chrome 副标）。 */
data class WorldRegionChip(val id: String, val name: String, val isHome: Boolean, val flavor: String)

/**
 * 大陆盒景 Compose 覆盖层（W9b 图纸 §4.4/§4.5）：站位标记（城市金点 / 奇观青菱 + 脉冲 + 标签）+ 大区切换器
 * （收起 chip / 展开可滚列表）。位置由 [ContinentSceneView] 投影后经 modifier 定位；色值单源 [WorldSceneColors]。
 */

/** 站位标记（demo:L25-34·城=金圆点·奇观=青菱形·脉冲 2.4s·[reduceMotion] 无脉冲·48dp 触达）。 */
@Composable
internal fun ContinentSiteMarker(
    site: ContinentSite,
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when {
        site.isWonder -> stringResource(R.string.world_wonder_label, site.name)
        site.isHome -> stringResource(R.string.world_home_marker, site.name)
        else -> site.name
    }
    val a11y = stringResource(
        if (site.isWonder) R.string.world_wonder_marker_a11y else R.string.world_city_marker_a11y,
        site.name,
    )
    val tint = if (site.isWonder) WorldSceneColors.wonderTeal else WorldSceneColors.gold
    val glowAlpha = if (site.isWonder) 0.7f else 0.75f
    val labelShadow = with(LocalDensity.current) {
        Shadow(WorldSceneColors.background.copy(alpha = 0.9f), androidx.compose.ui.geometry.Offset(0f, 1.dp.toPx()), 6.dp.toPx())
    }
    Column(
        modifier
            .clickableScale(role = Role.Button, onClick = onClick)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp) // 48dp 最小触达（§4.5）·内容居中锚定使金点仍钉投影点。
            .clearAndSetSemantics { contentDescription = a11y },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!reduceMotion) PulseRing(tint = tint, wonder = site.isWonder)
            // 光晕（径向·半径 9dp）。
            Box(
                Modifier.size(18.dp).drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(tint.copy(alpha = glowAlpha), Color.Transparent),
                            radius = size.minDimension / 2f,
                        ),
                        radius = size.minDimension / 2f,
                    )
                },
            )
            // 主点：城=圆·奇观=旋 45° 圆角方。
            if (site.isWonder) {
                Box(Modifier.size(7.dp).rotate(45f).clip(RoundedCornerShape(2.dp)).background(tint))
            } else {
                Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
            }
        }
        Text(label, style = TextStyle(color = WorldSceneColors.onGlass, fontSize = 11.sp, shadow = labelShadow))
    }
}

/** 脉冲环（2.4s EaseOut 无限·scale 0.4→1.5·alpha 0.95→0·城=圆环·奇观=圆角方环·demo:L30,32-33）。 */
@Composable
private fun PulseRing(tint: Color, wonder: Boolean) {
    val transition = rememberInfiniteTransition(label = "sitePulse")
    val frac by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = AppMotion.EaseOut), RepeatMode.Restart),
        label = "pulse",
    )
    val shape = if (wonder) RoundedCornerShape(4.dp) else CircleShape
    Box(
        Modifier
            .size(24.dp)
            .graphicsLayer {
                val s = 0.4f + 1.1f * frac
                scaleX = s; scaleY = s
                alpha = 0.95f * (1f - frac)
                if (wonder) rotationZ = 45f
            }
            .border(1.5.dp, tint, shape),
    )
}

// 大区切换器（RegionSwitcher / RegionSwitcherItem / chipLabel）已于 W15 迁入 WorldChrome.WorldTopBar
// 方案 A（标题卡合并·只搬不改）。此处仅留守 WorldRegionChip 数据类与站位标记。
