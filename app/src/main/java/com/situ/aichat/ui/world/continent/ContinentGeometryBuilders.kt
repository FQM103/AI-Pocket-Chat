package com.situ.aichat.ui.world.continent

import kotlin.math.sqrt

/**
 * flat-shading 三角流原语（W9b 图纸 §2·demo:L96-120 逐式移植·顶点序/法线同式）。写入 9 分量交错流
 * `(x,y,z, nx,ny,nz, r,g,b)`。位置/颜色以 **Double** 传入（= demo JS float64·法线在 float64 算），
 * 落 [TriStream] 时窄化 Float（= demo `new Float32Array(data)` 上传语义）。
 */
internal class TriStream(initialCapacity: Int = 4096) {
    private var buf = FloatArray(initialCapacity)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    /** 一个顶点（位置 Double + 法线 Double + 颜色 Double → Float 窄化）。 */
    private fun vertex(px: Double, py: Double, pz: Double, nx: Double, ny: Double, nz: Double, col: DoubleArray) {
        ensure(9)
        buf[size++] = px.toFloat(); buf[size++] = py.toFloat(); buf[size++] = pz.toFloat()
        buf[size++] = nx.toFloat(); buf[size++] = ny.toFloat(); buf[size++] = nz.toFloat()
        buf[size++] = col[0].toFloat(); buf[size++] = col[1].toFloat(); buf[size++] = col[2].toFloat()
    }

    /** 顶点数（= size/9）。 */
    val vertexCount: Int get() = size / 9

    fun toFloatArray(): FloatArray = buf.copyOf(size)

    // ── 原语（demo:L96-120·全 Double 传入）──

    /** 三角面（demo:L96-101·面法线 = (b-a)×(c-a) 归一·三顶点共用）。 */
    fun tri(a: DoubleArray, b: DoubleArray, c: DoubleArray, col: DoubleArray) {
        val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
        val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz).let { if (it == 0.0) 1.0 else it } // demo `Math.hypot(nx,ny,nz)||1`
        nx /= l; ny /= l; nz /= l
        vertex(a[0], a[1], a[2], nx, ny, nz, col)
        vertex(b[0], b[1], b[2], nx, ny, nz, col)
        vertex(c[0], c[1], c[2], nx, ny, nz, col)
    }

    /** 四边面 = 两三角（demo:L102）。 */
    fun quad(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray, col: DoubleArray) {
        tri(a, b, c, col); tri(a, c, d, col)
    }

    /** 立方盒（无底面·demo:L103-109·顶 + 前后左右四壁）。 */
    fun box(cx: Double, y0: Double, cz: Double, sx: Double, h: Double, sz: Double, col: DoubleArray) {
        val x0 = cx - sx / 2; val x1 = cx + sx / 2; val z0 = cz - sz / 2; val z1 = cz + sz / 2; val y1 = y0 + h
        quad(v(x0, y1, z1), v(x1, y1, z1), v(x1, y1, z0), v(x0, y1, z0), col)
        quad(v(x0, y0, z1), v(x1, y0, z1), v(x1, y1, z1), v(x0, y1, z1), col)
        quad(v(x1, y0, z0), v(x0, y0, z0), v(x0, y1, z0), v(x1, y1, z0), col)
        quad(v(x1, y0, z1), v(x1, y0, z0), v(x1, y1, z0), v(x1, y1, z1), col)
        quad(v(x0, y0, z0), v(x0, y0, z1), v(x0, y1, z1), v(x0, y1, z0), col)
    }

    /** 双坡屋顶（demo:L110-115·两坡面 + 两山墙三角）。 */
    fun roof(cx: Double, y0: Double, cz: Double, sx: Double, h: Double, sz: Double, col: DoubleArray) {
        val x0 = cx - sx / 2; val x1 = cx + sx / 2; val z0 = cz - sz / 2; val z1 = cz + sz / 2; val y1 = y0 + h
        quad(v(x0, y0, z1), v(x1, y0, z1), v(x1, y1, cz), v(x0, y1, cz), col)
        quad(v(x1, y0, z0), v(x0, y0, z0), v(x0, y1, cz), v(x1, y1, cz), col)
        tri(v(x0, y0, z0), v(x0, y0, z1), v(x0, y1, cz), col)
        tri(v(x1, y0, z1), v(x1, y0, z0), v(x1, y1, cz), col)
    }

    /** 四面锥（树冠·demo:L116-120）。 */
    fun cone(cx: Double, y: Double, cz: Double, r: Double, h: Double, col: DoubleArray) {
        tri(v(cx - r, y, cz + r), v(cx + r, y, cz + r), v(cx, y + h, cz), col)
        tri(v(cx + r, y, cz + r), v(cx + r, y, cz - r), v(cx, y + h, cz), col)
        tri(v(cx + r, y, cz - r), v(cx - r, y, cz - r), v(cx, y + h, cz), col)
        tri(v(cx - r, y, cz - r), v(cx - r, y, cz + r), v(cx, y + h, cz), col)
    }

    private fun v(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
}

/** 颜色 hex → Double RGB 0..1（= demo `C(hex)`·建筑/树干等硬编码色用）。 */
internal fun rgb(hex: Int): DoubleArray = doubleArrayOf(
    ((hex shr 16) and 255) / 255.0,
    ((hex shr 8) and 255) / 255.0,
    (hex and 255) / 255.0,
)
