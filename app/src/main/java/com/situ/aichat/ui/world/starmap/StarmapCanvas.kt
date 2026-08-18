package com.situ.aichat.ui.world.starmap

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.world.WorldSceneColors
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

/** 星图世界半径（§4.4·相机 fit 基准）。 */
internal const val STARMAP_WORLD_R = 470f

/** 世界内容盒边长（dp·§4.3 世界空间容器·足够罩住待相识外圈 456dp + 标签·图纸 §11 变换承载器）。 */
internal const val STARMAP_WORLD_BOX_DP = 1080f

// ── 背景（§4.2·屏幕空间·不随相机缩放）──

/** 场景背景：三层星雾 radial（demo `.bg`）+ 26 颗背景星（seed 固定·闪烁 5s·reduceMotion/static → alpha .5 静） */
@Composable
internal fun StarmapBackground(seed: Long, animated: Boolean, modifier: Modifier = Modifier) {
    val stars = remember(seed) {
        val rnd = Random(seed)
        List(26) { StarSpec(rnd.nextFloat(), rnd.nextFloat(), if (rnd.nextFloat() < 0.3f) 2f else 1.3f, (rnd.nextFloat() * 5000).toInt()) }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val twinkle = if (animated) {
        val t = rememberInfiniteTransition(label = "starTwinkle")
        stars.map { s ->
            t.animateFloat(0.7f, 0.15f, infiniteRepeatable(tween(2500, easing = AppMotion.EaseInOut), RepeatMode.Reverse, StartOffset(s.delayMs)), label = "tw") // 🟡-2：半程 2500 → Reverse 全周期 5s（demo tw 5s）
        }
    } else null
    Canvas(modifier.fillMaxSize()) {
        val big = maxOf(size.width, size.height)
        drawRect(Brush.radialGradient(listOf(WorldSceneColors.gold.copy(alpha = 0.075f), Color.Transparent), center = Offset(size.width * 0.5f, size.height * 0.46f), radius = big * 0.62f))
        drawRect(Brush.radialGradient(listOf(Color(0xFF7A89B8).copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * 0.18f, size.height * 0.88f), radius = big * 0.66f))
        drawRect(Brush.radialGradient(listOf(Color(0xFFA57F8C).copy(alpha = 0.08f), Color.Transparent), center = Offset(size.width * 0.85f, size.height * 0.10f), radius = big * 0.60f))
        stars.forEachIndexed { i, s ->
            val a = twinkle?.get(i)?.value ?: 0.5f
            drawCircle(WorldSceneColors.onGlass.copy(alpha = a), radius = with(density) { s.sizeDp.dp.toPx() } / 2f, center = Offset(s.fx * size.width, s.fy * size.height))
        }
    }
}

private data class StarSpec(val fx: Float, val fy: Float, val sizeDp: Float, val delayMs: Int)

// ── 世界内容（§4.3·在相机变换层内·随缩放整体缩放）──

/** 世界内容层：刻度环 / 外圈 / 你→星丝线 / 显示边（Canvas）+ 节点 / 你 / 待相识 / 外圈标签 + 逐项 a11y（[StarLayer]）。 */
@Composable
internal fun StarmapWorld(graph: StarmapGraph, animated: Boolean, onSelect: (StarmapSelection) -> Unit, modifier: Modifier = Modifier) {
    // requiredSize：世界盒必须真为 1080dp（不被屏幕约束钳小），否则 StarLayer/Canvas 的中心(540dp)错位=世界偏移。
    Box(modifier.requiredSize(STARMAP_WORLD_BOX_DP.dp)) {
        Canvas(Modifier.fillMaxSize()) { drawStarmapGeometry(graph) }
        StarLayer(Modifier.fillMaxSize()) {
            // 外圈「待相识」标签（圈顶·§4.3）
            if (graph.pendings.isNotEmpty()) {
                Text(
                    stringResource(R.string.world_starmap_rim),
                    style = TextStyle(color = WorldSceneColors.onGlass.copy(alpha = 0.32f), fontSize = 10.sp, letterSpacing = 0.3.em),
                    modifier = Modifier.starAnchor(0f, -452f),
                )
            }
            StarYouNode(animated, Modifier.starAnchor(0f, 0f))
            graph.nodes.forEachIndexed { i, node -> StarCharNode(node, i, animated) }
            graph.pendings.forEachIndexed { i, p -> StarPendingNode(p, i, animated) }
            StarmapA11yAnchors(graph, onSelect)
        }
    }
}

/** 逐项 a11y（§4.7·画布不可导航→隐形焦点按钮·node/edge/pending 各带描述 + 激活选中·TalkBack 主路仍是列表模式）。 */
@Composable
private fun StarmapA11yAnchors(graph: StarmapGraph, onSelect: (StarmapSelection) -> Unit) {
    graph.nodes.forEach { n ->
        val youTier = stringResource(StarmapStrings.youTierResId(closenessTier(n.closeness)))
        val desc = stringResource(R.string.world_starmap_a11y_node, n.name, youTier)
        A11yAnchor(n.pos.x, n.pos.y, desc) { onSelect(StarmapSelection.Node(n.characterUuid)) }
    }
    graph.edges.forEach { e ->
        val traj = stringResource(StarmapStrings.trajectoryResId(e.trajectory))
        val desc = stringResource(R.string.world_starmap_a11y_edge, e.aName, e.bName, e.types.joinToString(" · "), traj)
        A11yAnchor((e.aPos.x + e.bPos.x) / 2f, (e.aPos.y + e.bPos.y) / 2f, desc) { onSelect(StarmapSelection.Edge(e.pairKey)) }
    }
    graph.pendings.forEach { p ->
        val desc = stringResource(R.string.world_starmap_a11y_pending, p.name, p.stagePhrase)
        A11yAnchor(p.pos.x, p.pos.y, desc) { onSelect(StarmapSelection.Pending(p.nativeId)) }
    }
}

@Composable
private fun A11yAnchor(wx: Float, wy: Float, description: String, onActivate: () -> Unit) {
    Box(
        Modifier.starAnchor(wx, wy).size(48.dp).semantics { // 🔵-1：a11y 焦点锚最小触达 48dp
            contentDescription = description
            role = Role.Button
            onClick { onActivate(); true }
        },
    )
}

/** 刻度环 / 外圈 / 丝线 / 显示边（§4.3·世界空间 dp·中心=盒中点·线宽 dp 由相机层再缩放）。 */
private fun DrawScope.drawStarmapGeometry(graph: StarmapGraph) {
    val c = (STARMAP_WORLD_BOX_DP / 2f).dp.toPx()
    fun p(wx: Float, wy: Float) = Offset(c + wx.dp.toPx(), c + wy.dp.toPx())
    val one = 1.dp.toPx()
    // 刻度环 ×3
    for (r in intArrayOf(170, 260, 350)) drawCircle(WorldSceneColors.smRing, r.dp.toPx(), Offset(c, c), style = Stroke(one))
    // 待相识外圈（dash [3,7]）
    drawCircle(WorldSceneColors.smRim, 444.dp.toPx(), Offset(c, c), style = Stroke(one, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 7.dp.toPx()))))
    // 你→每颗星的极淡丝线
    for (n in graph.nodes) drawLine(WorldSceneColors.smThread, Offset(c, c), p(n.pos.x, n.pos.y), one)
    // 显示边（§4.3·线型叠加编码）
    for (e in graph.edges) drawEdge(e, ::p)
}

