package com.situ.aichat.ui.starfield

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.diagnostics.perf.FrameSceneObserver
import com.situ.aichat.diagnostics.perf.PerfScenes
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.promise.PromiseUiFormat
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 记忆星空全屏页（图纸 2026-07-16-记忆星空 §4.10）：相机（pan/zoom + 双向钳制）+ 画布 + 逐星 a11y 锚点 +
 * chrome（顶栏/图例/底标）+ 详情 sheet。相机纹路照 `ui/world/starmap/StarmapScene.kt`。
 *
 * **坐标系**：画布空间 dp（[StarfieldLayout] 出品）→ `graphicsLayer` 以 **[TransformOrigin] (0,0)**
 * 变换（`screen = canvas×scale + pan`）——图纸 J8 的钳制区间 `y ∈ [-(画布高×scale-视口高)-24dp, +24dp]`
 * 正是顶左原点的覆盖式；centroid 补偿随之改写为同原点形式（施工日志 D-4）。
 * 绘制 / 命中 / a11y 锚点三者共用同一套画布 dp 坐标（§9 机制锁「禁独立换算两套坐标」）。
 */
@Composable
fun StarfieldScreen(
    onBack: () -> Unit,
    onOpenMeetings: (String) -> Unit,
    onOpenPromises: (String) -> Unit,
    viewModel: StarfieldViewModel = hiltViewModel(),
) {
    // 性能采集·尺 3（卷 0）：本屏在被观测名单里（M18 星≥100 且月份簇>8 的判定源）。采集关时零成本。
    FrameSceneObserver(PerfScenes.MEMORY_STARFIELD)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val animate = !rememberReduceMotion()
    val density = LocalDensity.current
    val haptics = LocalAppHaptics.current
    val nowMillis = remember { System.currentTimeMillis() }
    val seed = remember(viewModel.characterUuid) { viewModel.characterUuid.hashCode() }

    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var cameraPlaced by remember { mutableStateOf(false) }

    // 退出记「已看到 now」（图纸 §3.3）——进程死亡未走到此 → 下次 nova 重现（宁多勿漏）。
    DisposableEffect(Unit) {
        onDispose { viewModel.markVisited() }
    }

    // 状态栏/导航栏浅色图标（恒暗页·PITFALLS 1d「强制深底场景必须接管系统栏图标色」·纹路照 WorldScreen:122-137）：
    // 进屏强制浅、退屏恢复原样。不接管则浅色主题下深图标压在 #0A0D1E 夜幕上 = 黑底黑字（装机实证·D-13）。
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevStatus = controller?.isAppearanceLightStatusBars
        val prevNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (controller != null) {
                if (prevStatus != null) controller.isAppearanceLightStatusBars = prevStatus
                if (prevNav != null) controller.isAppearanceLightNavigationBars = prevNav
            }
        }
    }

    LaunchedEffect(sizePx) {
        if (sizePx != IntSize.Zero) {
            with(density) { viewModel.onViewportChanged(sizePx.width.toDp().value, sizePx.height.toDp().value) }
        }
    }

    // 初始相机（J8）：y 落在最近簇心 - 0.35×视口高，x = 0。
    LaunchedEffect(sizePx, state.clusters) {
        if (!cameraPlaced && sizePx != IntSize.Zero && state.clusters.isNotEmpty()) {
            val nearest = state.clusters.first().centerYDp
            val target = with(density) { (0.35f * sizePx.height) - nearest.dp.toPx() * scale }
            pan = clampPan(Offset(0f, target), scale, sizePx, state.canvasHeightDp, density)
            cameraPlaced = true
        }
    }

    val canvasA11y = stringResource(R.string.starfield_a11y_canvas, state.starCount)

    Box(
        Modifier
            .fillMaxSize()
            // 兜底垫色（首停），正身是下方的固定天幕层（F-1）——双保险，绝不露白（E14）。
            .background(SkyStop0)
            .onSizeChanged { sizePx = it },
    ) {
        // 天幕（R1 后修 F-1·2026-07-16 真机实证「横拖隔断」）：夜幕四层（主渐变/顶部深邃/地平微光/暗角）
        // 画在**相机外的固定视口层**——星移天不移（天幕=无限远背景），pan/0.8× 缩小时画布外区域仍有
        // 完整天幕（旧结构画在世界层随相机平移，横拖露出垫色成「隔断」）；暗角=镜头效果，恒框视口
        // 而非跟世界跑偏。纯装饰无语义（Canvas 默认无 semantics）。
        Canvas(Modifier.fillMaxSize()) { drawSkyBase() }

        // 手势总层（覆盖全屏·手动命中·在 chrome 之下·纹路照 StarmapScene:93-122）。
        // **必须排在画布容器之前**：`clearAndSetSemantics` 的全屏节点会把**其后**声明的同级节点从
        // a11y 树里挤掉（装机 uiautomator 实证：逐星锚点 0 条；摘掉该 modifier 后 6 条全现身）。
        // 画布/锚点无 pointerInput，不参与命中 → 排在其上不影响手势（§4.10 两条规格由此并存·D-15）。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(state.canvasHeightDp) {
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                        val f = newScale / scale
                        // 顶左原点的 centroid 补偿：手指下的画布点保持不动。
                        val moved = pan + panChange
                        val compensated = centroid - (centroid - moved) * f
                        pan = clampPan(compensated, newScale, size, state.canvasHeightDp, density)
                        scale = newScale
                    }
                }
                .pointerInput(state.clusters) {
                    detectTapGestures { tap ->
                        val hit = hitTestStar(tap, state.clusters, scale, pan, density.density)
                        if (hit != null) haptics.light()
                        viewModel.onStarSelected(hit)
                    }
                }
                .clearAndSetSemantics { contentDescription = canvasA11y },
        )

        if (sizePx != IntSize.Zero) {
            val widthDp = with(density) { sizePx.width.toDp().value }
            Box(
                Modifier
                    .requiredSize(width = widthDp.dp, height = state.canvasHeightDp.dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = pan.x
                        translationY = pan.y
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                StarfieldSkyCanvas(
                    clusters = state.clusters,
                    selectedId = state.selected?.id,
                    seed = seed,
                    animate = animate,
                    widthDp = widthDp,
                    canvasHeightDp = state.canvasHeightDp,
                    modifier = Modifier.fillMaxSize(),
                )
                StarfieldA11yAnchors(state.clusters, viewModel::onStarSelected)
            }
        }

        // 月（视口系·静态·§4.6）：盒心 = (视口宽-44, 92)。**92 自内容区顶量起**（= mockup 坐标原点）：
        // 状态栏把顶栏推下后，若自屏顶量会与顶栏右计数叠字（装机实证·D-14）。
        StarfieldMoon(
            nowMillis = nowMillis,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .offset(x = -(MoonCenterFromRight - MoonBoxSize / 2), y = MoonCenterFromTop - MoonBoxSize / 2)
                .size(MoonBoxSize),
        )
        // 流星（视口系·单发·reduceMotion 不播·§4.5）。
        StarfieldMeteor(
            visible = state.showMeteor && animate,
            onPlayed = viewModel::onMeteorPlayed,
            modifier = Modifier.fillMaxSize(),
        )

        // 底标「始于 X 月」= 最远端（最早）簇的月份标（含往昔簇）。
        StarfieldChrome(state = state, onBack = onBack, footerStart = state.clusters.lastOrNull()?.label.orEmpty())
    }

    state.selected?.let { star ->
        StarfieldDetailSheet(
            star = star,
            characterUuid = viewModel.characterUuid,
            onDismiss = { viewModel.onStarSelected(null) },
            onOpenMeetings = onOpenMeetings,
            onOpenPromises = onOpenPromises,
        )
    }
}

