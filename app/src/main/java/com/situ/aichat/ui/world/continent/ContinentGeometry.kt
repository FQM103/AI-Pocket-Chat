package com.situ.aichat.ui.world.continent

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/** 一个大区的三角流几何（[ContinentGeometry.buildRegion] 产出·GL 上传用 Float 交错流·9 分量/顶点）。 */
internal class ContinentGeometryData(
    val lit: FloatArray,
    val water: FloatArray,
    val emis: FloatArray,
)

/**
 * 大陆盒景几何构建（W9b 图纸 §2/§4.1A·demo:L163-237 逐式）：台座四壁 + 地形网格 RES=64 + 水面 +
 * 树拒绝采样 + 城市微簇 + 奇观塔 → lit/water/emis 三流。另建大陆星点 14 颗（[buildContinentStars]）。
 * 纯计算（Double 噪声 + Float 落流）·零 Android / 零 DB·由 Compose 层在 Dispatchers.Default 调用。
 */
internal object ContinentGeometry {

    /** 大陆星点数（demo:L64）。 */
    const val STAR_COUNT = 14

    // 城市微簇 / 奇观塔 / 树 硬编码色（demo:L216,224,228,232-235）。
    private val WALLS = arrayOf(rgb(0xC99A86), rgb(0xB98A6E), rgb(0x8E9AA6))
    private val ROOFS = arrayOf(rgb(0x9A5B3E), rgb(0x8A4E33), rgb(0x5C6B7C))
    private val WINDOW = rgb(0xFFD9A0)
    private val TRUNK = rgb(0x6B5138)
    private val TOWER_A = rgb(0x9A8E80)
    private val TOWER_B = rgb(0x8F8478)

