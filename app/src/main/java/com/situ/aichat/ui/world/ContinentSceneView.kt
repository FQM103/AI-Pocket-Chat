package com.situ.aichat.ui.world

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.world.continent.ContinentCamSnapshot
import com.situ.aichat.ui.world.continent.ContinentGLView
import com.situ.aichat.ui.world.continent.ContinentGeometry
import com.situ.aichat.ui.world.continent.ContinentMath
import com.situ.aichat.ui.world.continent.ContinentSceneData
import com.situ.aichat.ui.world.continent.ContinentSite
import com.situ.aichat.ui.world.continent.SiteProjection
import com.situ.aichat.ui.world.continent.SkyParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 大陆场景宿主（W9b 图纸 §2/§3.7/§4.5）：AndroidView([ContinentGLView]) + 生命周期 + 区数据装载（Default 建
 * 几何）+ 天空 1s 过渡 + 投影循环（站位屏幕坐标）+ tap pick（52dp 就近）+ 选中态 hoist（站点卡）+ 大区切换器
 * + 提示 / up-hint chip。[onViewReady] 把 GL 视图交给 [WorldScreen] 驱动回星球转场。
 */
@Composable
internal fun BoxScope.ContinentSceneView(
    regionId: String,
    worldSeed: Long,
    reduceMotion: Boolean,
    staticMode: Boolean,
    interactive: Boolean, // 转场输入锁：非 None 转场期间 Compose 标记不响应（GL 触摸路另有 setInputLocked·E11 精神）。
    continentOf: suspend (String) -> ContinentSceneData,
    onGlError: () -> Unit,
    onFirstFrame: () -> Unit,
    onReturnToPlanet: () -> Unit,
    onEnterTown: (String) -> Unit = {},     // W9c：进小镇（按钮路径 A / 手势路径 B·§3.4·chunk5 WorldScreen 接实）
    initialRestore: Pair<ContinentCamSnapshot, Float>? = null, // W9c：回大陆恢复出发前姿态（快照+tDist）
    // W9d：旅行接线（城市卡「出发去这里/回家」·§4.7·presence/traveling/home 由 WorldScreen 传）。
    userPresenceCityId: String? = null,
    userTraveling: Boolean = false,
    userHomeCityId: String? = null,
    onDepartToCity: (String) -> Unit = {},
    onViewReady: (ContinentGLView) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    var glView by remember { mutableStateOf<ContinentGLView?>(null) }
    var sceneSize by remember { mutableStateOf(IntSize.Zero) }
    var sites by remember { mutableStateOf<List<ContinentSite>>(emptyList()) }
    var selected by remember { mutableStateOf<ContinentSite?>(null) }
    var upHint by remember { mutableStateOf(false) }
    val projections = remember { mutableStateOf<List<SiteProjection>>(emptyList()) }
    val markerSizes = remember { mutableStateMapOf<String, IntSize>() }
    var loadedOnce by remember { mutableStateOf(false) }
    val pickRadiusPx = with(density) { 52.dp.toPx() }
    // 屏外剔除边距（图纸 §4.5 = dp·高密度屏按 px 会缩水 2-3 倍·§4.7 无关）。
    val cullMarginXPx = with(density) { 60.dp.toPx() }
    val cullMarginYPx = with(density) { 40.dp.toPx() }

    fun pick(px: Float, py: Float) {
        if (!interactive) return // 转场期间 GL 触摸路已锁·此处兜住 onTap 竞态（E11 精神）。
        val v = glView ?: return
        val w = sceneSize.width.toFloat(); val h = sceneSize.height.toFloat()
        if (w == 0f || h == 0f) return
        val snap = v.cameraSnapshot()
        val mvp = ContinentMath.continentMvp(snap.yaw, snap.pitch, snap.dist, snap.tx, snap.ty, snap.tz, w / h)
        var best: ContinentSite? = null
        var bestD = pickRadiusPx
        for (s in sites) {
            val p = ContinentMath.projectSite(mvp, s.x, s.markerTop, s.z, w, h)
            if (!p.visible) continue
            val d = hypot(p.x - px, p.y - py)
            if (d < bestD) { bestD = d; best = s }
        }
        val hit = best
        if (hit != null) {
            selected = hit
            v.focusSite(hit.x, hit.markerTop - markerLift(hit), hit.z) // focus 到 pad 高（demo:L334）
        } else {
            selected = null
            v.clearSiteFocus()
        }
    }

    AndroidView(
        factory = { ctx ->
            ContinentGLView(
                context = ctx,
                worldSeed = worldSeed,
                reduceMotion = reduceMotion,
                onGlError = onGlError,
                onFirstFrame = onFirstFrame,
                onTap = { x, y -> pick(x, y) },
                onReturnGesture = onReturnToPlanet,
                // W9c 路径 B：overpinch-in 进小镇——仅选中「城市」（非奇观）时转发；未选中/选中奇观忽略（GL 侧复位）。
                onTownDiveGesture = { selected?.takeIf { !it.isWonder }?.let { onEnterTown(it.id) } },
                initialRestore = initialRestore,
            ).also { glView = it; onViewReady(it) }
        },
        modifier = Modifier.fillMaxSize().onSizeChanged { sceneSize = it },
        update = { it.setRenderFlags(reduceMotion, staticMode) },
    )

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

    // 区数据装载 + 天空 1s 过渡（§3.7·最新请求胜出=LaunchedEffect(key) 天然取消在飞装载·E13）。
    LaunchedEffect(glView, regionId) {
        val v = glView ?: return@LaunchedEffect
        val data = continentOf(regionId)
        val geom = withContext(Dispatchers.Default) { ContinentGeometry.buildRegion(data.style, data.sites) }
        val first = !loadedOnce
        v.submitRegion(geom, SkyParams.of(data.style), first)
        sites = data.sites
        selected = null
        if (first) {
            v.setSkyBlend(1f); loadedOnce = true
        } else {
            v.resetForRegion()
            val anim = Animatable(0f)
            anim.animateTo(1f, tween(1000, easing = AppMotion.EaseInOut)) { v.setSkyBlend(value) }
        }
    }

    // 投影循环（每帧读快照复算 mvp·offset 在布局阶段读 → 不触发重组）。
    LaunchedEffect(glView, sceneSize) {
        val v = glView ?: return@LaunchedEffect
        if (sceneSize.width == 0 || sceneSize.height == 0) return@LaunchedEffect
        val w = sceneSize.width.toFloat(); val h = sceneSize.height.toFloat()
        while (true) {
            withFrameNanos { }
            val snap = v.cameraSnapshot()
            val mvp = ContinentMath.continentMvp(snap.yaw, snap.pitch, snap.dist, snap.tx, snap.ty, snap.tz, w / h)
            projections.value = sites.map { s -> ContinentMath.projectSite(mvp, s.x, s.markerTop + 0.6f, s.z, w, h) }
            upHint = v.wantsUpHint()
        }
    }

    // 站位标记（投影经 offset/alpha 在布局/绘制阶段读·区变才重组）。
    sites.forEachIndexed { i, site ->
        ContinentSiteMarker(
            site = site,
            reduceMotion = reduceMotion,
            onClick = { if (interactive) onMarkerTap(site, glView) { selected = it } },
            modifier = Modifier
                .onSizeChanged { markerSizes[site.id] = it }
                .offset {
                    val p = projections.value.getOrNull(i)
                    val size = markerSizes[site.id] ?: IntSize.Zero
                    if (p == null) IntOffset(-9999, -9999)
                    else IntOffset((p.x - size.width / 2f).roundToInt(), (p.y - size.height / 2f).roundToInt())
                }
                .alpha(markerAlpha(projections.value.getOrNull(i), sceneSize, cullMarginXPx, cullMarginYPx)),
        )
    }

    // 大区切换器（W15 迁入顶栏方案 A·由 WorldScreen 经 WorldTopBar switcher 参数驱动·此处不再渲染）。

    // 提示 chip（底部中央·大陆文案）·开站点卡时隐（否则从暖纸面下透出·🔵-1）。
    if (selected == null) {
        WorldGlassChip(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp),
        ) {
            Text(
                stringResource(R.string.world_region_hint),
                color = WorldSceneColors.onGlass,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    // up-hint chip（顶部中央·dist>50 && intro 完成）。
    val upAlpha by animateFloatAsState(if (upHint) 1f else 0f, tween(300, easing = AppMotion.EaseInOut), label = "upHint")
    if (upAlpha > 0f) {
        WorldGlassChip(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp).alpha(upAlpha),
        ) {
            Text(
                stringResource(R.string.world_zoom_out_hint),
                color = WorldSceneColors.onGlass,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    // 站点卡（城市：走进小镇 +（异地未在途）出发去这里/回家·奇观/无按钮·§4.6/§4.7）。
    val enterTownLabel = stringResource(R.string.world_enter_town)
    val travelHereLabel = stringResource(R.string.world_travel_here)
    val travelHomeLabel = stringResource(R.string.world_travel_home)
    val sheetActions: List<Pair<String, () -> Unit>> = selected?.takeIf { !it.isWonder }?.let { site ->
        WorldSheetActionMatrix.cityButtons(userPresenceCityId, site.id, userTraveling, userHomeCityId).map { btn ->
            when (btn) {
                WorldSheetActionMatrix.CityBtn.ENTER_TOWN -> enterTownLabel to { onEnterTown(site.id) }
                // 🟡-1：开旅行单前先关城市卡（单一底部卡·否则旅行单打开时城市卡在底下透出）。
                WorldSheetActionMatrix.CityBtn.TRAVEL_HERE -> travelHereLabel to { selected = null; glView?.closeSheet(); onDepartToCity(site.id) }
                WorldSheetActionMatrix.CityBtn.TRAVEL_HOME -> travelHomeLabel to { selected = null; glView?.closeSheet(); onDepartToCity(site.id) }
            }
        }
    } ?: emptyList()
    WorldSiteSheet(
        site = selected,
        reduceMotion = reduceMotion,
        onClose = { selected = null; glView?.closeSheet() },
        modifier = Modifier.align(Alignment.BottomCenter),
        actions = sheetActions,
    )
}

/** 标记本体点击 = 与 tap 同一选中入口（focus 到该站点）。 */
private fun onMarkerTap(site: ContinentSite, view: ContinentGLView?, select: (ContinentSite) -> Unit) {
    select(site)
    view?.focusSite(site.x, site.markerTop - markerLift(site), site.z)
}

/** pad 顶 y（= markerTop − 抬高·城 1.6 / 奇观 6.2）→ focus target 用 pad 高（demo:L334 `cur.padH`）。 */
private fun markerLift(site: ContinentSite): Float = if (site.isWonder) 6.2f else 1.6f

private fun markerAlpha(p: SiteProjection?, size: IntSize, marginXPx: Float, marginYPx: Float): Float {
    if (p == null || !p.visible || size.width == 0) return 0f
    // 屏外剔除 [−60, w+60]×[−40, h+40] dp 域外（demo:L380·边距按 dp→px 换算·图纸 §4.5）。
    val w = size.width.toFloat(); val h = size.height.toFloat()
    return if (p.x > -marginXPx && p.x < w + marginXPx && p.y > -marginYPx && p.y < h + marginYPx) 1f else 0f
}
