package com.situ.aichat.ui.world.eggnest

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.pet.EggNestState
import com.situ.aichat.ui.components.AvatarColor
import com.situ.aichat.ui.world.WorldSceneColors
import com.situ.aichat.ui.world.continent.SiteProjection
import com.situ.aichat.ui.world.interior.InteriorAnchor
import androidx.compose.ui.res.stringResource
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Nest world anchor at yunye_home (碗池灯旁·图纸 §4.1/§9·装机 ±0.4 微调授权·终值记 §11). */
val EGG_NEST_ANCHOR = InteriorAnchor(-2.15f, 0.12f, 1.05f)

// Nest overlay literals (图纸 §4.1 精确值).
private val NestGlowHot = Color(0xFFFFD596)          // rgb(255,213,150) glow tint
private val NestGlowMidRing = Color(0x2EE8C57E)      // rgba(232,197,126,0.18) hatchable mid ring
private val NestLabelGold = Color(0xFFEAD9BE)
private val NestLabelShadow = Color(0xE60A0E1A)      // rgba(10,14,26,0.9)
private val NestTagBorder = Color(0xEBFFFFFF)        // white .92
private val NestTagInk = Color(0xFF2E2925)

// Nest geometry (dp): canvas 120×86; glow 110×70 centered on egg (egg center = 60,46 in canvas).
private const val NEST_W = 120f
private const val NEST_H = 86f
private const val ANCHOR_DROP_DP = 40f               // §4.1 屏上锚点下移 40dp 放巢底
private const val GLOW_FROZEN_ALPHA = 0.60f          // reduceMotion 定格 α 中值 (.35↔.85)

/**
 * Home egg nest 2D overlay (图纸 §4.1·决策 42⑥·仿 W9d 立绘卡族): woven nest + egg + glow + nameplate + label,
 * three states (Empty / Incubating / Hatchable). Projection fed by host ([projection]·per-frame nest anchor);
 * anchored bottom-center at the projected point dropped 40dp. GL render stack untouched (all 2D·W9d §9 lock).
 * Celebrate burst lives in C4, not here. [onClick] hoists selection to the host (site sheet).
 */
@Composable
internal fun BoxScope.EggNestOverlay(
    state: EggNestState,
    projection: () -> SiteProjection?,
    reduceMotion: Boolean,
    onClick: () -> Unit,
) {
    val hasEgg = state !is EggNestState.Empty
    val hatchable = state is EggNestState.Hatchable
    val name = when (state) {
        is EggNestState.Incubating -> state.characterName
        is EggNestState.Hatchable -> state.characterName
        EggNestState.Empty -> null
    }
    val eggColor = name?.let { AvatarColor.color(it) } ?: NestGlowHot
    val label = when (state) {
        EggNestState.Empty -> stringResource(R.string.world_nest_label_empty)
        is EggNestState.Incubating -> stringResource(R.string.world_nest_label_incubating, state.characterName)
        is EggNestState.Hatchable -> stringResource(R.string.world_nest_label_hatchable)
    }

    // Sway/rock angle (全周期 ease-in-out 正弦·W10 教训): incubating ±1.6°/5200ms·hatchable ±6°/1150ms.
    val transition = rememberInfiniteTransition(label = "eggNest")
    val swayPhase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (hatchable) 1150 else 5200, easing = LinearEasing), RepeatMode.Restart),
        label = "eggSway",
    )
    val eggAngle = if (reduceMotion || !hasEgg) 0f else (if (hatchable) 6f else 1.6f) * sin(2f * Math.PI.toFloat() * swayPhase)

    // Glow breathing α (.35↔.85): incubating 3400ms·hatchable 1900ms; reduceMotion 定格中值.
    val breathPhase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (hatchable) 1900 else 3400, easing = LinearEasing), RepeatMode.Restart),
        label = "eggGlow",
    )
    val glowAlpha = if (reduceMotion) GLOW_FROZEN_ALPHA else 0.60f + 0.25f * sin(2f * Math.PI.toFloat() * breathPhase)

    Box(
        Modifier.offset {
            val p = projection()
            if (p == null || !p.visible) return@offset IntOffset(-9999, -9999)
            // Bottom-center anchor at projected point dropped 40dp (§4.1). Nest at local (0,0)·120×86.
            IntOffset((p.x - (NEST_W / 2f).dp.toPx()).roundToInt(), (p.y + ANCHOR_DROP_DP.dp.toPx() - NEST_H.dp.toPx()).roundToInt())
        },
    ) {
        // Nest area (120×86): glow behind → nest+egg canvas → nameplate.
        Box(
            Modifier
                .size(NEST_W.dp, NEST_H.dp)
                .semantics { role = Role.Button; contentDescription = label }
                .clickable(onClick = onClick),
        ) {
            if (hasEgg) NestGlow(hatchable, glowAlpha, Modifier.offset(x = 5.dp, y = 11.dp))
            Canvas(Modifier.size(NEST_W.dp, NEST_H.dp)) { drawEggNest(withEgg = hasEgg, eggColor = eggColor, eggRotationDeg = eggAngle) }
            if (name != null) NestNameplate(name, eggColor, Modifier.offset(x = 82.dp, y = 30.dp))
        }
        // Label below the nest, centered on the nest center (x=60dp).
        Box(Modifier.offset(y = (NEST_H + 4f).dp).width(NEST_W.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                label,
                style = TextStyle(
                    color = NestLabelGold, fontSize = 10.sp,
                    shadow = Shadow(color = NestLabelShadow, offset = Offset(0f, 1.5f), blurRadius = 3f),
                ),
            )
        }
    }
}

