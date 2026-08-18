package com.situ.aichat.ui.world

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.util.lerp
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.world.continent.ContinentCamSnapshot
import com.situ.aichat.ui.world.continent.ContinentGLView
import com.situ.aichat.ui.world.interior.InteriorGLView
import com.situ.aichat.ui.world.planet.PlanetGLView
import com.situ.aichat.ui.world.planet.PlanetMath
import com.situ.aichat.ui.world.town.TownGLView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 世界屏转场瞬时态（Compose 状态·不进 VM·进程死亡恒回 Planet 静止态·§3.1·E15）。四段：星↔陆二 + 陆↔镇二 + W10 星图二（纯淡入淡出·无 GL 俯冲）。 */
internal enum class WorldTransition { None, ToContinent, ToPlanet, ToTown, ToContinentFromTown, ToInterior, ToTownFromInterior, ToStarmap, FromStarmap }

/**
 * 世界屏转场编排器（W9c 图纸 §3.5·从 WorldScreen 抽出并扩展）：四段俯冲转场（星↔陆已有二 + 陆↔镇新二）+
 * 幕（[curtain]·#0D1220）/ 输入锁 / GL 失败复位单源。时序全部 §9 锁死（460/220/240·420/200/240·520/280·460/220·
 * EaseInOut）。**GL 失败恒走 [onSceneGlError]**（9b R1 🔴-1 机制·复位 phase + 收幕 + 让返回键旁路可退）。
 *
 * 线程：所有动画在 Compose [scope] 上跑；view 引用由 WorldScreen 经 onViewReady 注入。
 */
internal class WorldTransitions(private val scope: CoroutineScope, private val vm: WorldViewModel) {

    var phase by mutableStateOf(WorldTransition.None)
        private set
    var glFailed by mutableStateOf(false)
        private set

    /** 转场幕（全屏 [WorldSceneColors.background] alpha·WorldScreen 读 [Animatable.value] 绘制）。 */
    val curtain = Animatable(0f)

    var planetView: PlanetGLView? = null
    var continentView: ContinentGLView? = null
    var townView: TownGLView? = null
    var interiorView: InteriorGLView? = null

    /** W15.2 回家镜头进行中（非场景切换·不占 [phase]·防重入与相互挤占）。 */
    private var spinningHome = false

    /** 回星球揭幕等待闸（卷 B）：换场前新建·[onPlanetFirstFrame] 或 [onSceneGlError] 放行·**不设超时**（GL 活着必出帧）。 */
    private var planetFirstFrame: CompletableDeferred<Unit>? = null

    /** GL 失败统一兜底（含转场途中失败）：复位 phase + 收幕，避免返回键被 phase 早退卡死（E11·9b R1 🔴-1）。 */
    fun onSceneGlError() {
        glFailed = true
        phase = WorldTransition.None
        planetFirstFrame?.complete(Unit) // 放行挂起的揭幕协程（幕已 snapTo(0)·重复揭幕无害·不挂死）
        scope.launch { curtain.snapTo(0f) }
    }

    /** 星球首帧信号（PlanetRenderer 首帧 → GLView post 回主线程）：**只放行等待方**，不揭幕不动 phase。 */
    fun onPlanetFirstFrame() { planetFirstFrame?.complete(Unit) }

    // ── W15.2 隔球望乡：点背面幽灵环 → 星球优雅转到家正面（2026-07-06 拍板·不换场景·无幕）──
    fun spinHomeToFront(reduceMotion: Boolean, homeX: Int, homeY: Int, onHaptic: () -> Unit) {
        if (phase != WorldTransition.None || spinningHome) return
        val pv = planetView ?: return
        spinningHome = true
        onHaptic()
        scope.launch {
            try {
                pv.setInputLocked(true)
                val snap = pv.cameraSnapshot()
                val homeUnit = PlanetMath.homeUnitVector(homeX, homeY)
                val (yawRaw, pitchT) = PlanetMath.diveTarget(homeUnit)
                val yawT = PlanetMath.nearestYaw(snap.yaw, yawRaw)
                val dur = if (reduceMotion) 240 else 720
                Animatable(0f).animateTo(1f, tween(dur, easing = AppMotion.EaseInOut)) {
                    pv.setCinematicPose(lerp(snap.yaw, yawT, value), lerp(snap.pitch, pitchT, value), snap.dist)
                }
            } finally {
                planetView?.clearCinematic() // 视图仍在（未换场景）→ 必须解除覆写，积分从终姿态平滑续跑
                planetView?.setInputLocked(false)
                spinningHome = false
            }
        }
    }

