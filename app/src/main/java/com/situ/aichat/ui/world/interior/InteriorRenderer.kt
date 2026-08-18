package com.situ.aichat.ui.world.interior

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.situ.aichat.ui.world.continent.ContinentShaders
import com.situ.aichat.ui.world.planet.PlanetShaders
import com.situ.aichat.world.stage.WorldWeatherKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 室内盒景 GLES2 渲染器（W9d 图纸 §4.2E·四 pass：背景 → lit → emis → precip·**禁背面剔除**·blend 开·demo:L364-388）。
 *
 * lit 用 [InteriorShaders.I_FS_LIT]（昼夜差 = uniform·[InteriorShaders.NIGHT]/[InteriorShaders.DAY]）；emis 闪烁；
 * precip 用 I_VS_RAIN/I_FS_RAIN（速度/透明/色 uniform·雨/雪变参）；bg = 页底渐变 + 暖光溢出。装载 [submitInterior]
 * GL 线程上传。首帧成功后 [onFirstFrame]；编译/链接失败 → [onGlError]（统一走 WorldScreen.onSceneGlError·§5 E14）。
 * 上下文重建 → 重编译 + CPU 几何副本重传（§3.6）。reduce/static → uTime 冻 0（emis/precip 停）。
 */
internal class InteriorRenderer(
    private val camera: InteriorCamera,
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

    // bg 程序
    private var bgProg = 0; private var bgAPos = 0; private var bgUSky = 0; private var bgUSkyPos = 0; private var bgUSpill = 0

    /** 通用交错流程序句柄（C_VS / I_VS_RAIN 共用 attrib 名·各 FS 的 uniform 分别取）。 */
    private class Prog(val prog: Int) {
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aNor = GLES20.glGetAttribLocation(prog, "aNor")
        val aCol = GLES20.glGetAttribLocation(prog, "aCol")
        val uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        val uTime = GLES20.glGetUniformLocation(prog, "uTime")
        // lit 专用
        val uWarm = GLES20.glGetUniformLocation(prog, "uWarm")
        val uCool = GLES20.glGetUniformLocation(prog, "uCool")
        val uAmbient = GLES20.glGetUniformLocation(prog, "uAmbient")
        val uWarmW = GLES20.glGetUniformLocation(prog, "uWarmW")
        val uCoolW = GLES20.glGetUniformLocation(prog, "uCoolW")
        val uCoolCol = GLES20.glGetUniformLocation(prog, "uCoolCol")
        val uFog = GLES20.glGetUniformLocation(prog, "uFog")
        // precip 专用
        val uFall = GLES20.glGetUniformLocation(prog, "uFall")
        val uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha")
        val uPrecipCol = GLES20.glGetUniformLocation(prog, "uPrecipCol")
    }
    private var litP: Prog? = null
    private var emisP: Prog? = null
    private var precipP: Prog? = null

    private var litVbo = 0; private var litCount = 0
    private var emisVbo = 0; private var emisCount = 0
    private var precipVbo = 0; private var precipCount = 0
    private var quadVbo = 0

    private var cpuLit: FloatArray? = null
    private var cpuEmis: FloatArray? = null
    private var cpuPrecip: FloatArray? = null

    @Volatile private var lighting: InteriorShaders.Lighting = InteriorShaders.NIGHT
    @Volatile private var precipFall = 3.2f
    @Volatile private var precipAlpha = 0.35f
    @Volatile private var precipCol = InteriorShaders.RAIN_COL
    @Volatile private var bgColors = FloatArray(9)
    private var ready = false

    /** 提交室内几何 + 昼夜/天气参数（GL 线程·queueEvent 调）。 */
    fun submitInterior(data: InteriorData) {
        cpuLit = data.geometry.lit; cpuEmis = data.geometry.emis; cpuPrecip = data.geometry.precip
        lighting = if (data.night) InteriorShaders.NIGHT else InteriorShaders.DAY
        bgColors = flattenStops(lighting.bgStops)
        when (data.weather) {
            WorldWeatherKind.RAIN -> { precipFall = 3.2f; precipAlpha = 0.35f; precipCol = InteriorShaders.RAIN_COL }
            WorldWeatherKind.SNOW -> { precipFall = 0.55f; precipAlpha = 0.85f; precipCol = InteriorShaders.SNOW_COL }
            WorldWeatherKind.CLEAR -> Unit
        }
        ready = true
        if (glReady) uploadGeometry()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            bgProg = link(PlanetShaders.BG_VS, InteriorShaders.I_BG_FS)
            litP = Prog(link(ContinentShaders.C_VS, InteriorShaders.I_FS_LIT))
            emisP = Prog(link(ContinentShaders.C_VS, InteriorShaders.I_FS_EMIS))
            precipP = Prog(link(InteriorShaders.I_VS_RAIN, InteriorShaders.I_FS_RAIN))
        } catch (e: RuntimeException) {
            glReady = false
            onGlError()
            return
        }
        bgAPos = GLES20.glGetAttribLocation(bgProg, "aPos")
        bgUSky = GLES20.glGetUniformLocation(bgProg, "uSky[0]")
        bgUSkyPos = GLES20.glGetUniformLocation(bgProg, "uSkyPos[0]")
        bgUSpill = GLES20.glGetUniformLocation(bgProg, "uSpillA")

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
        quadVbo = arrayBuffer(floatBuf(quad), quad.size * 4)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        startNanos = System.nanoTime()
        lastNanos = startNanos
        glReady = true
        uploadGeometry()
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
        val mvp = InteriorMath.interiorMvp(cam.yaw, cam.pitch, cam.dist, cam.tx, cam.ty, cam.tz, aspect)
        if (!ready) return

        // ① 背景（关深度·页底渐变 + 暖光溢出·demo:L388 前）
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(bgProg)
        bindAttrib(quadVbo, bgAPos, 2, 0, 0)
        GLES20.glUniform3fv(bgUSky, 3, bgColors, 0)
        GLES20.glUniform1fv(bgUSkyPos, 3, InteriorShaders.BG_POS, 0)
        GLES20.glUniform1f(bgUSpill, lighting.spillA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

        // ② lit（深度开·禁剔除·双向光·demo:L388）
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        if (litCount > 0) drawLit(mvp)
        // ③ emis（自发光闪烁）
        if (emisCount > 0) drawEmis(mvp, uTime)
        // ④ precip（雨/雪·回绕）
        if (precipCount > 0) drawPrecip(mvp, uTime)

        if (!firstFrameSent && litCount > 0) { firstFrameSent = true; onFirstFrame() }
    }

    private fun drawLit(mvp: FloatArray) {
        val p = litP!!
        GLES20.glUseProgram(p.prog)
        bindStream(p, litVbo)
        GLES20.glUniformMatrix4fv(p.uMVP, 1, false, mvp, 0)
        GLES20.glUniform3fv(p.uWarm, 1, InteriorShaders.WARM_DIR, 0)
        GLES20.glUniform3fv(p.uCool, 1, InteriorShaders.COOL_DIR, 0)
        GLES20.glUniform1f(p.uAmbient, lighting.ambient)
        GLES20.glUniform1f(p.uWarmW, lighting.warmW)
        GLES20.glUniform1f(p.uCoolW, lighting.coolW)
        GLES20.glUniform3fv(p.uCoolCol, 1, lighting.coolCol, 0)
        GLES20.glUniform3fv(p.uFog, 1, lighting.fog, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, litCount)
    }

    private fun drawEmis(mvp: FloatArray, uTime: Float) {
        val p = emisP!!
        GLES20.glUseProgram(p.prog)
        bindStream(p, emisVbo)
        GLES20.glUniformMatrix4fv(p.uMVP, 1, false, mvp, 0)
        GLES20.glUniform1f(p.uTime, uTime)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, emisCount)
    }

    private fun drawPrecip(mvp: FloatArray, uTime: Float) {
        val p = precipP!!
        GLES20.glUseProgram(p.prog)
        bindStream(p, precipVbo)
        GLES20.glUniformMatrix4fv(p.uMVP, 1, false, mvp, 0)
        GLES20.glUniform1f(p.uTime, uTime)
        GLES20.glUniform1f(p.uFall, precipFall)
        GLES20.glUniform1f(p.uAlpha, precipAlpha)
        GLES20.glUniform3fv(p.uPrecipCol, 1, precipCol, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, precipCount)
    }

    private fun bindStream(p: Prog, vbo: Int) {
        bindAttrib(vbo, p.aPos, 3, 36, 0)
        bindAttrib(vbo, p.aNor, 3, 36, 12)
        bindAttrib(vbo, p.aCol, 3, 36, 24)
    }

    private fun uploadGeometry() {
        cpuLit?.let { litVbo = replaceBuffer(litVbo, it); litCount = it.size / 9 }
        cpuEmis?.let { emisVbo = replaceBuffer(emisVbo, it); emisCount = it.size / 9 }
        cpuPrecip?.let { precipVbo = replaceBuffer(precipVbo, it); precipCount = it.size / 9 }
    }

    private fun flattenStops(stops: Array<FloatArray>): FloatArray {
        val out = FloatArray(9)
        for (i in 0..2) { out[i * 3] = stops[i][0]; out[i * 3 + 1] = stops[i][1]; out[i * 3 + 2] = stops[i][2] }
        return out
    }

    private fun replaceBuffer(old: Int, data: FloatArray): Int {
        if (old != 0) GLES20.glDeleteBuffers(1, intArrayOf(old), 0)
        return arrayBuffer(floatBuf(data), data.size * 4)
    }

    private fun bindAttrib(vbo: Int, loc: Int, size: Int, stride: Int, offset: Int) {
        if (loc < 0) return
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
            throw RuntimeException("interior shader compile failed: $log")
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
            throw RuntimeException("interior program link failed: $log")
        }
        return prog
    }
}
