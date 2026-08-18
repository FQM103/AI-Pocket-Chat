package com.situ.aichat.ui.world.interior

import com.situ.aichat.ui.world.continent.TriStream
import com.situ.aichat.ui.world.continent.rgb
import com.situ.aichat.world.stage.WorldWeatherKind
import java.util.Random

/**
 * 室内盒景几何套件（W9d 图纸 §4.2·demo `interior-3d-demo.html` 逐值·§9 锁死）。
 *
 * 提供：材质 11 色 + 十间共用房壳模数 [buildShell]（per-间调色）+ 窗景（昼/夜·§4.2D）[buildSky] +
 * 降水（雨26/雪34·§4.2D）[buildPrecip] + 家具 kit 基元（demo:L207-219 + §4.2B 新基元）。布局文件调本套件落各间几何。
 * 原语 [TriStream]/[rgb] 复用自 continent 包（import·对 9b/9c 锁死区零 diff·DRY 见 §11）。坐标全 Double（= demo float64）。
 */
internal object InteriorKit {

    // ── 材质 11 色（demo:L131-134·§4.2A）──
    val WOOD_D = rgb(0x6E5238)
    val WOOD = rgb(0x8A6B4E)
    val WOOD_L = rgb(0x9C7D5C)
    val CREAM = rgb(0xEFE6D8)
    val CLAY = rgb(0xC99A86)
    val CLAY_D = rgb(0xB07E68)
    val DARKCLAY = rgb(0x3A322C)
    val MOSS = rgb(0x7E926E)
    val LAMP = rgb(0xFFD9A0)
    val NIGHT = rgb(0x121A2E)
    val BRASS = rgb(0xC2A26B)

    // ── 房壳模数（demo:L137·十间共用·§9 锁死）──
    const val RX0 = -5.2
    const val RX1 = 5.2
    const val RZ0 = -3.4
    const val RZ1 = 3.4
    const val WH = 3.3
    const val WT = 0.16

    private fun v(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)

    // ── 房壳（demo:L137-166·per-间调色 plankA/plankB/skirt·其余固定）──
    fun buildShell(lit: TriStream, plankA: DoubleArray, plankB: DoubleArray, skirt: DoubleArray) {
        // 地板 13 条 0.8 宽沿 x 交替（px 奇=plankA·偶=plankB·demo:L139-141）。
        for (px in 0..12) {
            val x0 = RX0 + px * 0.8
            val x1 = minOf(x0 + 0.8, RX1)
            lit.quad(v(x0, 0.0, RZ1), v(x1, 0.0, RZ1), v(x1, 0.0, RZ0), v(x0, 0.0, RZ0), if (px % 2 == 1) plankA else plankB)
        }
        // 后墙：裙线 + 上段 CREAM（demo:L148-149）。
        lit.box(0.0, 0.0, RZ0 - WT / 2 + 0.001, 10.4, 1.1, WT, skirt)
        lit.box(0.0, 1.1, RZ0 - WT / 2 + 0.001, 10.4, WH - 1.1, WT, CREAM)
        // 左墙开窗四段（窗洞 z∈[-1.5,1.1] y∈[0.95,2.55]·demo:L151-155）。
        fun lwall(z0: Double, z1: Double, y0: Double, y1: Double) =
            lit.box(RX0 - WT / 2 + 0.001, y0, (z0 + z1) / 2, WT, y1 - y0, z1 - z0, CREAM)
        lwall(RZ0, RZ1, 0.0, 0.95)
        lwall(RZ0, RZ1, 2.55, WH)
        lwall(RZ0, -1.5, 0.95, 2.55)
        lwall(1.1, RZ1, 0.95, 2.55)
        lit.box(RX0 - WT / 2 + 0.001, 0.0, RZ0 + 0.55, WT, 1.1, 1.1, skirt) // 裙线补角（demo:L156）
        // 窗框五杆 + 横挺（demo:L158-161）。
        fun bar(cy: Double, cz: Double, h: Double, d: Double) = lit.box(RX0 + 0.02, cy, cz, 0.1, h, d, WOOD_D)
        bar(0.9, -0.2, 0.1, 2.7); bar(2.5, -0.2, 0.1, 2.7)
        bar(0.95, -1.52, 1.6, 0.1); bar(0.95, 1.12, 1.6, 0.1); bar(0.95, -0.2, 1.6, 0.09)
        lit.box(RX0 + 0.02, 1.72, -0.2, 0.09, 0.08, 2.6, WOOD_D)
        // 窗台 + 台上盆栽（demo:L163-166）。
        lit.box(RX0 + 0.14, 0.86, -0.2, 0.34, 0.09, 2.8, WOOD)
        lit.box(RX0 + 0.16, 0.95, 0.7, 0.2, 0.16, 0.2, rgb(0xA9563F))
        lit.tri(v(RX0 + 0.05, 1.11, 0.82), v(RX0 + 0.28, 1.11, 0.82), v(RX0 + 0.16, 1.42, 0.7), MOSS)
        lit.tri(v(RX0 + 0.28, 1.11, 0.58), v(RX0 + 0.05, 1.11, 0.58), v(RX0 + 0.16, 1.42, 0.7), MOSS)
    }