    // ── 星球 → 大陆（金标点击 / overpinch·§3.6·9a）──
    fun startDiveToContinent(reduceMotion: Boolean, homeX: Int, homeY: Int, onHaptic: () -> Unit) {
        if (phase != WorldTransition.None || spinningHome) return
        val pv = planetView ?: return
        phase = WorldTransition.ToContinent
        onHaptic() // 俯冲触发轻点一次
        scope.launch {
            pv.setInputLocked(true)
            val snap = pv.cameraSnapshot()
            vm.savePlanetCamera(snap.yaw, snap.pitch, snap.dist)
            if (reduceMotion) {
                curtain.animateTo(1f, tween(240, easing = AppMotion.EaseInOut)); vm.enterContinent()
            } else {
                val homeUnit = PlanetMath.homeUnitVector(homeX, homeY)
                val (yawRaw, pitchT) = PlanetMath.diveTarget(homeUnit)
                val yawT = PlanetMath.nearestYaw(snap.yaw, yawRaw)
                val curtainJob = launch { delay(280); curtain.animateTo(1f, tween(240, easing = AppMotion.EaseInOut)) }
                Animatable(0f).animateTo(1f, tween(520, easing = AppMotion.EaseInOut)) {
                    pv.setCinematicPose(lerp(snap.yaw, yawT, value), lerp(snap.pitch, pitchT, value), lerp(snap.dist, 1.45f, value))
                }
                curtainJob.join(); vm.enterContinent()
            }
        }
    }

    // ── 大陆 → 星球（overzoom / 系统返回 / 返回钮·§3.6·9a）──
    fun startReturnToPlanet(reduceMotion: Boolean) {
        if (phase != WorldTransition.None) return
        val cv = continentView ?: return
        phase = WorldTransition.ToPlanet
        scope.launch {
            cv.setInputLocked(true); cv.closeSheet()
            if (reduceMotion) {
                curtain.animateTo(1f, tween(240, easing = AppMotion.EaseInOut))
            } else {
                val snap = cv.cameraSnapshot()
                val curtainJob = launch { delay(220); curtain.animateTo(1f, tween(240, easing = AppMotion.EaseInOut)) }
                Animatable(0f).animateTo(1f, tween(460, easing = AppMotion.EaseInOut)) {
                    cv.setCinematicPose(lerp(snap.pitch, 1.12f, value), lerp(snap.dist, 95f, value))
                }
                curtainJob.join()
            }
            // 先建闸再换场（组合在下一帧才发生 → 无「信号先于等待」竞态），等星球真画出首帧再揭幕。
            val firstFrame = CompletableDeferred<Unit>().also { planetFirstFrame = it }
            vm.backToPlanet()
            firstFrame.await()
            curtain.animateTo(0f, tween(240, easing = AppMotion.EaseInOut)); phase = WorldTransition.None
        }
    }

    // ── 大陆 → 小镇（按钮 / overpinch-in·§3.5·460ms 俯冲 dist→4.5·幕入@220·intro 幕后自跑一镜到底）──
    fun startDiveToTown(cityId: String, reduceMotion: Boolean, onHaptic: () -> Unit) {
        if (phase != WorldTransition.None) return
        val cv = continentView ?: return
        phase = WorldTransition.ToTown
        onHaptic() // 进镇触发轻点一次（同 9b 俯冲档）
        scope.launch {
            cv.setInputLocked(true); cv.closeSheet()
            vm.saveContinentCamera(cv.cameraSnapshot(), cv.currentTDist())
            if (reduceMotion) {
                curtain.animateTo(1f, tween(240, easing = AppMotion.EaseInOut)); vm.enterTown(cityId)
            } else {
                val snap = cv.cameraSnapshot()
                val curtainJob = launch { delay(220); curtain.animateTo(1f, tween(240, easing = AppMotion.EaseInOut)) }
                Animatable(0f).animateTo(1f, tween(460, easing = AppMotion.EaseInOut)) {
                    cv.setCinematicPose(snap.pitch, lerp(snap.dist, 4.5f, value)) // pitch 不动·dist→4.5
                }
                curtainJob.join(); vm.enterTown(cityId)
            }
        }
    }