private fun DrawScope.drawEdge(e: StarEdge, p: (Float, Float) -> Offset) {
    val a = p(e.aPos.x, e.aPos.y)
    val b = p(e.bPos.x, e.bPos.y)
    val d = b - a
    val len = hypot(d.x, d.y).takeIf { it > 0f } ?: return
    val u = Offset(d.x / len, d.y / len)
    val off = 22.dp.toPx()
    val s = a + u * off
    val t = b - u * off
    val avg = e.avgCloseness
    when (e.trajectory) {
        "warming" -> drawLine(WorldSceneColors.gold.copy(alpha = 0.80f), s, t, (1.5f + avg / 45f).dp.toPx(), StrokeCap.Round)
        "cooling" -> drawLine(WorldSceneColors.smCoolLine.copy(alpha = 0.85f), s, t, 1.9.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
        else -> drawLine(WorldSceneColors.onGlass.copy(alpha = 0.34f), s, t, (1.1f + avg / 70f).dp.toPx(), StrokeCap.Round)
    }
    val m = (s + t) / 2f
    if (e.tension >= 40) {
        // 结标记：9×9dp 方块转 45°（形状编码·§4.3/§18）
        rotate(45f, m) {
            val half = 4.5.dp.toPx()
            drawRect(WorldSceneColors.background, topLeft = Offset(m.x - half, m.y - half), size = androidx.compose.ui.geometry.Size(half * 2, half * 2))
            drawRect(WorldSceneColors.smKnot, topLeft = Offset(m.x - half, m.y - half), size = androidx.compose.ui.geometry.Size(half * 2, half * 2), style = Stroke(1.6.dp.toPx()))
        }
    } else {
        drawCircle(WorldSceneColors.onGlass.copy(alpha = 0.75f), 3.dp.toPx(), m)
    }
}

private fun DrawScope.rotate(deg: Float, pivot: Offset, block: DrawScope.() -> Unit) {
    withTransform({ rotate(deg, pivot) }, block)
}

// ── 节点（§4.3·全 demo 字面量）──

@Composable
private fun StarCharNode(node: StarNode, index: Int, animated: Boolean) {
    val entrance = entranceAlpha(animated, 80 + 70 * index)
    val breath = breathOffset(animated, 500 * index)
    Box(
        Modifier
            .starAnchor(node.pos.x, node.pos.y)
            .alpha(entrance)
            .graphicsLayer { translationY = -2.5.dp.toPx() * breath }
            .size(34.dp)
            .border(2.5.dp, WorldSceneColors.cardStroke, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        CharacterAvatar(node.name, node.avatarPath, 34.dp)
    }
    NodeLabel(node.name, node.pos.x, node.pos.y + 34f, alpha = 1f, entrance = entrance)
}

@Composable
private fun StarYouNode(animated: Boolean, modifier: Modifier) {
    val breath = breathOffset(animated, 0)
    Box(modifier.size(92.dp), contentAlignment = Alignment.Center) {
        // 光晕
        Box(Modifier.size(92.dp).drawBehind {
            drawCircle(Brush.radialGradient(listOf(WorldSceneColors.gold.copy(alpha = 0.35f), Color.Transparent), radius = size.minDimension / 2f))
        })
        // 脉冲环
        if (animated) {
            val t = rememberInfiniteTransition(label = "youPulse")
            val f by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = AppMotion.EaseOut), RepeatMode.Restart), label = "pulse")
            Box(
                Modifier.size(60.dp).graphicsLayer {
                    val sc = 0.72f + (1.35f - 0.72f) * f
                    scaleX = sc; scaleY = sc; alpha = 0.55f * (1f - f)
                }.border(1.2.dp, WorldSceneColors.gold.copy(alpha = 0.5f), CircleShape),
            )
        }
        // 核 + 「你」
        Box(
            Modifier.graphicsLayer { translationY = -2.5.dp.toPx() * breath }
                .size(44.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(WorldSceneColors.smGoldTop, WorldSceneColors.smGoldBottom)))
                .border(2.5.dp, WorldSceneColors.cardStroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.world_starmap_you), color = WorldSceneColors.sheetTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StarPendingNode(pending: PendingStar, index: Int, animated: Boolean) {
    val entrance = entranceAlpha(animated, 600 + 120 * index)
    val twinkle = if (animated) {
        val t = rememberInfiniteTransition(label = "pendTwinkle")
        t.animateFloat(0.9f, 0.5f, infiniteRepeatable(tween(2300, easing = AppMotion.EaseInOut), RepeatMode.Reverse), label = "dim").value // 🟡-2：半程 2300 → Reverse 全周期 4.6s（demo dimtw 4.6s）
    } else 0.7f
    Box(
        Modifier
            .starAnchor(pending.pos.x, pending.pos.y)
            .alpha(entrance)
            .size(28.dp)
            .graphicsLayer { alpha = twinkle }
            .clip(CircleShape)
            .background(WorldSceneColors.mystery.copy(alpha = 0.6f))
            .then(dashedRing()), // 虚线描边 dash [3,3]（§4.3/§18 形状编码）
        contentAlignment = Alignment.Center,
    ) {
        Text(pending.name.take(1), color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp)
    }
    NodeLabel(pending.name, pending.pos.x, pending.pos.y + 30f, alpha = 0.8f, entrance = entrance)
    // 朦胧短语第二行
    Text(
        pending.stagePhrase,
        style = TextStyle(color = WorldSceneColors.onGlass.copy(alpha = 0.55f), fontSize = 9.sp, shadow = labelShadowStyle()),
        modifier = Modifier.starAnchor(pending.pos.x, pending.pos.y + 44f).alpha(entrance),
    )
}

@Composable
private fun NodeLabel(name: String, wx: Float, wy: Float, alpha: Float, entrance: Float) {
    Text(
        name,
        style = TextStyle(color = WorldSceneColors.onGlass.copy(alpha = alpha), fontSize = 11.sp, shadow = labelShadowStyle()),
        modifier = Modifier.starAnchor(wx, wy).alpha(entrance),
    )
}

// ── 动效驱动（§4.5）──

/** 入场淡入（0→1·tween 700ms EaseOut·delay 按组·reduceMotion/static → 直接 1）。 */
@Composable
private fun entranceAlpha(animated: Boolean, delayMs: Int): Float {
    if (!animated) return 1f
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(700, delayMillis = delayMs, easing = AppMotion.EaseOut), label = "entrance")
    return a
}