    // ── 窗景（§4.2D·夜=demo:L169-174 逐值·昼=作者落值）──
    fun buildSky(emis: TriStream, night: Boolean) {
        if (night) {
            emis.quad(v(-9.5, 0.0, -6.0), v(-9.5, 0.0, 6.0), v(-9.5, 4.6, 6.0), v(-9.5, 4.6, -6.0), NIGHT) // 夜幕
            for (b in HOUSE_SHADOWS) emis.box(b[0], 0.0, b[1], b[2], b[3], 1.0, rgb(0x18213A)) // 屋影三
            for (w in FAR_WINDOWS) { // 远窗灯四
                emis.quad(v(w[0], w[1], w[2] - 0.09), v(w[0], w[1], w[2] + 0.09), v(w[0], w[1] + 0.14, w[2] + 0.09), v(w[0], w[1] + 0.14, w[2] - 0.09), rgb(0xE8B87E))
            }
        } else {
            emis.quad(v(-9.5, 2.2, -6.0), v(-9.5, 2.2, 6.0), v(-9.5, 4.6, 6.0), v(-9.5, 4.6, -6.0), rgb(0x9FB8CC)) // 天幕上段
            emis.quad(v(-9.5, 0.0, -6.0), v(-9.5, 0.0, 6.0), v(-9.5, 2.2, 6.0), v(-9.5, 2.2, -6.0), rgb(0xC6D5DE)) // 天幕下段
            for (b in HOUSE_SHADOWS) emis.box(b[0], 0.0, b[1], b[2], b[3], 1.0, rgb(0x4E6076)) // 屋影三·昼色
            // 昼无远窗灯（§4.2D）
        }
    }

    // 屋影三（cx,cz,sx,h·§4.2D）·远窗灯四（cx,cy,cz·§4.2D）。
    private val HOUSE_SHADOWS = arrayOf(
        doubleArrayOf(-7.6, -1.9, 1.2, 1.1), doubleArrayOf(-8.2, 0.3, 1.5, 1.6), doubleArrayOf(-7.2, 1.8, 1.0, 0.9),
    )
    private val FAR_WINDOWS = arrayOf(
        doubleArrayOf(-7.05, 0.95, -1.9), doubleArrayOf(-7.65, 1.15, 0.15), doubleArrayOf(-7.65, 0.7, 0.5), doubleArrayOf(-6.68, 0.9, 1.75),
    )

    // ── 降水（§4.2D·雨26 盐0x9D·雪34 盐0x9E·aCol=(ph,ry,0)·java.util.Random 顺序流 r1=rz r2=rx r3=ph r4=ry）──
    fun buildPrecip(precip: TriStream, weather: WorldWeatherKind) {
        when (weather) {
            WorldWeatherKind.RAIN -> emitPrecip(precip, Random(0x9DL), count = 26, w = 0.012, h = 0.4)
            WorldWeatherKind.SNOW -> emitPrecip(precip, Random(0x9EL), count = 34, w = 0.055, h = 0.055)
            WorldWeatherKind.CLEAR -> Unit
        }
    }

    private fun emitPrecip(precip: TriStream, rnd: Random, count: Int, w: Double, h: Double) {
        repeat(count) {
            val rz = -2.2 + rnd.nextDouble() * 4.0  // r1
            val rx = -5.9 - rnd.nextDouble() * 2.6  // r2
            val ph = rnd.nextDouble() * 3.4         // r3
            val ry = rnd.nextDouble() * 3.4         // r4
            precip.quad(v(rx, 0.0, rz), v(rx, 0.0, rz + w), v(rx, h, rz + w), v(rx, h, rz), doubleArrayOf(ph, ry, 0.0))
        }
    }

    // ─────────────────── 家具 kit 基元（demo:L207-219 + §4.2B 新基元·逐值）───────────────────

