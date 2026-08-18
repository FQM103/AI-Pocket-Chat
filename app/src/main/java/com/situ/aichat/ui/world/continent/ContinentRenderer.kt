package com.situ.aichat.ui.world.continent

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.situ.aichat.ui.world.planet.PlanetShaders
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** 一个大区渲染的天空/光照参数（5 停靠色 + 位置 + 辉光 α + warm/haze·区切换 CPU 逐帧 lerp）。 */
internal class SkyParams(
    val colors: FloatArray, // 15 = 5×rgb
    val pos: FloatArray,    // 5
    val glowA: Float,
    val warm: FloatArray,   // 3
    val haze: FloatArray,   // 3
) {
    companion object {
        fun of(style: RegionStyle): SkyParams {
            val colors = FloatArray(15)
            for (i in 0..4) {
                val c = style.sky[i].color
                colors[i * 3] = c[0]; colors[i * 3 + 1] = c[1]; colors[i * 3 + 2] = c[2]
            }
            return SkyParams(colors, FloatArray(5) { style.sky[it].pos }, style.glowA, style.warm.copyOf(), style.haze.copyOf())
        }

        fun lerp(a: SkyParams, b: SkyParams, f: Float): SkyParams {
            fun l(x: FloatArray, y: FloatArray) = FloatArray(x.size) { x[it] + (y[it] - x[it]) * f }
            return SkyParams(l(a.colors, b.colors), l(a.pos, b.pos), a.glowA + (b.glowA - a.glowA) * f, l(a.warm, b.warm), l(a.haze, b.haze))
        }
    }
}

/**
 * 大陆盒景 GLES2 渲染器（W9b 图纸 §2/§4.1·五 pass：背景→星点→lit→水→emis·**禁背面剔除**·水 depthMask
 * false·demo:L340-378）。区切换时 [submitRegion] 缓冲重传（GL 线程 queueEvent）+ 天空 CPU lerp（[setSkyBlend]）。
 * 首帧成功后 [onFirstFrame]（揭幕）；编译/链接失败 → [onGlError]（皆由 GLView 封装为主线程回调·不崩·§5 E14）。
 * GL 上下文重建（onSurfaceCreated 再回调）→ 重编译 + 用 CPU 几何副本重传（§3.8·E14b）。
 */