    // ── 小镇 → 大陆（overzoom-out / 返回·§3.5·420ms 拉升 pitch→1.15 dist→38·幕入@200·恢复大陆姿态）──
    fun startReturnToContinent(reduceMotion: Boolean) {
        if (phase != WorldTransition.None) return
        val tv = townView ?: return
        phase = WorldTransition.ToContinentFromTown
        scope.launch {
            tv.setInputLocked(true)
            if (reduceMotion) {
                curtain.animateTo(1f, tween(240, easing = AppMotion.EaseInOut))
            } else {
                val snap = tv.cameraSnapshot()
                val curtainJob = launch { delay(200); curtain.animateTo(1f, tween(240, easing = AppMotion.EaseInOut)) }
                Animatable(0f).animateTo(1f, tween(420, easing = AppMotion.EaseInOut)) {
                    tv.setCinematicPose(lerp(snap.pitch, 1.15f, value), lerp(snap.dist, 38f, value))
                }
                curtainJob.join()
            }
            vm.backToContinent() // ContinentSceneView 用 savedContinentCamera 恢复出发前姿态·其 onFirstFrame → [onContinentFirstFrame]
        }
    }

    // ── 小镇 → 室内（走进去·§4.9·380ms 俯冲 dist→13·幕入@180·intro 幕后自跑）──
    fun startDiveToInterior(cityId: String, placeId: String, reduceMotion: Boolean, onHaptic: () -> Unit) {
        if (phase != WorldTransition.None) return
        val tv = townView ?: return
        phase = WorldTransition.ToInterior
        onHaptic() // 进室内触发轻点一次
        scope.launch {
            tv.setInputLocked(true)
            vm.saveTownCamera(tv.cameraSnapshot(), tv.cameraSnapshot().dist)
            if (reduceMotion) {
                curtain.animateTo(1f, tween(240, easing = AppMotion.EaseInOut)); vm.enterInterior(cityId, placeId)
            } else {
                val snap = tv.cameraSnapshot()
                val curtainJob = launch { delay(180); curtain.animateTo(1f, tween(240, easing = AppMotion.EaseInOut)) }
                Animatable(0f).animateTo(1f, tween(380, easing = AppMotion.EaseInOut)) {
                    tv.setCinematicPose(snap.pitch, lerp(snap.dist, 13f, value)) // pitch 不动·dist→13
                }
                curtainJob.join(); vm.enterInterior(cityId, placeId)
            }
        }
    }

    // ── 室内 → 小镇（overzoom-out / 返回·§4.9·380ms 拉升 pitch→1.0 dist→16.5·幕入@180·恢复小镇姿态）──
    fun startReturnToTown(reduceMotion: Boolean) {
        if (phase != WorldTransition.None) return
        val iv = interiorView ?: return
        phase = WorldTransition.ToTownFromInterior
        scope.launch {
            iv.setInputLocked(true)
            if (reduceMotion) {
                curtain.animateTo(1f, tween(240, easing = AppMotion.EaseInOut))
            } else {
                val snap = iv.cameraSnapshot()
                val curtainJob = launch { delay(180); curtain.animateTo(1f, tween(240, easing = AppMotion.EaseInOut)) }
                Animatable(0f).animateTo(1f, tween(380, easing = AppMotion.EaseInOut)) {
                    iv.setCinematicPose(lerp(snap.pitch, 1.0f, value), lerp(snap.dist, 16.5f, value))
                }
                curtainJob.join()
            }
            vm.backToTown() // TownSceneView 用 savedTownCamera 恢复·其 onFirstFrame → onTownFirstFrame 揭幕
        }
    }

    // ── W10 星球 ↔ 星图 ↔ 小镇（§4.8·无 GL 俯冲·纯淡入淡出幕·200ms / reduceMotion 150ms）──