    fun buildRegion(style: RegionStyle, sites: List<ContinentSite>): ContinentGeometryData {
        val lit = TriStream(1 shl 16)
        val water = TriStream(512)
        val emis = TriStream(4096)

        val hw = ContinentMath.S / 2.0                 // 23
        val base = ContinentMath.BASE.toDouble()       // -3.5
        val res = ContinentMath.RES                    // 64
        val step = ContinentMath.S / res.toDouble()    // 0.71875
        val earth = style.earth

        // ── 台座四壁（demo:L190-193·Bad North 式悬浮块·无底）──
        lit.quad(v(-hw, base, hw), v(hw, base, hw), v(hw, 0.0, hw), v(-hw, 0.0, hw), earth)
        lit.quad(v(hw, base, -hw), v(-hw, base, -hw), v(-hw, 0.0, -hw), v(hw, 0.0, -hw), earth)
        lit.quad(v(hw, base, hw), v(hw, base, -hw), v(hw, 0.0, -hw), v(hw, 0.0, hw), earth)
        lit.quad(v(-hw, base, -hw), v(-hw, base, hw), v(-hw, 0.0, hw), v(-hw, 0.0, -hw), earth)

        // pad 中心（heightAt / 避树共用）= 全站位盒景坐标。
        val pads = sites.map { doubleArrayOf(it.x.toDouble(), it.z.toDouble()) }

        // ── 地形网格（demo:L194-204·flat-shading·顶点取 z 递减序→面法线朝上）──
        val h = Array(res + 1) { DoubleArray(res + 1) }
        for (i in 0..res) for (j in 0..res) {
            h[i][j] = ContinentMath.heightAt(-hw + i * step, -hw + j * step, style, pads)
        }
        for (i in 0 until res) for (j in 0 until res) {
            val x0 = -hw + i * step; val z0 = -hw + j * step; val x1 = x0 + step; val z1 = z0 + step
            val a = v(x0, h[i][j + 1], z1)
            val b = v(x1, h[i + 1][j + 1], z1)
            val c = v(x1, h[i + 1][j], z0)
            val d = v(x0, h[i][j], z0)
            val mh = (a[1] + b[1] + c[1] + d[1]) / 4.0
            val ny = step / hypot(step, abs(a[1] - c[1]))   // 近似坡度（判崖壁）
            val col = ContinentMath.colorFor(mh, ny, x0, z0, style)
            lit.tri(a, b, c, col); lit.tri(a, c, d, col)
        }

        // ── 水面（demo:L206·半透满台座·渲染时 alpha 0.84 + depthMask false）──
        water.quad(v(-hw, 0.0, hw), v(hw, 0.0, hw), v(hw, 0.0, -hw), v(-hw, 0.0, -hw), style.water)

        // ── 树（demo:L207-218·确定性拒绝采样·噪声门保丛生·避 pad）──
        val seed = style.seed
        val span = ContinentMath.S - 6.0    // 40
        val treeMax = min(style.snowLine * 0.85, style.amp)
        var count = 0
        for (t in 0 until 1600) {
            if (count >= style.treeN) break
            val x = (ContinentMath.hash2(t * 1.3 + 1.0, seed) - 0.5) * span
            val z = (ContinentMath.hash2(t * 2.7 + 9.0, seed * 3.1) - 0.5) * span
            val th = ContinentMath.heightAt(x, z, style, pads)
            if (th < 0.35 || th > treeMax) continue
            if (ContinentMath.fbm2(x * 0.21 + 3.0, z * 0.21 + 3.0) < 0.48) continue
            if (sites.any { hypot(x - it.x.toDouble(), z - it.z.toDouble()) < 3.4 }) continue
            val sc = 0.7 + ContinentMath.hash2(t.toDouble(), 7.0) * 0.7
            val leaf = style.leafs[floor(ContinentMath.hash2(t.toDouble(), 5.0) * style.leafs.size).toInt()]
            lit.box(x, th, z, 0.22 * sc, style.trunk * sc, 0.22 * sc, TRUNK)
            lit.cone(x, th + style.trunk * sc, z, style.treeR * sc, style.treeH * sc, leaf)
            count++
        }

        // ── 城市微簇（demo:L219-229·楼数按 site.buildingCount）──
        for (site in sites) {
            if (site.isWonder) continue
            val y = style.padH; val n = site.buildingCount
            val sx = site.x.toDouble(); val sz = site.z.toDouble()
            for (k in 0 until n) {
                val ox = (k - (n - 1) / 2.0) * 1.5 + (ContinentMath.hash2(k.toDouble(), sx) - 0.5) * 0.5
                val oz = (ContinentMath.hash2(k * 3.0, sz) - 0.5) * 1.6
                lit.box(sx + ox, y, sz + oz, 1.5, 1.0, 1.3, WALLS[k % 3])
                lit.roof(sx + ox, y + 1.0, sz + oz, 1.7, 0.6, 1.5, ROOFS[k % 3])
                emis.quad(
                    v(sx + ox - 0.2, y + 0.3, sz + oz + 0.66), v(sx + ox + 0.2, y + 0.3, sz + oz + 0.66),
                    v(sx + ox + 0.2, y + 0.7, sz + oz + 0.66), v(sx + ox - 0.2, y + 0.7, sz + oz + 0.66), WINDOW,
                )
            }
        }

        // ── 奇观塔（demo:L230-236·石塔三段 + 顶灯）──
        for (site in sites) {
            if (!site.isWonder) continue
            val wx = site.x.toDouble(); val wz = site.z.toDouble(); val y = style.padH
            lit.box(wx, y, wz, 1.2, 2.6, 1.2, TOWER_A)
            lit.box(wx, y + 2.6, wz, 0.85, 1.9, 0.85, TOWER_B)
            lit.box(wx, y + 4.5, wz, 0.5, 1.3, 0.5, TOWER_A)
            emis.box(wx, y + 5.8, wz, 0.32, 0.32, 0.32, WINDOW)
        }

        return ContinentGeometryData(lit.toFloatArray(), water.toFloatArray(), emis.toFloatArray())
    }

    /**
     * 大陆星点 14 颗（§4.1C·vec4：x_ndc, y_ndc, 基础尺寸 px, 相位 rad）。位置 y 落屏上 26%（demo:L66）·
     * 尺寸 30% 概率 2px 其余 1.4px·相位随机·由世界 [seed] 派生（盐 `xor 0x9B`·与星球星空不同一片）。
     */
    fun buildContinentStars(seed: Long): FloatArray {
        val rnd = Random(seed xor 0x9BL)
        val out = FloatArray(STAR_COUNT * 4)
        for (s in 0 until STAR_COUNT) {
            val size = if (rnd.nextFloat() < 0.3f) 2f else 1.4f
            val x = rnd.nextFloat() * 2f - 1f
            val topFrac = rnd.nextFloat() * 0.26f
            val y = 1f - 2f * topFrac
            val phase = rnd.nextFloat() * 2f * PI.toFloat()
            out[s * 4] = x; out[s * 4 + 1] = y; out[s * 4 + 2] = size; out[s * 4 + 3] = phase
        }
        return out
    }

    private fun v(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
}
