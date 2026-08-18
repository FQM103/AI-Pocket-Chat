package com.situ.aichat.ui.world.gl

import com.situ.aichat.ui.world.planet.PlanetMath
import kotlin.math.tan

/**
 * 自由漫游相机共用数学核（W15 图纸 §4A.1）——屏幕像素 → 地面点反投影 [groundPoint] + 角度归一 [wrapPi]。
 * 零 Android 依赖 → JVM 单测直测（T1-1）。
 *
 * **精度纪律（图纸 §9）**：矩阵一律复用 [PlanetMath] 的 Float 列主序族，与三盒景视图
 * `trans(0,0,-dist)·rotX(pitch)·rotY(yaw)·trans(-target)`（`ContinentMath.continentView` 同式）**严格互逆**，
 * 禁止手写三角展开或改用其他矩阵序——杜绝符号约定漂移。正确性以 T1-1 回程互证锁死
 * （[groundPoint] 结果投回 `continentMvp`/`projectSite` 落回原像素 ±0.5px）。
 */
internal object WorldCameraMath {

    /** 视线接近水平（|dir.y| ≤ 此）即判打不到地面（避免除以近零）。 */
    private const val DIR_Y_EPS = 1e-4f

    /** 反投影超程守卫：命中距离 > `RANGE_FACTOR·dist` 判无效（地平线附近距离爆炸的保险丝）。 */
    private const val RANGE_FACTOR = 4f

    /**
     * 屏幕像素 (px,py) → 相机注视平面 y=ty 上的地面点 [x,z]；打不到（地平线以上 / 反向 / 超程）返回 null。
     * [fov] 由调用方传场景值：大陆/小镇 0.85f、室内 0.8f；[viewW]/[viewH] = GL 表面像素尺寸。
     */
    fun groundPoint(
        yaw: Float, pitch: Float, dist: Float,
        tx: Float, ty: Float, tz: Float,
        px: Float, py: Float, viewW: Float, viewH: Float,
        fov: Float,
    ): FloatArray? {
        val tanHalf = tan(fov / 2f)
        // rInv = R⁻¹（视图旋转 R = rotX(pitch)·rotY(yaw) 的逆·顺序反转 + 角取负）。
        val rInv = PlanetMath.mul(PlanetMath.rotY(-yaw), PlanetMath.rotX(-pitch))
        // 眼位 = target + R⁻¹·(0,0,dist)。
        val eyeOff = PlanetMath.v4(rInv, 0f, 0f, dist)
        val eyeX = tx + eyeOff[0]; val eyeY = ty + eyeOff[1]; val eyeZ = tz + eyeOff[2]
        // 像素 → NDC → 眼系视线方向 → 世界系方向（rInv 无平移列，v4 平移项恒 0）。
        val ndcX = 2f * px / viewW - 1f
        val ndcY = 1f - 2f * py / viewH
        val dir = PlanetMath.v4(rInv, ndcX * tanHalf * (viewW / viewH), ndcY * tanHalf, -1f)
        val dirY = dir[1]
        if (dirY >= -DIR_Y_EPS) return null // 地平线以上 / 视线朝上 → 打不到地面。
        val s = (ty - eyeY) / dirY
        if (s <= 0f || s > RANGE_FACTOR * dist) return null // 反向 / 超程。
        return floatArrayOf(eyeX + s * dir[0], eyeZ + s * dir[2])
    }

    /** 角度归一到 (−π, π]。 */
    fun wrapPi(a: Float): Float {
        val twoPi = (2.0 * Math.PI).toFloat()
        val pi = Math.PI.toFloat()
        var x = a
        while (x > pi) x -= twoPi
        while (x <= -pi) x += twoPi
        return x
    }
}