    /** 桌（demo:L208-209）：腿 0.12×0.72×0.12 WOOD_D + 面 s×0.07×s WOOD_L。 */
    fun table(lit: TriStream, cx: Double, cz: Double, s: Double) {
        lit.box(cx, 0.0, cz, 0.12, 0.72, 0.12, WOOD_D)
        lit.box(cx, 0.72, cz, s, 0.07, s, WOOD_L)
    }

    /** 椅（demo:L210-216·rot 0/1/2/3 背在 +z/−z/−x/+x·b=0.21·偏 0.03）。 */
    fun chair(lit: TriStream, cx: Double, cz: Double, rot: Int) {
        lit.box(cx, 0.0, cz, 0.42, 0.46, 0.42, WOOD)
        val b = 0.21
        when (rot) {
            0 -> lit.box(cx, 0.46, cz + b - 0.03, 0.42, 0.5, 0.07, WOOD)
            1 -> lit.box(cx, 0.46, cz - b + 0.03, 0.42, 0.5, 0.07, WOOD)
            2 -> lit.box(cx - b + 0.03, 0.46, cz, 0.07, 0.5, 0.42, WOOD)
            3 -> lit.box(cx + b - 0.03, 0.46, cz, 0.07, 0.5, 0.42, WOOD)
        }
    }

    /** 杯（demo:L217）：0.11×0.09×0.11 CREAM。 */
    fun cup(lit: TriStream, cx: Double, cz: Double, y: Double) = lit.box(cx, y, cz, 0.11, 0.09, 0.11, CREAM)

    /** 烛（demo:L218-219）：座 0.1×0.07×0.1 #B9AFA6 + 焰 emis 0.045×0.06×0.045 LAMP @y+0.07。 */
    fun candle(lit: TriStream, emis: TriStream, cx: Double, cz: Double, y: Double) {
        lit.box(cx, y, cz, 0.1, 0.07, 0.1, rgb(0xB9AFA6))
        emis.box(cx, y + 0.07, cz, 0.045, 0.06, 0.045, LAMP)
    }

    /** 挂画（demo:L202-204）：框 WOOD_D 0.06 深 @z=RZ0+0.05 + 画心内缩 0.07 @z=RZ0+0.09。 */
    fun art(lit: TriStream, cx: Double, cy: Double, w: Double, h: Double, inner: DoubleArray) {
        lit.box(cx, cy, RZ0 + 0.05, w, h, 0.06, WOOD_D)
        lit.quad(
            v(cx - w / 2 + 0.07, cy + 0.07, RZ0 + 0.09), v(cx + w / 2 - 0.07, cy + 0.07, RZ0 + 0.09),
            v(cx + w / 2 - 0.07, cy + h - 0.07, RZ0 + 0.09), v(cx - w / 2 + 0.07, cy + h - 0.07, RZ0 + 0.09), inner,
        )
    }

    /** 吊灯（demo:L244-250）：杆 + 罩 + 芯 emis + 地面光池 r=poolR #AE9068。 */
    fun pendant(lit: TriStream, emis: TriStream, cx: Double, cz: Double, poolR: Double) {
        lit.box(cx, 2.42, cz, 0.05, WH - 2.42, 0.05, DARKCLAY)
        lit.box(cx, 2.18, cz, 0.5, 0.26, 0.5, DARKCLAY)
        emis.box(cx, 2.06, cz, 0.2, 0.14, 0.2, LAMP)
        pool(lit, cx, cz, poolR, rgb(0xAE9068))
    }

    /** 地面光池 quad（y0.016·pendant/floorLamp/hearth 共用·色由调用方给）。 */
    private fun pool(lit: TriStream, cx: Double, cz: Double, r: Double, col: DoubleArray) =
        lit.quad(v(cx - r, 0.016, cz + r), v(cx + r, 0.016, cz + r), v(cx + r, 0.016, cz - r), v(cx - r, 0.016, cz - r), col)

