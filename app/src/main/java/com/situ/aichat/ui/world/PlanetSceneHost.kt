package com.situ.aichat.ui.world

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.situ.aichat.ui.world.planet.HomeProjection
import com.situ.aichat.ui.world.planet.PlanetGLView
import com.situ.aichat.ui.world.planet.PlanetMath
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 星球场景宿主（9a WorldGlScene + W9b：initialPose 恢复姿态 / 金标进大陆 / 视图 hoist）。
 * **W9d：从 [WorldScreen] 只搬不改抽出**（9a 原逻辑字节级不变·§2）·令 WorldScreen 容纳 Interior 分支后仍达标。
 * **W15.2 隔球望乡**：标记投影换 [PlanetMath.projectHomeFull]（背面亦有坐标）+ [PlanetMath.homeMarkerVisual]
 * 三态连续渐变（取代旧 visible 硬门 + spring 淡入淡出）；背面幽灵环点击 → [onSpinHome] 转回家正面。
 */
@Composable
internal fun BoxScope.PlanetSceneHost(
    ui: WorldUiState,
    reduceMotion: Boolean,
    initialPose: Triple<Float, Float, Float>?,
    onGlError: () -> Unit,
    onFirstFrame: () -> Unit,
    onDive: () -> Unit,
    onSpinHome: () -> Unit,
    onViewReady: (PlanetGLView) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var glView by remember { mutableStateOf<PlanetGLView?>(null) }
    var sceneSize by remember { mutableStateOf(IntSize.Zero) }
    val homeUnit = remember(ui.homeX, ui.homeY) { PlanetMath.homeUnitVector(ui.homeX, ui.homeY) }
    var marker by remember { mutableStateOf<HomeProjection?>(null) }
    var camDist by remember { mutableStateOf(0f) }
    var markerSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val marginPx = with(density) { 26.dp.toPx() }
    val chevHalfPx = with(density) { 24.dp.toPx() }
    val currentDive by rememberUpdatedState(onDive)
    val currentSpinHome by rememberUpdatedState(onSpinHome)

    AndroidView(
        factory = {
            PlanetGLView(it, ui.seed, ui.seedOff, onGlError, initialPose, onDive, onFirstFrame)
                .also { v -> glView = v; onViewReady(v) }
        },
        modifier = Modifier.fillMaxSize().onSizeChanged { sceneSize = it },
        update = { it.setRenderFlags(reduceMotion, ui.staticMode) },
    )

    // W15.3 点按路由（标记/雪佛龙不再自带 clickable——Compose 叠层会在命中测试里遮蔽 GLView、
    // 吞掉「按住家标记拖球」这一最自然的抓取动作）：GLView 判定点按后按当前几何路由。
    LaunchedEffect(glView) {
        glView?.onTapListener = tap@{ x, y ->
            val proj = marker ?: return@tap
            val d = camDist
            if (d <= 0f || sceneSize.width == 0) return@tap
            val visual = PlanetMath.homeMarkerVisual(proj.facingZ, d)
            val mw = (if (markerSize.width > 0) markerSize.width else 126) / 2f
            val mh = (if (markerSize.height > 0) markerSize.height else 126) / 2f
            if (visual.front >= 0.5f && abs(x - proj.x) <= mw && abs(y - proj.y) <= mh) {
                currentDive()
                return@tap
            }
            val chev = PlanetMath.homeChevron(proj, d, sceneSize.width.toFloat(), sceneSize.height.toFloat(), marginPx)
            if (chev[3] >= 0.5f && abs(x - chev[0]) <= chevHalfPx && abs(y - chev[1]) <= chevHalfPx) {
                currentSpinHome()
            }
        }
    }

    DisposableEffect(lifecycleOwner, glView) {
        val v = glView
        if (v != null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) v.resumeWorld()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> v?.resumeWorld()
                Lifecycle.Event.ON_PAUSE -> v?.pauseWorld()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); v?.pauseWorld() }
    }

    LaunchedEffect(glView, sceneSize) {
        val v = glView ?: return@LaunchedEffect
        if (sceneSize.width == 0 || sceneSize.height == 0) return@LaunchedEffect
        val w = sceneSize.width.toFloat(); val h = sceneSize.height.toFloat()
        while (true) {
            withFrameNanos { }
            val snap = v.cameraSnapshot()
            val m = PlanetMath.sceneMatrices(snap.yaw, snap.pitch, snap.dist, w / h)
            marker = PlanetMath.projectHomeFull(m.model, m.mvp, homeUnit, w, h)
            camDist = snap.dist
        }
    }

    val p = marker
    if (p != null && camDist > 0f) {
        val visual = PlanetMath.homeMarkerVisual(p.facingZ, camDist)
        if (visual.front > 0.01f) {
            WorldHomeMarker(
                cityName = ui.homeCityName,
                reduceMotion = reduceMotion,
                visual = visual,
                onEnter = onDive,
                modifier = Modifier
                    .onSizeChanged { markerSize = it }
                    .offset {
                        IntOffset(
                            (p.x - markerSize.width / 2f).roundToInt(),
                            (p.y - markerSize.height / 2f).roundToInt(),
                        )
                    },
            )
        }
        // W15.3 边缘指路雪佛龙（家不在屏内可见 = 背面 ∪ 正面出屏·与正面标记交叉渐变·取代透视幽灵环）
        if (sceneSize.width > 0) {
            val chev = PlanetMath.homeChevron(
                p, camDist, sceneSize.width.toFloat(), sceneSize.height.toFloat(), marginPx,
            )
            if (chev[3] > 0.01f) {
                WorldHomeChevron(
                    cityName = ui.homeCityName,
                    angleRad = chev[2],
                    alpha = chev[3],
                    onSpinHome = onSpinHome,
                    modifier = Modifier.offset {
                        IntOffset((chev[0] - 24.dp.toPx()).roundToInt(), (chev[1] - 24.dp.toPx()).roundToInt())
                    },
                )
            }
        }
    }
}
