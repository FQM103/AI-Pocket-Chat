package com.situ.aichat.ui.world.continent

import com.situ.aichat.ui.world.planet.PlanetMath
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 大陆盒景的纯数学核（W9b 图纸 §2/§3.3/§3.4/§4.1）——2D 噪声 + 地形高度 + 逐面配色 + 相机/投影矩阵
 * + 站位屏幕投影。全部逐式移植自对版 demo `design/world/continent-3d-demo.html`（图纸 §9 禁改）。
 * 零 Android 依赖 → JVM 单测直测（T1）。
 *
 * **精度纪律（图纸 §9）**：噪声与地形高度用 **Double**（= demo JS float64 语义·勿用星球栈的 Float，那是
 * 贴 GPU 的另一回事），令 [fbm2]/[heightAt]/[colorFor] 与 demo 逐值一致（E1/E2 金标 ±1e-9）。相机/投影
 * 矩阵复用 [PlanetMath] 的 Float 列主序族（= demo Float32Array 语义），只新增大陆特有的三参平移 [trans3]
 * 与带 target 的 [continentView]/[continentMvp]/[projectSite]（demo:L78,372-373,325-327）。
 */
internal object ContinentMath {

    // ── 盒景常量（demo:L164·图纸 §3.3·单源）──
    const val S = 46f          // 台座边长
    const val RES = 64         // 地形网格细分
    const val BASE = -3.5f     // 台座底 y
    const val SPREAD = 14f     // 站位散布半径（demo 手植站位实测域 ±14）

    // ── 投影常量（demo:L373）──
    private const val FOV = 0.85f
    private const val NEAR = 0.5f
    private const val FAR = 220f

    /** heightAt 边缘衰减半宽 `S/2 - 1`（demo:L168·Double）。 */
    private const val EDGE = 22.0

    // ──────────────────────── 2D 噪声（demo:L85-91 逐式·float64）────────────────────────

    fun hash2(x: Double, z: Double): Double {
        val h = sin(x * 127.1 + z * 311.7) * 43758.5453123
        return h - floor(h)
    }

    fun vnoise2(x: Double, z: Double): Double {
        val ix = floor(x); val iz = floor(z)
        var fx = x - ix; var fz = z - iz
        fx = fx * fx * (3.0 - 2.0 * fx)
        fz = fz * fz * (3.0 - 2.0 * fz)
        val a = hash2(ix, iz); val b = hash2(ix + 1.0, iz)
        val c = hash2(ix, iz + 1.0); val d = hash2(ix + 1.0, iz + 1.0)
        return a + (b - a) * fx + (c - a) * fz + (a - b - c + d) * fx * fz
    }

    fun fbm2(x: Double, z: Double): Double {
        var v = 0.0; var a = 0.5; var px = x; var pz = z
        repeat(5) {
            v += a * vnoise2(px, pz)
            px *= 2.03; pz *= 2.03; a *= 0.5
        }
        return v
    }

    fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    // ──────────────────────── 地形高度（demo:L166-175·台地 + 站位 pad 整平）────────────────────────

    /**
     * 某点地形高度（demo:L166-175 逐式·Double）。[pads] = 站位 pad 中心 box 坐标（每项 `[x, z]`·按顺序
     * 迭代整平），来自 [ContinentSceneData]（精修城 + 生成城 + 奇观全集）。台地阶仅 [RegionStyle.terrace]。
     */
    fun heightAt(x: Double, z: Double, style: RegionStyle, pads: List<DoubleArray>): Double {
        val f = fbm2((x + style.seed * 13.7) * 0.075, (z + style.seed * 7.9) * 0.075)
        val u = abs(x) / EDGE
        val v = abs(z) / EDGE
        val r = Math.pow(Math.pow(u, 4.0) + Math.pow(v, 4.0), 0.25) // demo:L169 逐式（Math.pow 非整幂展开）
        val m = 1.0 - smooth(style.coast, 1.0, r)
        var h = (f - style.sea) * style.amp * m - (1.0 - m) * 2.2
        if (style.terrace && h > 0.0) h = h * 0.35 + (floor(h / 1.1) * 1.1 + 0.55) * 0.65
        for (c in pads) {
            val d = hypot(x - c[0], z - c[1])
            val w = 1.0 - smooth(1.6, 3.0, d)
            if (w > 0.0) h = h * (1.0 - w) + style.padH * w
        }
        return h
    }