    /** 角落盆栽（demo:L231-237）：盆 #A9563F + 三层四面锥 MOSS [(y0.42,h0.72,r0.62),(0.94,0.5,0.44),(1.3,0.34,0.3)]。 */
    fun cornerPlant(lit: TriStream, cx: Double, cz: Double) {
        lit.box(cx, 0.0, cz, 0.42, 0.42, 0.42, rgb(0xA9563F))
        for (t in arrayOf(doubleArrayOf(0.42, 0.72, 0.62), doubleArrayOf(0.94, 0.5, 0.44), doubleArrayOf(1.3, 0.34, 0.3))) {
            val y = t[0]; val h = t[1]; val r = t[2]
            lit.tri(v(cx - r, y, cz + r), v(cx + r, y, cz + r), v(cx, y + h, cz), MOSS)
            lit.tri(v(cx + r, y, cz + r), v(cx + r, y, cz - r), v(cx, y + h, cz), MOSS)
            lit.tri(v(cx + r, y, cz - r), v(cx - r, y, cz - r), v(cx, y + h, cz), MOSS)
            lit.tri(v(cx - r, y, cz - r), v(cx - r, y, cz + r), v(cx, y + h, cz), MOSS)
        }
    }

    /** 沙发（§4.2B）：底 + 背 @y0.5,z−0.39 + 双扶手 @x±(w/2−0.12) + 坐垫 @y0.5。 */
    fun sofa(lit: TriStream, cx: Double, cz: Double, w: Double) {
        lit.box(cx, 0.0, cz, w, 0.5, 1.0, CLAY)
        lit.box(cx, 0.5, cz - 0.39, w, 0.55, 0.22, CLAY)
        lit.box(cx - (w / 2 - 0.12), 0.0, cz, 0.24, 0.62, 1.0, WOOD)
        lit.box(cx + (w / 2 - 0.12), 0.0, cz, 0.24, 0.62, 1.0, WOOD)
        lit.box(cx, 0.5, cz, w - 0.5, 0.1, 0.8, rgb(0xB9AFA6))
    }

    /** 灶（§4.2B）：灶体 + 台面 @y1.0 + 汤锅 @y1.08,x−0.5。 */
    fun stove(lit: TriStream, cx: Double, cz: Double) {
        lit.box(cx, 0.0, cz, 2.2, 1.0, 0.9, rgb(0xA9563F))
        lit.box(cx, 1.0, cz, 2.3, 0.08, 1.0, DARKCLAY)
        lit.box(cx - 0.5, 1.08, cz, 0.5, 0.35, 0.5, rgb(0x8E8377))
    }

    /** 圆凳（§4.2B）：0.38×0.42×0.38 WOOD。 */
    fun stool(lit: TriStream, cx: Double, cz: Double) = lit.box(cx, 0.0, cz, 0.38, 0.42, 0.38, WOOD)

    /** 条凳（§4.2B）：w×0.42×0.5 #8A6B4E。 */
    fun bench(lit: TriStream, cx: Double, cz: Double, w: Double) = lit.box(cx, 0.0, cz, w, 0.42, 0.5, rgb(0x8A6B4E))

    /** 书桌（§4.2B）：面 1.4×0.07×0.7 WOOD_L @y0.75 + 双腿板 @x±0.6。 */
    fun desk(lit: TriStream, cx: Double, cz: Double) {
        lit.box(cx, 0.75, cz, 1.4, 0.07, 0.7, WOOD_L)
        lit.box(cx - 0.6, 0.0, cz, 0.1, 0.75, 0.7, WOOD_D)
        lit.box(cx + 0.6, 0.0, cz, 0.1, 0.75, 0.7, WOOD_D)
    }

    /** 落地灯（§4.2B）：杆 + 罩 @y1.7 + 芯 emis @y1.62 + 光池 r0.9（#AE9068·同 pendant 暖池）。 */
    fun floorLamp(lit: TriStream, emis: TriStream, cx: Double, cz: Double) {
        lit.box(cx, 0.0, cz, 0.07, 1.7, 0.07, DARKCLAY)
        lit.box(cx, 1.7, cz, 0.4, 0.3, 0.4, CREAM)
        emis.box(cx, 1.62, cz, 0.16, 0.1, 0.16, LAMP)
        pool(lit, cx, cz, 0.9, rgb(0xAE9068))
    }

    /** 火塘（§4.2B）：石圈八块(环 r0.9·45°) + 火 emis + 吊壶 + 三脚杆×2 @x±0.55 + 火光池 r1.5 #B87A4E。 */
    fun hearth(lit: TriStream, emis: TriStream, cx: Double, cz: Double) {
        for (k in 0 until 8) {
            val a = Math.toRadians(k * 45.0)
            lit.box(cx + 0.9 * Math.cos(a), 0.0, cz + 0.9 * Math.sin(a), 0.34, 0.24, 0.34, rgb(0x8A8072))
        }
        emis.box(cx, 0.1, cz, 0.4, 0.3, 0.4, rgb(0xFFB56B))
        lit.box(cx, 1.5, cz, 0.4, 0.4, 0.4, rgb(0x4A4038))
        lit.box(cx - 0.55, 0.0, cz, 0.06, 1.5, 0.06, DARKCLAY)
        lit.box(cx + 0.55, 0.0, cz, 0.06, 1.5, 0.06, DARKCLAY)
        pool(lit, cx, cz, 1.5, rgb(0xB87A4E))
    }

