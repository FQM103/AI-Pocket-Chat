package com.situ.aichat.ui.world.planet

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * 星球渲染的纯数学核（W9a 图纸 §2 / §3.5 / §3.6）——全部常量与表达式**逐式移植**自对版 demo
 * `design/world/planet-3d-demo.html`（图纸 §9 禁改）。零 Android 依赖 → JVM 单测直测（T1）。
 *
 * mat4 采用 demo 同款**列主序** `Float32Array(16)` 语义（`m[col*4 + row]`），故可直接以
 * `GLES20.glUniformMatrix4fv(loc, 1, transpose = false, m, 0)` 上传，无需转置。CPU 版噪声（[hash]/
 * [vnoise]/[fbm]）与 `FS_PLANET`/`NOISE` 逐式一致（含 `43758.5453123`、`f*f*(3-2f)`、5 octave、
 * `p*=2.03`、`a*=0.5`）：用 Float（= GPU highp 同为 IEEE float32）令 CPU 陆地搜索最贴近 GPU 着色结果。
 */
internal object PlanetMath {

    // ── 相机/投影常量（demo:L221·锁死·图纸 §9）──
    const val FOV = 0.9f
    const val NEAR = 0.1f
    const val FAR = 30f

    // ── 家乡标记（demo:L245-246·锁死）──
    /** 可见判据：世界系正 z 大于此值才朝向镜头（demo:L245）。 */
    const val MARKER_VISIBLE_Z = 0.28f

    /** 标记稍抬离球面避免 z-fighting（demo:L246 `home*1.01`）。 */
    const val MARKER_LIFT = 1.01f

    // ── 图集→球面映射（图纸 §3.6·温带带·避开极地雪盖·锁死）──
    private const val PLANE_Y_SPAN = 2600.0
    private const val LAT_TOP_DEG = 45.0
    private const val LAT_SPAN_DEG = 84.0
    private const val PLANE_X_SPAN = 4800.0
    private const val DEG_TO_RAD = Math.PI / 180.0

    // ── seedOff 陆地搜索（图纸 §3.6·锁死）──
    private const val SEED_MOD = 1000L
    private const val SEED_BASE_OFFSET = 3.0
    private const val SEED_STEP = 0.37
    private const val SEED_TRIES = 24 // k = 0..23
    private const val LAND_SEARCH_THRESHOLD = 0.55f // 陆地阈 0.535 + 余量

    // ────────────────────────── mat4（列主序·demo:L55-63 逐式）──────────────────────────

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    /** 透视投影（demo:L56-57）。 */
    fun persp(fov: Float, asp: Float, n: Float, f: Float): FloatArray {
        val t = 1f / tan(fov / 2f)
        val m = FloatArray(16)
        m[0] = t / asp
        m[5] = t
        m[10] = (f + n) / (n - f)
        m[11] = -1f
        m[14] = 2f * f * n / (n - f)
        return m
    }

