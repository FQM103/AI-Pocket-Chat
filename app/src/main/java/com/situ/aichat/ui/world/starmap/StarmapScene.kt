package com.situ.aichat.ui.world.starmap

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.world.WorldGlassChip
import com.situ.aichat.ui.world.WorldSceneColors
import kotlin.math.hypot

/**
 * 关系星图场景（W10 图纸 §4.3/§4.4/§4.5/§4.6）：背景星雾（屏幕空间）+ 相机变换层内的世界内容（[StarmapWorld]）
 * + 平移/缩放手势 + 点击命中（节点/边/待相识优先于空白·空白=收卡）+ 模式切换 chip / 提示 chip / 空态。四种底部卡
 * 与列表模式覆盖由 [StarmapSheets]/[StarmapListMode] 承接（本文件调用点在末尾）。
 */
@Composable
fun StarmapScene(
    state: StarmapUiState,
    seed: Long,
    reduceMotion: Boolean,
    staticMode: Boolean,
    onSelect: (StarmapSelection) -> Unit,
    onClearSelection: () -> Unit,
    onToggleList: () -> Unit,
    onJumpToTown: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animated = !reduceMotion && !staticMode
    val graph = state.graph
    val density = LocalDensity.current
    val haptics = LocalAppHaptics.current

    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var minScale by remember { mutableFloatStateOf(0.5f) }
    var initialized by remember { mutableStateOf(false) }

    // 初始相机（§4.4·fit 边距 56dp·WORLD_R 470）。
    LaunchedEffect(sizePx) {
        if (sizePx != IntSize.Zero && !initialized) {
            val minDp = with(density) { minOf(sizePx.width, sizePx.height).toDp().value }
            val fit = (minDp / 2f - 56f) / STARMAP_WORLD_R
            scale = maxOf(0.55f, fit)
            minScale = minOf(0.5f, fit * 0.9f)
            initialized = true
        }
    }

    Box(modifier.fillMaxSize().onSizeChanged { sizePx = it }) {
        StarmapBackground(seed, animated, Modifier.fillMaxSize())

        if (graph != null) {
            // 🟡-1：画布屏读摘要在 composable 作用域解析后捕获（clearAndSetSemantics 非 composable 作用域）。
            val canvasCd = stringResource(R.string.world_starmap_canvas_a11y, graph.nodes.size, graph.pendings.size)
            StarmapWorld(
                graph, animated, onSelect,
                Modifier.align(Alignment.Center).graphicsLayer {
                    translationX = pan.x; translationY = pan.y; scaleX = scale; scaleY = scale
                },
            )
            // 手势层（覆盖全屏·手动命中·在 chrome/卡片之下）。
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, panChange, zoomChange, _ ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            var newPan = pan + panChange
                            val newScale = (scale * zoomChange).coerceIn(minScale, 2.4f)
                            val f = newScale / scale
                            val d = center - centroid // = W/2 - mid（demo 捏合 mid 补偿等价）
                            newPan = (newPan + d) * f - d
                            pan = newPan
                            scale = newScale
                        }
                    }
                    .pointerInput(graph) {
                        detectTapGestures { tap ->
                            val sel = hitTest(tap, graph, size, scale, pan, density.density)
                            if (sel == null) {
                                onClearSelection()
                            } else {
                                haptics.light()
                                onSelect(sel)
                            }
                        }
                    }
                    .clearAndSetSemantics {
                        contentDescription = canvasCd
                    },
            )
        }

        if (graph != null && graph.isEmpty) {
            Text(
                stringResource(R.string.world_starmap_empty),
                color = WorldSceneColors.onGlass.copy(alpha = 0.8f),
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
            )
        }

        // 列表模式全屏覆盖（在 chrome 之下·§4.6）。
        StarmapListMode(graph = graph, visible = state.listMode, reduceMotion = reduceMotion)
        // 四种底部卡（图模式·选中态·§4.7）。
        StarmapSheets(card = state.selectionCard, reduceMotion = reduceMotion, onClose = onClearSelection, onJumpToTown = onJumpToTown)

        // 模式切换 chip（右上·位置同入口 chip·在列表覆盖之上）。
        StarmapModeChip(
            listMode = state.listMode,
            onToggle = onToggleList,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 16.dp, top = 16.dp),
        )
        // 底部提示 chip（仅图模式·非空·无卡时·卡开则让位给底部卡·§4.6）。
        if (!state.listMode && state.selectionCard == null && graph != null && !graph.isEmpty) {
            StarmapHintChip(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp))
        }
    }
}

/** 命中：节点/待相识/你（各自半径内最近）优先 → 边（中点 24dp）→ 空（null=收卡）。世界 dp 比较（§4.4）。 */
private fun hitTest(tap: Offset, graph: StarmapGraph, size: IntSize, scale: Float, pan: Offset, density: Float): StarmapSelection? {
    val center = Offset(size.width / 2f, size.height / 2f)
    val w = (tap - center - pan) / (density * scale) // 屏幕 px → 世界 dp
    fun d(x: Float, y: Float) = hypot(w.x - x, w.y - y)
    // 你 + 角色 + 待相识：取半径内最近。
    var best: Pair<StarmapSelection, Float>? = null
    fun consider(sel: StarmapSelection, dist: Float, radius: Float) {
        if (dist <= radius && (best == null || dist < best!!.second)) best = sel to dist
    }
    consider(StarmapSelection.You, d(0f, 0f), 28f)
    graph.nodes.forEach { consider(StarmapSelection.Node(it.characterUuid), d(it.pos.x, it.pos.y), 26f) }
    graph.pendings.forEach { consider(StarmapSelection.Pending(it.nativeId), d(it.pos.x, it.pos.y), 26f) }
    best?.let { return it.first }
    // 边：中点 24dp。
    var bestEdge: Pair<StarmapSelection, Float>? = null
    graph.edges.forEach { e ->
        val mx = (e.aPos.x + e.bPos.x) / 2f
        val my = (e.aPos.y + e.bPos.y) / 2f
        val dist = hypot(w.x - mx, w.y - my)
        val cur = bestEdge
        if (dist <= 24f && (cur == null || dist < cur.second)) bestEdge = StarmapSelection.Edge(e.pairKey) to dist
    }
    return bestEdge?.first
}

@Composable
private fun StarmapModeChip(listMode: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val label = if (listMode) "✕ " + stringResource(R.string.world_starmap_map) else "☰ " + stringResource(R.string.world_starmap_list)
    WorldGlassChip(modifier = modifier.clickableScale(role = Role.Button, onClick = onToggle)) {
        Text(
            label,
            color = WorldSceneColors.onGlass,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun StarmapHintChip(modifier: Modifier) {
    WorldGlassChip(modifier) {
        Text(
            stringResource(R.string.world_starmap_hint),
            color = WorldSceneColors.onGlass,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