    /** 窑口（§4.2B）：emis w×h×0.1 #FFB56B @y。 */
    fun kilnMouth(emis: TriStream, cx: Double, y: Double, cz: Double, w: Double, h: Double) =
        emis.box(cx, y, cz, w, h, 0.1, rgb(0xFFB56B))

    /**
     * 展架（§4.2B）：架体 WOOD_D（背板 + 侧板 + 每 row 层板）+ 每 row 内容小盒（色列 [cols] 逐间给·轮换）。
     * 内容盒数按 w 取（≥1·每 0.4 宽一盒）·尺寸 0.22×0.3×(d×0.5)（作者框架内落值·装机复审·§11）。
     */
    fun shelfUnit(lit: TriStream, cx: Double, cz: Double, w: Double, h: Double, d: Double, rows: List<Double>, cols: List<DoubleArray>) {
        lit.box(cx, 0.0, cz - d / 2 + 0.05, w, h, 0.1, WOOD_D) // 背板
        lit.box(cx - (w / 2 - 0.05), 0.0, cz, 0.1, h, d, WOOD_D) // 左侧板
        lit.box(cx + (w / 2 - 0.05), 0.0, cz, 0.1, h, d, WOOD_D) // 右侧板
        val n = maxOf(1, (w / 0.4).toInt())
        var ci = 0
        for (row in rows) {
            lit.box(cx, row, cz, w, 0.06, d, WOOD_D) // 层板
            for (i in 0 until n) {
                val bx = cx - w / 2 + w * (i + 0.5) / n
                lit.box(bx, row + 0.06, cz, 0.22, 0.3, d * 0.5, cols[ci % cols.size])
                ci++
            }
        }
    }

    /** 柜台（§4.2B）：身 w×0.95×d + 面 (w+0.2)×0.08×(d+0.15) @y0.95。 */
    fun counterUnit(lit: TriStream, cx: Double, cz: Double, w: Double, d: Double, bodyCol: DoubleArray, topCol: DoubleArray) {
        lit.box(cx, 0.0, cz, w, 0.95, d, bodyCol)
        lit.box(cx, 0.95, cz, w + 0.2, 0.08, d + 0.15, topCol)
    }

    /** 摊桌（§4.2B）：身 1.6×0.85×0.9 WOOD + 面 1.7×0.06×1.0 WOOD_L @y0.85。 */
    fun stallTable(lit: TriStream, cx: Double, cz: Double) {
        lit.box(cx, 0.0, cz, 1.6, 0.85, 0.9, WOOD)
        lit.box(cx, 0.85, cz, 1.7, 0.06, 1.0, WOOD_L)
    }

    /** 通用 lit box 透传（布局表零散摆件用·避免布局文件再 import TriStream 细节）。 */
    fun litBox(lit: TriStream, cx: Double, y0: Double, cz: Double, sx: Double, h: Double, sz: Double, col: DoubleArray) =
        lit.box(cx, y0, cz, sx, h, sz, col)

    /** 通用 emis box 透传。 */
    fun emisBox(emis: TriStream, cx: Double, y0: Double, cz: Double, sx: Double, h: Double, sz: Double, col: DoubleArray) =
        emis.box(cx, y0, cz, sx, h, sz, col)

    /** 通用 lit quad 透传（毯/黑板粉痕等）。 */
    fun litQuad(lit: TriStream, a: DoubleArray, b: DoubleArray, c: DoubleArray, dd: DoubleArray, col: DoubleArray) =
        lit.quad(a, b, c, dd, col)

    /** 通用 emis quad 透传（压力表/粉痕/画心发光等）。 */
    fun emisQuad(emis: TriStream, a: DoubleArray, b: DoubleArray, c: DoubleArray, dd: DoubleArray, col: DoubleArray) =
        emis.quad(a, b, c, dd, col)

    /** 通用 lit tri 透传（盆栽锥叶）。 */
    fun litTri(lit: TriStream, a: DoubleArray, b: DoubleArray, c: DoubleArray, col: DoubleArray) = lit.tri(a, b, c, col)

    /** 坐标构造（布局文件共用）。 */
    fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
}