internal class ContinentRenderer(
    private val camera: ContinentCamera,
    private val worldSeed: Long,
    private val pointScale: Float,
    private val onGlError: () -> Unit,
    private val onFirstFrame: () -> Unit,
) : GLSurfaceView.Renderer {

    @Volatile var reduceMotion: Boolean = false
    @Volatile var staticMode: Boolean = false

    private var glReady = false
    private var aspect = 1f
    private var startNanos = 0L
    private var lastNanos = 0L
    private var firstFrameSent = false

    // 程序
    private var bgProg = 0; private var bgAPos = 0; private var bgUSky = 0; private var bgUSkyPos = 0; private var bgUGlow = 0
    private var starProg = 0; private var starAStar = 0; private var starUTime = 0; private var starUAnim = 0; private var starUScale = 0
    private var litProg = 0; private var waterProg = 0; private var emisProg = 0

    // lit/water/emis 各自 attrib/uniform 句柄（同 C_VS·分程序取）
    private class SceneProg(val prog: Int) {
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aNor = GLES20.glGetAttribLocation(prog, "aNor")
        val aCol = GLES20.glGetAttribLocation(prog, "aCol")
        val uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        val uSun = GLES20.glGetUniformLocation(prog, "uSun")
        val uWarm = GLES20.glGetUniformLocation(prog, "uWarm")
        val uHaze = GLES20.glGetUniformLocation(prog, "uHaze")
    }
    private var litP: SceneProg? = null
    private var waterP: SceneProg? = null
    private var emisP: SceneProg? = null

    // 缓冲
    private var litVbo = 0; private var litCount = 0
    private var waterVbo = 0; private var waterCount = 0
    private var emisVbo = 0; private var emisCount = 0
    private var quadVbo = 0
    private var starVbo = 0

    // CPU 几何副本（上下文重建重传·E14b）
    private var cpuLit: FloatArray? = null
    private var cpuWater: FloatArray? = null
    private var cpuEmis: FloatArray? = null
    private val stars = ContinentGeometry.buildContinentStars(worldSeed)

    // 天空混合
    @Volatile private var skyBlend = 1f
    private var prevSky: SkyParams? = null
    private var nextSky: SkyParams? = null

    private val sun = floatArrayOf(-0.5f, 0.62f, 0.42f) // demo:L263
    private val viewportUnit = 1000f

    /** 提交新区几何 + 天空（GL 线程·queueEvent 调）。首区 blend=1 直达·换区 blend 由 [setSkyBlend] 驱动。 */
    fun submitRegion(data: ContinentGeometryData, sky: SkyParams, first: Boolean) {
        cpuLit = data.lit; cpuWater = data.water; cpuEmis = data.emis
        if (glReady) uploadRegion()
        if (first || prevSky == null) {
            prevSky = sky; nextSky = sky; skyBlend = 1f
        } else {
            prevSky = SkyParams.lerp(prevSky!!, nextSky!!, skyBlend) // 捕获当前显示色为起点
            nextSky = sky; skyBlend = 0f
        }
    }

    /** 天空过渡分数 0→1（区切换 1s·Compose 驱动·§3.7）。 */
    fun setSkyBlend(fraction: Float) { skyBlend = fraction }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            bgProg = link(PlanetShaders.BG_VS, ContinentShaders.C_BG_FS)
            starProg = link(PlanetShaders.STAR_VS, PlanetShaders.STAR_FS)
            litProg = link(ContinentShaders.C_VS, ContinentShaders.C_FS_LIT)
            waterProg = link(ContinentShaders.C_VS, ContinentShaders.C_FS_WATER)
            emisProg = link(ContinentShaders.C_VS, ContinentShaders.C_FS_EMIS)
        } catch (e: RuntimeException) {
            glReady = false
            onGlError()
            return
        }
        bgAPos = GLES20.glGetAttribLocation(bgProg, "aPos")
        bgUSky = GLES20.glGetUniformLocation(bgProg, "uSky[0]")
        bgUSkyPos = GLES20.glGetUniformLocation(bgProg, "uSkyPos[0]")
        bgUGlow = GLES20.glGetUniformLocation(bgProg, "uGlowA")
        starAStar = GLES20.glGetAttribLocation(starProg, "aStar")
        starUTime = GLES20.glGetUniformLocation(starProg, "uTime")
        starUAnim = GLES20.glGetUniformLocation(starProg, "uAnim")
        starUScale = GLES20.glGetUniformLocation(starProg, "uPointScale")
        litP = SceneProg(litProg); waterP = SceneProg(waterProg); emisP = SceneProg(emisProg)

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
        quadVbo = arrayBuffer(floatBuf(quad), quad.size * 4)
        starVbo = arrayBuffer(floatBuf(stars), stars.size * 4)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        startNanos = System.nanoTime()
        lastNanos = startNanos
        glReady = true
        uploadRegion() // 上下文重建后用 CPU 副本重传（首建时副本可能为 null → 待 submitRegion）
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height == 0) 1f else width.toFloat() / height.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!glReady) return

        val now = System.nanoTime()
        val dt = ((now - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastNanos = now
        val frozen = reduceMotion || staticMode
        val uTime = if (frozen) 0f else ((now - startNanos) / 1_000_000_000.0).toFloat()

        camera.integrate(dt, reduceMotion = frozen)
        val cam = camera.snapshot
        val mvp = ContinentMath.continentMvp(cam.yaw, cam.pitch, cam.dist, cam.tx, cam.ty, cam.tz, aspect)
        val sky = effectiveSky()

        // ── ① 背景（关深度）② 星点（关深度写）──
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        if (sky != null) {
            GLES20.glUseProgram(bgProg)
            bindAttrib(quadVbo, bgAPos, 2, 0, 0)
            GLES20.glUniform3fv(bgUSky, 5, sky.colors, 0)
            GLES20.glUniform1fv(bgUSkyPos, 5, sky.pos, 0)
            GLES20.glUniform1f(bgUGlow, sky.glowA)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        }
        GLES20.glUseProgram(starProg)
        bindAttrib(starVbo, starAStar, 4, 0, 0)
        GLES20.glUniform1f(starUTime, uTime)
        GLES20.glUniform1f(starUAnim, if (frozen) 0f else 1f)
        GLES20.glUniform1f(starUScale, pointScale)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, ContinentGeometry.STAR_COUNT)

        // ── ③ lit（深度测试开·禁背面剔除·demo:L341）──
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        if (litCount > 0 && sky != null) drawScene(litP!!, litVbo, litCount, mvp, sky)

        // ── ④ 水（blend·depthMask false·demo:L375-377）──
        if (waterCount > 0 && sky != null) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glDepthMask(false)
            drawScene(waterP!!, waterVbo, waterCount, mvp, sky)
            GLES20.glDepthMask(true)
        }

        // ── ⑤ emis（自发光·demo:L378）──
        if (emisCount > 0 && sky != null) drawScene(emisP!!, emisVbo, emisCount, mvp, sky)

        // 首帧揭幕：几何已上传且真正画出后才触发（避免揭在空场景上）。
        if (!firstFrameSent && litCount > 0 && sky != null) { firstFrameSent = true; onFirstFrame() }
    }

    private fun effectiveSky(): SkyParams? {
        val p = prevSky ?: return null
        val n = nextSky ?: return p
        return if (skyBlend >= 1f) n else SkyParams.lerp(p, n, skyBlend)
    }

    private fun drawScene(sp: SceneProg, vbo: Int, count: Int, mvp: FloatArray, sky: SkyParams) {
        GLES20.glUseProgram(sp.prog)
        bindAttrib(vbo, sp.aPos, 3, 36, 0)
        bindAttrib(vbo, sp.aNor, 3, 36, 12)
        bindAttrib(vbo, sp.aCol, 3, 36, 24)
        GLES20.glUniformMatrix4fv(sp.uMVP, 1, false, mvp, 0)
        if (sp.uSun >= 0) GLES20.glUniform3fv(sp.uSun, 1, sun, 0)
        if (sp.uWarm >= 0) GLES20.glUniform3fv(sp.uWarm, 1, sky.warm, 0)
        if (sp.uHaze >= 0) GLES20.glUniform3fv(sp.uHaze, 1, sky.haze, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
    }

    private fun uploadRegion() {
        cpuLit?.let { litVbo = replaceBuffer(litVbo, it); litCount = it.size / 9 }
        cpuWater?.let { waterVbo = replaceBuffer(waterVbo, it); waterCount = it.size / 9 }
        cpuEmis?.let { emisVbo = replaceBuffer(emisVbo, it); emisCount = it.size / 9 }
    }

    private fun replaceBuffer(old: Int, data: FloatArray): Int {
        if (old != 0) GLES20.glDeleteBuffers(1, intArrayOf(old), 0)
        return arrayBuffer(floatBuf(data), data.size * 4)
    }

    private fun bindAttrib(vbo: Int, loc: Int, size: Int, stride: Int, offset: Int) {
        if (loc < 0) return // 共用 C_VS 时 water/emis 程序会剔除未用的 aNor → 跳过（demo 同款无害）
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, size, GLES20.GL_FLOAT, false, stride, offset)
    }

    private fun arrayBuffer(buffer: FloatBuffer, byteSize: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, byteSize, buffer, GLES20.GL_STATIC_DRAW)
        return ids[0]
    }

    private fun floatBuf(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(sh)
            GLES20.glDeleteShader(sh)
            throw RuntimeException("continent shader compile failed: $log")
        }
        return sh
    }

    private fun link(vs: String, fs: String): Int {
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            throw RuntimeException("continent program link failed: $log")
        }
        return prog
    }
}
