package com.situ.aichat.ui.world.planet

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 星球场景的顶点数据（W9a 图纸 §2）：球网格（demo:L66-75）+ 全屏背景 quad + 26 星点。零 Android 依赖。
 * 球网格双精度中间量后窄化 Float（与 demo `Math.*` → `Float32Array` 同链）；顶点着色器会 `normalize(vLocal)`，
 * 微精度不影响。星点位置/相位由**世界种子**确定性派生（图纸 §4.1B·同一世界恒同一片星空）。
 */
internal object PlanetGeometry {

    /** 球网格纬/经细分（demo:L66·锁死·图纸 §9）。 */
    const val LAT = 48
    const val LON = 72

    /** 星点数（demo:L49）。 */
    const val STAR_COUNT = 26

    /** 球网格顶点（xyz 单位球）+ 三角索引（demo:L66-75）。 */
    class Sphere(val positions: FloatArray, val indices: ShortArray)

    fun buildSphere(): Sphere {
        val pos = ArrayList<Float>((LAT + 1) * (LON + 1) * 3)
        for (i in 0..LAT) {
            val th = i.toDouble() / LAT * PI
            val sT = sin(th); val cT = cos(th)
            for (j in 0..LON) {
                val ph = j.toDouble() / LON * 2.0 * PI
                pos.add((sT * cos(ph)).toFloat())
                pos.add(cT.toFloat())
                pos.add((sT * sin(ph)).toFloat())
            }
        }
        val idx = ArrayList<Short>(LAT * LON * 6)
        for (i in 0 until LAT) {
            for (j in 0 until LON) {
                val a = i * (LON + 1) + j
                val b = a + LON + 1
                idx.add(a.toShort()); idx.add(b.toShort()); idx.add((a + 1).toShort())
                idx.add(b.toShort()); idx.add((b + 1).toShort()); idx.add((a + 1).toShort())
            }
        }
        return Sphere(pos.toFloatArray(), idx.toShortArray())
    }

    /** 全屏背景 quad（NDC·两三角·vec2 attribute·CCW 前面）。 */
    val backgroundQuad: FloatArray = floatArrayOf(
        -1f, -1f, 1f, -1f, 1f, 1f,
        -1f, -1f, 1f, 1f, -1f, 1f,
    )

    /**
     * 26 星点顶点（vec4：x_ndc, y_ndc, 基础点尺寸 px, 相位 rad）·由 [seed] 确定性派生。
     * 位置：x 全屏、y 落屏上 62%（demo:L51 `top = rand*62%`）；尺寸 30% 为 2px 其余 1.4px（demo:L50）；
     * 相位随机（demo:L52 `animationDelay`·此处折成正弦相位，闪烁频率见星点着色器 4.5s）。
     */
    fun buildStars(seed: Long): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(STAR_COUNT * 4)
        for (s in 0 until STAR_COUNT) {
            val size = if (rnd.nextFloat() < 0.3f) 2f else 1.4f
            val x = rnd.nextFloat() * 2f - 1f
            val topFrac = rnd.nextFloat() * 0.62f
            val y = 1f - 2f * topFrac
            val phase = rnd.nextFloat() * 2f * PI.toFloat()
            out[s * 4] = x
            out[s * 4 + 1] = y
            out[s * 4 + 2] = size
            out[s * 4 + 3] = phase
        }
        return out
    }
}
