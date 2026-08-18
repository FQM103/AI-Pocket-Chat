package com.situ.aichat.ui.world.planet

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 星球 GLES2 渲染器（W9a 图纸 §2/§4.1）：编译链接四程序（星球/云/背景/星点）+ 缓冲 + 每帧四 pass
 * 绘制（背景→星点→星球→云·demo:L204-241）。**绝不碰 DB/网络**（种子/seedOff 由 VM 一次性传入）。
 *
 * 太阳向量 [0.75,0.25,0.62]、云壳 1.016、球壳 1.0、`persp(0.9,·,0.1,30)`、视距平移 `-dist` 全 demo 锁死。
 * uTime 用真实经过秒（demo:L207-208）；静帧（[reduceMotion]||[staticMode]）→ uTime=0（云冻结）·星点不闪·
 * 相机无闲置自转。着色器编译/链接失败 → [onGlError]（主线程回调由调用方封装）+ 停绘（不崩·§5 E14）。
 *
 * 首帧成功后 [onFirstFrame]（回星球揭幕等它·性能卷 B·三兄弟 renderer 同惯例·默认空 = 小卡等无需揭幕的宿主
 * 零改）；建面耗时三观测点走 Logcat tag `WorldPerf`（诊断 shader 编译占比·不进 PerfCollector 样本）。
 */