/**
 * 逐星隐形焦点锚（§4.10·纹路照 `StarmapCanvas.StarmapA11yAnchors`）：与 Canvas 同 `graphicsLayer`
 * 容器内 → 坐标天然同步。48dp 最小触达；声明序 = 时间升序（簇内 stars 已升序）。
 */
@Composable
private fun StarfieldA11yAnchors(clusters: List<StarCluster>, onSelect: (StarNode) -> Unit) {
    val pattern = stringResource(R.string.starfield_date_pattern_md)
    val novaPrefix = stringResource(R.string.starfield_a11y_nova_prefix)
    val legacyDate = stringResource(R.string.starfield_legacy_date)
    val meetingUntitled = stringResource(R.string.starfield_meeting_untitled)
    clusters.forEach { cluster ->
        cluster.stars.forEach { star ->
            val node = star.node
            val date = if (node.timestampMillis <= 0L) legacyDate else PromiseUiFormat.format(node.timestampMillis, pattern)
            val typeWord = stringResource(a11yTypeResOf(node.type))
            val title = node.title.ifBlank { if (node.type == StarType.MEETING) meetingUntitled else "" }
            val desc = stringResource(R.string.starfield_a11y_star, date, typeWord, title)
            // nova 星描述前缀「新的·」（§4.10）。
            val full = if (node.nova) novaPrefix + desc else desc
            Box(
                Modifier
                    .offset(x = (star.xDp - A11Y_ANCHOR_DP / 2).dp, y = (star.yDp - A11Y_ANCHOR_DP / 2).dp)
                    .size(A11Y_ANCHOR_DP.dp)
                    .semantics {
                        contentDescription = full
                        role = Role.Button
                        onClick { onSelect(node); true }
                    },
            )
        }
    }
}