/** 呼吸位移因子 0→1→0（3600ms EaseInOut·相位差 offsetMs·reduceMotion/static → 0 静）。 */
@Composable
private fun breathOffset(animated: Boolean, offsetMs: Int): Float {
    if (!animated) return 0f
    val t = rememberInfiniteTransition(label = "breath")
    return t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = AppMotion.EaseInOut), RepeatMode.Reverse, StartOffset(offsetMs)), label = "by").value
}

@Composable
private fun labelShadowStyle(): Shadow = Shadow(WorldSceneColors.background.copy(alpha = 0.9f), Offset(0f, with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }), with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() })

/** 待相识虚圈描边（dash [3,3]·§4.3）。 */
private fun dashedRing(): Modifier = Modifier.drawBehind {
    drawCircle(WorldSceneColors.smPendingStroke, size.minDimension / 2f - 0.8.dp.toPx(), style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
}

// ── 定位层（世界 dp anchor·中心=盒中点·各子居中于 anchor）──

private class StarAnchorData(val wx: Float, val wy: Float) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@StarAnchorData
}

/** 把子的**中心**钉在世界坐标 ([wx],[wy]) dp（原点=盒中点）。 */
internal fun Modifier.starAnchor(wx: Float, wy: Float): Modifier = this.then(StarAnchorData(wx, wy))

@Composable
private fun StarLayer(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val centerPx = (STARMAP_WORLD_BOX_DP / 2f).dp.toPx()
        val placed = measurables.map { it.measure(androidx.compose.ui.unit.Constraints()) to (it.parentData as StarAnchorData) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placed.forEach { (pl, a) ->
                val cx = centerPx + a.wx.dp.toPx()
                val cy = centerPx + a.wy.dp.toPx()
                pl.place((cx - pl.width / 2f).roundToInt(), (cy - pl.height / 2f).roundToInt())
            }
        }
    }
}