/** Soft radial halo around the egg (§4.1): incubating single fade·hatchable inner + 55% mid ring; breathes via [alpha]. */
@Composable
private fun NestGlow(hatchable: Boolean, alpha: Float, modifier: Modifier) {
    Canvas(modifier.size(110.dp, 70.dp).graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) }) {
        val brush = if (hatchable) {
            Brush.radialGradient(
                0f to NestGlowHot.copy(alpha = 0.62f),
                0.55f to NestGlowMidRing,
                1f to Color.Transparent,
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.minDimension / 2f,
            )
        } else {
            Brush.radialGradient(
                0f to NestGlowHot.copy(alpha = 0.42f),
                1f to Color.Transparent,
                center = Offset(size.width / 2f, size.height / 2f),
                radius = size.minDimension / 2f,
            )
        }
        drawOval(brush = brush)
    }
}

/** Warm-paper mini nameplate hung to the egg's right (§4.1): 8dp 角色色圆点 + 名字 10sp w600. */
@Composable
private fun NestNameplate(name: String, dotColor: Color, modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(WorldSceneColors.pcardPaperTop, WorldSceneColors.pcardPaperBottom)))
            .border(1.5.dp, NestTagBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(name, color = NestTagInk, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── Hatch celebrate burst (§4.5·celebrate 档·每屏限一处)──
private val NestBurstGold = Color(0xFFE8C57E)
private val NestBurstAmber = Color(0xFFFFD9A0)
private val NestBurstCream = Color(0xFFF5EFEA)
private val BurstEasing = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)
private const val BURST_TOTAL_MS = 1200

private data class BurstParticle(val angle: Float, val radiusDp: Float, val durMs: Int, val color: Color)

/**
 * Hatch celebrate burst (图纸 §4.5): ① radial warm flash at the nest projection (rgba(255,213,150,.5)→transparent
 * @40%, 60ms in → 900ms out) + ② 26 particles bursting from the nest center (pool gold/amber/cream/character color,
 * 6dp, radius 60–190dp, up-drift −60dp, 700–1200ms, cubic-bezier(.2,.7,.3,1), scale 1→0.3 + fade). Caller renders
 * only when !reduceMotion and drives the 800ms→navigate itself. One-shot; clock starts on composition.
 */
@Composable
internal fun BoxScope.EggNestCelebrateBurst(
    projection: () -> SiteProjection?,
    characterColor: Color,
) {
    val particles = remember {
        val pool = listOf(NestBurstGold, NestBurstAmber, NestBurstCream, characterColor)
        List(26) {
            BurstParticle(
                angle = Random.nextFloat() * 2f * Math.PI.toFloat(),
                radiusDp = 60f + Random.nextFloat() * 130f, // 60–190dp
                durMs = 700 + Random.nextInt(501),          // 700–1200ms
                color = pool[Random.nextInt(pool.size)],
            )
        }
    }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { clock.animateTo(1f, tween(BURST_TOTAL_MS, easing = LinearEasing)) }

    Box(
        Modifier.matchParentSize().drawBehind {
            val p = projection() ?: return@drawBehind
            if (!p.visible) return@drawBehind
            val t = clock.value
            val center = Offset(p.x, p.y)
            // ① Flash: 60ms in (0.05·1200), 900ms out (0.75·1200); rgba(255,213,150,.5)→transparent @40%.
            val flashAlpha = if (t < 0.05f) t / 0.05f else (1f - (t - 0.05f) / 0.75f).coerceIn(0f, 1f)
            if (flashAlpha > 0f) {
                val fr = 200.dp.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to NestGlowHot.copy(alpha = 0.5f * flashAlpha),
                        0.4f to Color.Transparent,
                        center = center, radius = fr,
                    ),
                    center = center, radius = fr,
                )
            }
            // ② Particles.
            particles.forEach { part ->
                val pp = (t * BURST_TOTAL_MS / part.durMs).coerceIn(0f, 1f)
                val e = BurstEasing.transform(pp)
                val r = part.radiusDp.dp.toPx() * e
                val alpha = 1f - pp
                if (alpha <= 0f) return@forEach
                val px = center.x + cos(part.angle) * r
                val py = center.y + sin(part.angle) * r - 60.dp.toPx() * e // up-drift −60dp
                drawCircle(color = part.color.copy(alpha = alpha), radius = 3.dp.toPx() * (1f - 0.7f * pp), center = Offset(px, py))
            }
        },
    )
}