    /** 列主序矩阵乘 a·b（demo:L58-60·`o[i*4+j] = Σ a[k*4+j]·b[i*4+k]`）。 */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(16)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var v = 0f
                for (k in 0 until 4) v += a[k * 4 + j] * b[i * 4 + k]
                o[i * 4 + j] = v
            }
        }
        return o
    }

    /** 绕 X 轴旋转（demo:L61）。 */
    fun rotX(r: Float): FloatArray {
        val c = cos(r)
        val s = sin(r)
        val m = identity()
        m[5] = c; m[6] = s; m[9] = -s; m[10] = c
        return m
    }

    /** 绕 Y 轴旋转（demo:L62）。 */
    fun rotY(r: Float): FloatArray {
        val c = cos(r)
        val s = sin(r)
        val m = identity()
        m[0] = c; m[2] = -s; m[8] = s; m[10] = c
        return m
    }

    /** 沿 Z 平移（demo:L63）。 */
    fun trans(z: Float): FloatArray {
        val m = identity()
        m[14] = z
        return m
    }

    /** m（列主序）× (x,y,z,1)·返回 [x,y,z,w]（demo:L198-201·恒 w=1）。 */
    fun v4(m: FloatArray, x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14],
        m[3] * x + m[7] * y + m[11] * z + m[15],
    )

    /**
     * 每帧场景矩阵（demo:L219-222·先自转后俯仰：`model = rotX(pitch)·rotY(yaw)`，俯仰轴恒为屏幕水平轴）。
     * 渲染器（GL 线程）与 [WorldScreen] 家乡标记投影（Compose 侧）**共用同式**，避免两处漂移。
     */
    fun sceneMatrices(yaw: Float, pitch: Float, dist: Float, aspect: Float): SceneMatrices {
        val model = mul(rotX(pitch), rotY(yaw))
        val view = trans(-dist)
        val proj = persp(FOV, aspect, NEAR, FAR)
        val mvp = mul(proj, mul(view, model))
        return SceneMatrices(model, mvp)
    }

    // ────────────────────────── CPU 噪声（demo:L86-94 逐式·float32）──────────────────────────

    private fun fract(x: Float): Float = x - floor(x)

    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun hash(px: Float, py: Float, pz: Float): Float =
        fract(sin(px * 127.1f + py * 311.7f + pz * 74.7f) * 43758.5453123f)

    fun vnoise(px: Float, py: Float, pz: Float): Float {
        val ix = floor(px); val iy = floor(py); val iz = floor(pz)
        var fx = px - ix; var fy = py - iy; var fz = pz - iz
        fx = fx * fx * (3f - 2f * fx)
        fy = fy * fy * (3f - 2f * fy)
        fz = fz * fz * (3f - 2f * fz)
        val n000 = hash(ix, iy, iz); val n100 = hash(ix + 1f, iy, iz)
        val n010 = hash(ix, iy + 1f, iz); val n110 = hash(ix + 1f, iy + 1f, iz)
        val n001 = hash(ix, iy, iz + 1f); val n101 = hash(ix + 1f, iy, iz + 1f)
        val n011 = hash(ix, iy + 1f, iz + 1f); val n111 = hash(ix + 1f, iy + 1f, iz + 1f)
        return mix(
            mix(mix(n000, n100, fx), mix(n010, n110, fx), fy),
            mix(mix(n001, n101, fx), mix(n011, n111, fx), fy),
            fz,
        )
    }

    fun fbm(px: Float, py: Float, pz: Float): Float {
        var v = 0f; var a = 0.5f
        var x = px; var y = py; var z = pz
        repeat(5) {
            v += a * vnoise(x, y, z)
            x *= 2.03f; y *= 2.03f; z *= 2.03f
            a *= 0.5f
        }
        return v
    }

    // ────────────────────────── 图集→球面 + 家乡标记 + seedOff（图纸 §3.5/§3.6）──────────────────────────

    /** 图集平面坐标 (x,y) → 经纬度（度·图纸 §3.6·温带带映射）。 */
    fun atlasToLatLonDeg(x: Int, y: Int): Pair<Double, Double> {
        val latDeg = LAT_TOP_DEG - (y / PLANE_Y_SPAN) * LAT_SPAN_DEG
        val lonDeg = (x / PLANE_X_SPAN) * 360.0 - 180.0
        return latDeg to lonDeg
    }

    /** 经纬度（度）→ 单位球向量 [cos(lat)cos(lon), sin(lat), cos(lat)sin(lon)]（demo:L196-197 同式）。 */
    fun latLonToUnit(latDeg: Double, lonDeg: Double): FloatArray {
        val la = latDeg * DEG_TO_RAD
        val lo = lonDeg * DEG_TO_RAD
        return floatArrayOf(
            (cos(la) * cos(lo)).toFloat(),
            sin(la).toFloat(),
            (cos(la) * sin(lo)).toFloat(),
        )
    }

    /** 图集家乡坐标 → 单位球向量（映射 + 投影一次到位）。 */
    fun homeUnitVector(x: Int, y: Int): FloatArray {
        val (latDeg, lonDeg) = atlasToLatLonDeg(x, y)
        return latLonToUnit(latDeg, lonDeg)
    }

    /**
     * 由世界种子确定性派生 seedOff，令家乡标记落在**陆地**上（图纸 §3.6·锁死）：
     * `base = (seed mod 1000)/100 + 3`；`k = 0..23`：`cand = base + k*0.37`，取首个使家乡点
     * `fbm(homeLn*2 + cand) ≥ 0.55` 者；24 个都不中 → 兜底 base（概率极低）。
     * CPU fbm 与 `FS_PLANET`（demo:L102-103）同式，令搜索到的陆地与 GPU 着色一致。
     */
    fun deriveSeedOff(seed: Long, homeUnit: FloatArray): Float {
        val hx = homeUnit[0]; val hy = homeUnit[1]; val hz = homeUnit[2]
        val base = (seed.mod(SEED_MOD)) / 100.0 + SEED_BASE_OFFSET
        return searchLandOffset(base) { cand ->
            fbm(hx * 2f + cand, hy * 2f + cand, hz * 2f + cand)
        }
    }

    /**
     * 陆地搜索核（图纸 §3.6·锁死步长/阈/次数）：`k = 0..23` 取首个使 [contAt] `≥ 0.55` 的
     * `base + k*0.37`；全败 → 兜底 `base`。[contAt] 注入令搜索逻辑与 fbm 解耦、可用假采样器独立验（T1 E4）。
     */
    fun searchLandOffset(base: Double, contAt: (Float) -> Float): Float {
        for (k in 0 until SEED_TRIES) {
            val cand = (base + k * SEED_STEP).toFloat()
            if (contAt(cand) >= LAND_SEARCH_THRESHOLD) return cand
        }
        return base.toFloat()
    }

    /**
     * 家乡标记屏幕投影（demo:L244-250·图纸 §3.5）：可见（`wp.z > 0.28` 且 `cp.w > 0`）时给出屏幕像素
     * 坐标，否则不可见。[viewW]/[viewH] = 场景区像素尺寸（与 GL 表面同宽高比）。
     */
    fun projectHome(
        model: FloatArray,
        mvp: FloatArray,
        homeUnit: FloatArray,
        viewW: Float,
        viewH: Float,
    ): MarkerProjection {
        val wp = v4(model, homeUnit[0], homeUnit[1], homeUnit[2])
        val facing = wp[2] > MARKER_VISIBLE_Z
        val cp = v4(mvp, homeUnit[0] * MARKER_LIFT, homeUnit[1] * MARKER_LIFT, homeUnit[2] * MARKER_LIFT)
        if (facing && cp[3] > 0f) {
            val sx = (cp[0] / cp[3] * 0.5f + 0.5f) * viewW
            val sy = (-cp[1] / cp[3] * 0.5f + 0.5f) * viewH
            return MarkerProjection(visible = true, x = sx, y = sy)
        }
        return MarkerProjection(visible = false, x = 0f, y = 0f)
    }

    // ────────────────────────── W9b 俯冲转场（加法·既有函数零改·图纸 §3.5）──────────────────────────

    // ────────────────── W15.2 隔球望乡（2026-07-06 拍板·加法·projectHome 锁死零改）──────────────────

    /**
     * 家乡标记全量投影（W15.2·W15.3 增补 wp 分量）：与 [projectHome] 同式投影，背面亦给屏幕坐标；
     * 另吐世界系分量 [HomeProjection.facingZ]（+1 正对镜头 / −1 正背面）与 wpX/wpY（W15.3 边缘指示方向用）。
     * 单位球点的裁剪 w = dist − 1.01·z ≥ dist − 1.01 > 0（dist 钳 ≥1.9），恒可投影。
     */
    fun projectHomeFull(
        model: FloatArray,
        mvp: FloatArray,
        homeUnit: FloatArray,
        viewW: Float,
        viewH: Float,
    ): HomeProjection {
        val wp = v4(model, homeUnit[0], homeUnit[1], homeUnit[2])
        val cp = v4(mvp, homeUnit[0] * MARKER_LIFT, homeUnit[1] * MARKER_LIFT, homeUnit[2] * MARKER_LIFT)
        val sx = (cp[0] / cp[3] * 0.5f + 0.5f) * viewW
        val sy = (-cp[1] / cp[3] * 0.5f + 0.5f) * viewH
        return HomeProjection(x = sx, y = sy, facingZ = wp[2], wpX = wp[0], wpY = wp[1])
    }

    /**
     * 标记双态可见度（W15.2·**W15.3 改**：透视幽灵环废弃 → ghost 通道改语义为「边缘指示器强度」）：
     * 以**真实地平线** `hz = 1/dist` 为界连续渐变——
     * - front = smoothstep(hz−0.05, hz+0.05, z)：正面标记强度（跨地平线淡出）；
     * - label = smoothstep(hz+0.04, hz+0.14, z)：标签在临近边缘先行淡出；
     * - ghost = 1−front：背面**边缘雪佛龙**强度（与 front 严格互补交叉渐变）。
     */
    fun homeMarkerVisual(facingZ: Float, dist: Float): HomeMarkerVisual {
        val hz = 1f / dist
        val front = smoothstep(hz - 0.05f, hz + 0.05f, facingZ)
        val label = smoothstep(hz + 0.04f, hz + 0.14f, facingZ)
        return HomeMarkerVisual(front = front, label = label, ghost = 1f - front)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ──────────── W15.3 真·抓取拖拽 + 边缘指路（2026-07-06 用户「认真重构一步到位」·加法）────────────

    /**
     * 屏幕触点 → 单位球面点（视空间·以球心为原点·W15.3 抓取锚定第一步）。
     * 射线 = 投影逆映射（FOV 纵向·aspect=W/H），球心在视空间 (0,0,−dist)、半径 1；取近交点。
     * 未命中（缩得很远时点到星空）→ null。返回向量满足 `model·anchor = 该向量`（与 [sceneMatrices] 同约定）。
     */
    fun screenToSphere(xPx: Float, yPx: Float, viewW: Float, viewH: Float, dist: Float): FloatArray? {
        val t = 1f / tan(FOV / 2f)
        val asp = viewW / viewH
        val ndcX = xPx / viewW * 2f - 1f
        val ndcY = 1f - yPx / viewH * 2f
        val dx = ndcX * asp / t
        val dy = ndcY / t
        val dz = -1f
        val a = dx * dx + dy * dy + dz * dz
        // |s·d − c|² = 1，c = (0,0,−dist)：s²·a − 2s·dist + dist²−1 = 0
        val disc4 = dist * dist - a * (dist * dist - 1f)
        if (disc4 < 0f) return null
        val s = (dist - kotlin.math.sqrt(disc4)) / a
        val px = s * dx
        val py = s * dy
        val pz = s * dz + dist
        val len = kotlin.math.sqrt(px * px + py * py + pz * pz)
        return floatArrayOf(px / len, py / len, pz / len)
    }

    /** 世界系球面点 → 模型系锚点：`a = rotY(−yaw)·rotX(−pitch)·q`（[sceneMatrices] `model = rotX·rotY` 的逆）。 */
    fun modelAnchor(q: FloatArray, yaw: Float, pitch: Float): FloatArray {
        val cp = cos(pitch); val sp = sin(pitch)
        // rotX(−pitch)·q
        val y1 = cp * q[1] + sp * q[2]
        val z1 = -sp * q[1] + cp * q[2]
        val cy = cos(yaw); val sy = sin(yaw)
        // rotY(−yaw)·(q0, y1, z1)
        return floatArrayOf(cy * q[0] - sy * z1, y1, sy * q[0] + cy * z1)
    }

    /**
     * 抓取反解（W15.3 核心）：解 (yaw, pitch) 使 `rotX(pitch)·rotY(yaw)·anchor = q`——按下时锚定的球面点
     * 始终钉在手指命中的新方向 [q] 下。俯仰双解取**距当前姿态最近**者并钳 ±[pitchLimit]（钳住时纵向打滑=
     * 地球仪标准行为，横向仍精确）；锚点在极冠（xz 模 < 0.05）→ yaw 保持不变；q 纵向越出可达域 → 取可达极值。
     * yaw 用 [nearestYaw] 归到当前圈，防跨 ±π 突跳。
     */
    fun solveGrabPose(
        anchor: FloatArray,
        q: FloatArray,
        currentYaw: Float,
        currentPitch: Float,
        pitchLimit: Float,
    ): Pair<Float, Float> {
        // 俯仰：cos(p)·q_y + sin(p)·q_z = a_y → R·cos(p−φ) = a_y，φ = atan2(q_z, q_y)
        val r = kotlin.math.hypot(q[1], q[2])
        var p = currentPitch
        if (r > 1e-6f) {
            val phi = atan2(q[2], q[1])
            val ratio = anchor[1] / r
            p = when {
                ratio >= 1f -> phi
                ratio <= -1f -> phi + Math.PI.toFloat()
                else -> {
                    val d = kotlin.math.acos(ratio)
                    // 双解 φ±d，各配 ±2π 等价角，取距当前俯仰最近
                    val twoPi = (2.0 * Math.PI).toFloat()
                    var best = phi + d
                    var bestDist = Float.MAX_VALUE
                    for (cand0 in floatArrayOf(phi + d, phi - d)) {
                        for (k in -1..1) {
                            val cand = cand0 + k * twoPi
                            val dd = abs(cand - currentPitch)
                            if (dd < bestDist) { bestDist = dd; best = cand }
                        }
                    }
                    best
                }
            }
        }
        p = p.coerceIn(-pitchLimit, pitchLimit)
        // 偏航：rotY(yaw)·a 与 rotX(−p)·q 的 xz 相位差（rotY 给 xz 相位加 yaw）
        val cp = cos(p); val sp = sin(p)
        val cx = q[0]
        val cz = -sp * q[1] + cp * q[2]
        val axzLen = kotlin.math.hypot(anchor[0], anchor[2])
        val yaw = if (axzLen < 0.05f) {
            currentYaw // 抓在极冠：偏航无良定义，保持
        } else {
            nearestYaw(currentYaw, atan2(cx, cz) - atan2(anchor[0], anchor[2]))
        }
        return yaw to p
    }

    /**
     * 家的边缘指路雪佛龙（W15.3·取代透视幽灵环——透视投影在屏幕上与拖拽方向天然镜像=体感全反的病根）：
     * **出现条件 = 家不在屏内可见**（几何背面 ∪ 正面但投影出屏——近距缩放时屏幕只是球面中央一小窗，
     * 正面出屏区域远大于背面）。返回 `[x, y, angleRad, alpha]`：
     * - **方向**：正面出屏 → 投影点相对屏心方向（真实所在侧）；背面 → 世界系 (wpX, −wpY)（家将从哪侧
     *   转回来）——两者在地平线处同向、连续衔接；
     * - **位置**：屏心沿方向推至 min(屏幕矩形边 − [marginPx], 星球剪影半径 − [marginPx])；
     * - **alpha** = max(1−front, smoothstep(0, 80px, 投影点出屏距离))——家在屏内正面时为 0，滑出屏/转到
     *   背面时连续升到 1（与正面标记交叉渐变）。
     */
    fun homeChevron(
        proj: HomeProjection,
        dist: Float,
        viewW: Float,
        viewH: Float,
        marginPx: Float,
    ): FloatArray {
        val hz = 1f / dist
        val front = smoothstep(hz - 0.05f, hz + 0.05f, proj.facingZ)
        // 出屏程度（px）：投影点越出屏幕矩形多远
        val excess = maxOf(0f, -proj.x, proj.x - viewW, -proj.y, proj.y - viewH)
        val alpha = maxOf(1f - front, smoothstep(0f, 80f, excess))
        // 方向：正面用投影点方向（含出屏），背面用世界系环绕方向（地平线处两者同侧连续）
        var nx: Float
        var ny: Float
        if (proj.facingZ >= hz) {
            nx = proj.x - viewW / 2f
            ny = proj.y - viewH / 2f
        } else {
            nx = proj.wpX
            ny = -proj.wpY
        }
        if (ny == 0f) ny = 0f // 规范负零（atan2(−0,−1)=−π 而非 π·方向等价但下游断言/日志易混）
        val len = kotlin.math.hypot(nx, ny)
        if (len < 1e-4f) { nx = 0f; ny = -1f } else { nx /= len; ny /= len }
        val cxPx = viewW / 2f
        val cyPx = viewH / 2f
        var s = Float.MAX_VALUE
        if (abs(nx) > 1e-6f) s = minOf(s, (cxPx - marginPx) / abs(nx))
        if (abs(ny) > 1e-6f) s = minOf(s, (cyPx - marginPx) / abs(ny))
        // 星球剪影半径（px）：地平角 cosφ=1/dist；投影半径 = sinφ/(dist−cosφ)·(1/tan(FOV/2))·H/2
        val cosH = 1f / dist
        val sinH = kotlin.math.sqrt(1f - cosH * cosH)
        val rSil = sinH / (dist - cosH) * (1f / tan(FOV / 2f)) * viewH / 2f
        s = minOf(s, rSil - marginPx)
        return floatArrayOf(cxPx + nx * s, cyPx + ny * s, atan2(ny, nx), alpha)
    }

    /**
     * 俯冲镜头目标姿态（图纸 §3.5·加法）：令 `model(yawT,pitchT)·homeUnit = (0,0,1)`——把家乡点转到正对
     * 镜头（+z 面）。`model = rotX(pitch)·rotY(yaw)`（demo 场景矩阵），反解得 `yawT = atan2(-hx, hz)`、
     * `pitchT = asin(hy)`（互证 4 组样本 model×h≈(0,0,1) 误差<1e-12·家乡金标 yawT=2.356194/pitchT=0.052360）。
     */
    fun diveTarget(homeUnit: FloatArray): Pair<Float, Float> {
        val yawT = atan2(-homeUnit[0], homeUnit[2])
        val pitchT = asin(homeUnit[1].coerceIn(-1f, 1f))
        return yawT to pitchT
    }

    /**
     * 就近等价角（图纸 §3.5·加法）：`target + round((current-target)/2π)·2π`——俯冲插值时避免地球倒转半圈
     * （E9 金标 nearestYaw(7.0, 2.356194)=8.639380）。
     */
    fun nearestYaw(current: Float, target: Float): Float {
        val twoPi = (2.0 * Math.PI).toFloat()
        return target + ((current - target) / twoPi).roundToInt() * twoPi
    }
}

/** 一帧的 model 与 mvp 矩阵（[PlanetMath.sceneMatrices] 产出·列主序）。 */
internal class SceneMatrices(val model: FloatArray, val mvp: FloatArray)

/** 家乡标记投影结果（[PlanetMath.projectHome]·屏幕像素坐标）。 */
internal data class MarkerProjection(val visible: Boolean, val x: Float, val y: Float)

/** 家乡标记全量投影（W15.2/.3·[PlanetMath.projectHomeFull]·wpX/wpY/facingZ = 世界系分量·背面亦有坐标）。 */
internal data class HomeProjection(val x: Float, val y: Float, val facingZ: Float, val wpX: Float, val wpY: Float)

/** 家乡标记双态可见度（[PlanetMath.homeMarkerVisual]·front=正面标记 label=标签 ghost=边缘雪佛龙·∈[0,1]）。 */
internal data class HomeMarkerVisual(val front: Float, val label: Float, val ghost: Float)