private fun a11yTypeResOf(type: StarType): Int = when (type) {
    StarType.MEETING -> R.string.starfield_a11y_type_meeting
    StarType.PROMISE -> R.string.starfield_a11y_type_promise
    StarType.MILESTONE -> R.string.starfield_a11y_type_milestone
}

/**
 * 命中（§4.10）：屏幕 px → 画布 dp，命中半径 = max(24dp, 核径×3)，取最近者；空点 → null（清选中）。
 */
internal fun hitTestStar(
    tap: Offset,
    clusters: List<StarCluster>,
    scale: Float,
    pan: Offset,
    density: Float,
): StarNode? {
    val canvasDp = (tap - pan) / (density * scale)
    var best: Pair<StarNode, Float>? = null
    clusters.forEach { cluster ->
        cluster.stars.forEach { star ->
            val distance = hypot(canvasDp.x - star.xDp, canvasDp.y - star.yDp)
            val radius = max(HIT_MIN_RADIUS_DP, star.radiusDp * 3f)
            val current = best
            if (distance <= radius && (current == null || distance < current.second)) best = star.node to distance
        }
    }
    return best?.first
}

/**
 * 相机钳制（J8·**在手势回调内做**，非绘制后裁剪·§9 机制锁）：
 * x ∈ ±0.18×视口宽×scale；y ∈ [-(画布高×scale - 视口高) - 24dp, +24dp]。
 * 画布在该缩放下短于视口时下界会翻到上界之上（`coerceIn` 会抛）→ 取两者较小值兜底，画布顶贴视口顶。
 */
internal fun clampPan(
    pan: Offset,
    scale: Float,
    viewportPx: IntSize,
    canvasHeightDp: Float,
    density: Density,
): Offset {
    val maxX = PAN_X_FACTOR * viewportPx.width * scale
    val margin = with(density) { PAN_MARGIN_DP.dp.toPx() }
    val canvasHeightPx = with(density) { canvasHeightDp.dp.toPx() }
    val minY = -(canvasHeightPx * scale - viewportPx.height) - margin
    val maxY = margin
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(min(minY, maxY), maxY))
}

internal const val MIN_SCALE = 0.8f
internal const val MAX_SCALE = 1.6f
internal const val PAN_X_FACTOR = 0.18f
internal const val PAN_MARGIN_DP = 24f
private const val HIT_MIN_RADIUS_DP = 24f
private const val A11Y_ANCHOR_DP = 48f