internal class PlanetRenderer(
    private val camera: PlanetCamera,
    private val seed: Long,
    private val seedOff: Float,
    private val pointScale: Float,
    private val onGlError: () -> Unit,
    private val onFirstFrame: () -> Unit = {},
) : GLSurfaceView.Renderer {

    @Volatile var reduceMotion: Boolean = false
    @Volatile var staticMode: Boolean = false

    private var glReady = false
    private var firstFrameSent = false
    private var aspect = 1f
    private var startNanos = 0L
    private var lastNanos = 0L

    // 缓冲句柄
    private var sphereVbo = 0
    private var sphereIbo = 0
    private var sphereIndexCount = 0
    private var quadVbo = 0
    private var starVbo = 0

    // 星球程序
    private var pProg = 0
    private var pAPos = 0; private var pUMVP = 0; private var pUModel = 0
    private var pUScale = 0; private var pUSun = 0; private var pUSeedOff = 0
    // 云程序
    private var cProg = 0
    private var cAPos = 0; private var cUMVP = 0; private var cUModel = 0
    private var cUScale = 0; private var cUSun = 0; private var cUTime = 0
    // 背景程序
    private var bProg = 0
    private var bAPos = 0; private var bURes = 0
    // 星点程序
    private var sProg = 0
    private var sAStar = 0; private var sUTime = 0; private var sUAnim = 0; private var sUPointScale = 0

    private val sun = floatArrayOf(0.75f, 0.25f, 0.62f) // demo:L167

    /** 背景 uResolution 只需宽高比（银河/光晕按比例算·量纲无关）→ 传比例分辨率，绝对单位在比值中约掉。 */
    private val viewportUnit = 1000f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val enterNanos = System.nanoTime() // 观测点计时基准（link 段是全函数的子段）
        try {
            pProg = link(PlanetShaders.VS, PlanetShaders.FS_PLANET)
            cProg = link(PlanetShaders.VS, PlanetShaders.FS_CLOUD)
            bProg = link(PlanetShaders.BG_VS, PlanetShaders.BG_FS)
            sProg = link(PlanetShaders.STAR_VS, PlanetShaders.STAR_FS)
        } catch (e: RuntimeException) {
            glReady = false
            onGlError()
            return
        }
        Log.i("WorldPerf", "planet_shaders_linked_ms=${(System.nanoTime() - enterNanos) / 1_000_000}")
        pAPos = GLES20.glGetAttribLocation(pProg, "aPos")
        pUMVP = GLES20.glGetUniformLocation(pProg, "uMVP"); pUModel = GLES20.glGetUniformLocation(pProg, "uModel")
        pUScale = GLES20.glGetUniformLocation(pProg, "uScale"); pUSun = GLES20.glGetUniformLocation(pProg, "uSun")
        pUSeedOff = GLES20.glGetUniformLocation(pProg, "uSeedOff")
        cAPos = GLES20.glGetAttribLocation(cProg, "aPos")
        cUMVP = GLES20.glGetUniformLocation(cProg, "uMVP"); cUModel = GLES20.glGetUniformLocation(cProg, "uModel")
        cUScale = GLES20.glGetUniformLocation(cProg, "uScale"); cUSun = GLES20.glGetUniformLocation(cProg, "uSun")
        cUTime = GLES20.glGetUniformLocation(cProg, "uTime")
        bAPos = GLES20.glGetAttribLocation(bProg, "aPos"); bURes = GLES20.glGetUniformLocation(bProg, "uResolution")
        sAStar = GLES20.glGetAttribLocation(sProg, "aStar"); sUTime = GLES20.glGetUniformLocation(sProg, "uTime")
        sUAnim = GLES20.glGetUniformLocation(sProg, "uAnim"); sUPointScale = GLES20.glGetUniformLocation(sProg, "uPointScale")

        val sphere = PlanetGeometry.buildSphere()
        sphereIndexCount = sphere.indices.size
        sphereVbo = arrayBuffer(floatBuf(sphere.positions), sphere.positions.size * 4)
        sphereIbo = elementBuffer(shortBuf(sphere.indices), sphere.indices.size * 2)
        val quad = PlanetGeometry.backgroundQuad
        quadVbo = arrayBuffer(floatBuf(quad), quad.size * 4)
        val stars = PlanetGeometry.buildStars(seed)
        starVbo = arrayBuffer(floatBuf(stars), stars.size * 4)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glCullFace(GLES20.GL_BACK)
        startNanos = System.nanoTime()
        lastNanos = startNanos
        glReady = true
        Log.i("WorldPerf", "planet_surface_created_ms=${(System.nanoTime() - enterNanos) / 1_000_000}")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = if (height == 0) 1f else width.toFloat() / height.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!glReady) return

        val now = System.nanoTime()
        val dt = ((now - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastNanos = now
        val frozen = reduceMotion || staticMode
        val uTime = if (frozen) 0f else ((now - startNanos) / 1_000_000_000.0).toFloat()

        camera.integrate(dt, reduceMotion = frozen)
        val cam = camera.snapshot
        val m = PlanetMath.sceneMatrices(cam.yaw, cam.pitch, cam.dist, aspect)

        // ── ① 背景（最先·关深度）② 星点 ──
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(bProg)
        bindAttrib(quadVbo, bAPos, 2)
        GLES20.glUniform2f(bURes, aspect * viewportUnit, viewportUnit)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

        GLES20.glUseProgram(sProg)
        bindAttrib(starVbo, sAStar, 4)
        GLES20.glUniform1f(sUTime, uTime)
        GLES20.glUniform1f(sUAnim, if (frozen) 0f else 1f)
        GLES20.glUniform1f(sUPointScale, pointScale)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, PlanetGeometry.STAR_COUNT)

        // ── ③ 星球 ④ 云（demo:L224-241）──
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        GLES20.glUseProgram(pProg)
        bindAttrib(sphereVbo, pAPos, 3)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, sphereIbo)
        GLES20.glUniformMatrix4fv(pUMVP, 1, false, m.mvp, 0)
        GLES20.glUniformMatrix4fv(pUModel, 1, false, m.model, 0)
        GLES20.glUniform1f(pUScale, 1.0f)
        GLES20.glUniform3fv(pUSun, 1, sun, 0)
        GLES20.glUniform1f(pUSeedOff, seedOff)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphereIndexCount, GLES20.GL_UNSIGNED_SHORT, 0)

        GLES20.glUseProgram(cProg)
        bindAttrib(sphereVbo, cAPos, 3)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, sphereIbo)
        GLES20.glUniformMatrix4fv(cUMVP, 1, false, m.mvp, 0)
        GLES20.glUniformMatrix4fv(cUModel, 1, false, m.model, 0)
        GLES20.glUniform1f(cUScale, 1.016f)
        GLES20.glUniform3fv(cUSun, 1, sun, 0)
        GLES20.glUniform1f(cUTime, uTime)
        GLES20.glDepthMask(false)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphereIndexCount, GLES20.GL_UNSIGNED_SHORT, 0)
        GLES20.glDepthMask(true)

        // 首帧揭幕：四 pass 真正画完才触发（glReady 前提由上方早退保证·避免揭在空场景上）。
        if (!firstFrameSent) {
            firstFrameSent = true
            Log.i("WorldPerf", "planet_first_frame")
            onFirstFrame()
        }
    }

    private fun bindAttrib(vbo: Int, loc: Int, size: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(loc)
        GLES20.glVertexAttribPointer(loc, size, GLES20.GL_FLOAT, false, 0, 0)
    }

    private fun arrayBuffer(buffer: FloatBuffer, byteSize: Int): Int = genBuffer(GLES20.GL_ARRAY_BUFFER, buffer, byteSize)
    private fun elementBuffer(buffer: ShortBuffer, byteSize: Int): Int = genBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffer, byteSize)

    private fun genBuffer(target: Int, buffer: java.nio.Buffer, byteSize: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(target, ids[0])
        GLES20.glBufferData(target, byteSize, buffer, GLES20.GL_STATIC_DRAW)
        return ids[0]
    }

    private fun floatBuf(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }

    private fun shortBuf(data: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(data); position(0) }

    /** 编译单个着色器·失败抛异常（附 InfoLog·[onSurfaceCreated] 捕获转兜底）。 */
    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(sh)
            GLES20.glDeleteShader(sh)
            throw RuntimeException("shader compile failed: $log")
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
            throw RuntimeException("program link failed: $log")
        }
        return prog
    }
}