    // ──────────────────────── 逐面配色（demo:L176-185·分支序不许换）────────────────────────

    /**
     * 某面颜色（demo:L176-185·分支序不许换·Double RGB 0..1）。[ny] = 近似坡度、[h] = 面均高。
     * 末尾逐面微差抖动 `j = 0.94 + hash2·0.10 ∈ [0.94, 1.04)`（低多边形质感）。
     */
    fun colorFor(h: Double, ny: Double, x: Double, z: Double, style: RegionStyle): DoubleArray {
        val c: DoubleArray = when {
            h < 0.0 -> style.bed
            h > style.snowLine -> if (ny < 0.5) style.rock else style.snow
            ny < 0.52 -> style.cliff
            h < 0.45 -> style.beach
            else -> {
                val t = smooth(0.35, 0.65, fbm2(x * 0.3 + 9.0, z * 0.3 + 9.0))
                doubleArrayOf(
                    style.g1[0] + (style.g2[0] - style.g1[0]) * t,
                    style.g1[1] + (style.g2[1] - style.g1[1]) * t,
                    style.g1[2] + (style.g2[2] - style.g1[2]) * t,
                )
            }
        }
        val j = 0.94 + hash2(floor(x * 2.1), floor(z * 2.1)) * 0.10
        return doubleArrayOf(c[0] * j, c[1] * j, c[2] * j)
    }

    // ──────────────────────── 相机 / 投影（demo:L78,372-373·Float 列主序·复用 PlanetMath）────────────────────────

    /** 三参平移矩阵（demo:L78·星球栈只有单参 z 版，本块新增）。 */
    fun trans3(x: Float, y: Float, z: Float): FloatArray {
        val m = PlanetMath.identity()
        m[12] = x; m[13] = y; m[14] = z
        return m
    }

    /** 视图矩阵 `trans(0,0,-dist)·rotX(pitch)·rotY(yaw)·trans(-target)`（demo:L372）。 */
    fun continentView(yaw: Float, pitch: Float, dist: Float, tx: Float, ty: Float, tz: Float): FloatArray {
        val rot = PlanetMath.mul(PlanetMath.rotX(pitch), PlanetMath.rotY(yaw))
        val rotTrans = PlanetMath.mul(rot, trans3(-tx, -ty, -tz))
        return PlanetMath.mul(trans3(0f, 0f, -dist), rotTrans)
    }

    /** MVP `persp(0.85, asp, 0.5, 220)·view`（demo:L373）·渲染器与覆盖层投影共用同式。 */
    fun continentMvp(
        yaw: Float, pitch: Float, dist: Float,
        tx: Float, ty: Float, tz: Float, aspect: Float,
    ): FloatArray {
        val view = continentView(yaw, pitch, dist, tx, ty, tz)
        val proj = PlanetMath.persp(FOV, aspect, NEAR, FAR)
        return PlanetMath.mul(proj, view)
    }

    /**
     * 站位世界坐标 → 屏幕像素（demo:L325-327）：`c=mvp·(x,y,z,1)`；`c.w≤0` → 不可见；否则
     * `sx=(c.x/c.w·0.5+0.5)·W`、`sy=(-c.y/c.w·0.5+0.5)·H`。
     */
    fun projectSite(mvp: FloatArray, x: Float, y: Float, z: Float, viewW: Float, viewH: Float): SiteProjection {
        val c = PlanetMath.v4(mvp, x, y, z)
        if (c[3] <= 0f) return SiteProjection(false, 0f, 0f)
        val sx = (c[0] / c[3] * 0.5f + 0.5f) * viewW
        val sy = (-c[1] / c[3] * 0.5f + 0.5f) * viewH
        return SiteProjection(true, sx, sy)
    }
}

/** 站位屏幕投影结果（[ContinentMath.projectSite]·屏幕像素坐标）。 */
internal data class SiteProjection(val visible: Boolean, val x: Float, val y: Float)