    /** 星球 → 星图（Planet 入口 chip·存星球相机供返回恢复·进星图轻点一次）。 */
    fun startFadeToStarmap(reduceMotion: Boolean, onHaptic: () -> Unit) {
        if (phase != WorldTransition.None || spinningHome) return
        phase = WorldTransition.ToStarmap
        onHaptic() // 进星图轻点一次（俯冲同档）
        scope.launch {
            planetView?.let { pv ->
                pv.setInputLocked(true)
                val snap = pv.cameraSnapshot()
                vm.savePlanetCamera(snap.yaw, snap.pitch, snap.dist)
            }
            fadeThrough(reduceMotion) { vm.enterStarmap() }
        }
    }

    /** 星图 → 星球（返回键 / 返回钮·星球重建走 savedPlanetCamera 恢复姿态·PlanetSceneHost initialPose 既有）。 */
    fun startReturnFromStarmap(reduceMotion: Boolean) {
        if (phase != WorldTransition.None) return
        phase = WorldTransition.FromStarmap
        scope.launch { fadeThrough(reduceMotion, waitPlanetFirstFrame = true) { vm.backFromStarmap() } }
    }

    /** 星图 → 小镇（待相识卡「去{城}看看」·savedContinentCamera 保持 null=回大陆新鲜入场·返回链 Town→Continent→Planet 既有）。 */
    fun startJumpToTownFromStarmap(cityId: String, reduceMotion: Boolean) {
        if (phase != WorldTransition.None) return
        phase = WorldTransition.FromStarmap
        scope.launch { fadeThrough(reduceMotion) { vm.enterTown(cityId) } }
    }

    /**
     * 淡入淡出幕编排（§4.8 三段共用）：curtain→1 → 中段换场 → 等待（[waitPlanetFirstFrame]=星球首帧信号·
     * 否则补 2 帧） → curtain→0 → phase 复位。揭幕时长两路都用原 [dur]（性能卷 B 只换「开始揭」的触发器）。
     */
    private suspend fun fadeThrough(
        reduceMotion: Boolean,
        waitPlanetFirstFrame: Boolean = false,
        midAction: () -> Unit,
    ) {
        val dur = if (reduceMotion) 150 else 200
        curtain.animateTo(1f, tween(dur, easing = AppMotion.EaseInOut))
        val firstFrame = if (waitPlanetFirstFrame) CompletableDeferred<Unit>().also { planetFirstFrame = it } else null
        midAction()
        if (firstFrame != null) firstFrame.await() else repeat(2) { withFrameNanos { } }
        curtain.animateTo(0f, tween(dur, easing = AppMotion.EaseInOut))
        phase = WorldTransition.None
    }

    /** 室内首帧揭幕（小镇→室内 一镜到底·§4.9·intro 已在幕后自跑）。 */
    fun onInteriorFirstFrame() {
        if (phase != WorldTransition.ToInterior) return
        scope.launch { curtain.animateTo(0f, tween(240, easing = AppMotion.EaseInOut)); phase = WorldTransition.None }
    }

    /** 大陆首帧揭幕（星球→大陆 一镜到底·§3.6 步4 / 小镇→大陆 恢复后揭幕·两向共用 ContinentRenderer.onFirstFrame）。 */
    fun onContinentFirstFrame() {
        if (phase != WorldTransition.ToContinent && phase != WorldTransition.ToContinentFromTown) return
        scope.launch { curtain.animateTo(0f, tween(240, easing = AppMotion.EaseInOut)); phase = WorldTransition.None }
    }

    /** 小镇首帧揭幕（大陆→小镇 一镜到底·§3.5 / 室内→小镇 恢复后揭幕·§4.9·两向共用 TownRenderer.onFirstFrame）。 */
    fun onTownFirstFrame() {
        if (phase != WorldTransition.ToTown && phase != WorldTransition.ToTownFromInterior) return
        scope.launch { curtain.animateTo(0f, tween(240, easing = AppMotion.EaseInOut)); phase = WorldTransition.None }
    }
}

/** 大陆相机恢复态（进小镇时存·回大陆恢复·快照六元 + tDist·§3.1）。 */
internal typealias ContinentCamRestore = Pair<ContinentCamSnapshot, Float>
