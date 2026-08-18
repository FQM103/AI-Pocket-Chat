package com.situ.aichat.ui.world.town

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.situ.aichat.ui.world.continent.ContinentShaders
import com.situ.aichat.ui.world.continent.SkyStop
import com.situ.aichat.ui.world.planet.PlanetShaders
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** 一座小镇的天空/辉光参数（7 停靠色 + 位置 + 辉光 α·由 [TownData.sky]/[TownData.glowA] 组装·§4.1E）。 */
internal class TownSkyParams(val colors: FloatArray, val pos: FloatArray, val glowA: Float) {
    companion object {
        fun of(sky: List<SkyStop>, glowA: Float): TownSkyParams {
            val colors = FloatArray(21)
            for (i in 0..6) {
                val c = sky[i].color
                colors[i * 3] = c[0]; colors[i * 3 + 1] = c[1]; colors[i * 3 + 2] = c[2]
            }
            return TownSkyParams(colors, FloatArray(7) { sky[it].pos }, glowA)
        }
    }
}

/**
 * 小镇盒景 GLES2 渲染器（W9c 图纸 §2/§4.1E·三 pass：背景→星点→lit→emis·**禁背面剔除**·水体在 lit 流内故无
 * 独立水 pass·demo:L275-296）。装载时 [submitTown] 缓冲上传（GL 线程 queueEvent）。首帧成功后 [onFirstFrame]
 * （揭幕）；编译/链接失败 → [onGlError]（皆由 GLView 封装为主线程回调·不崩·§5 E11·统一走 WorldScreen.onSceneGlError）。
 * GL 上下文重建（onSurfaceCreated 再回调）→ 重编译 + 用 CPU 几何副本重传（§3.6）。
 */
internal class TownRenderer(
    private val camera: TownCamera,
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

    /** lit/emis 各自 attrib/uniform 句柄（同 C_VS·分程序取·emis 无 uSun→-1）。 */
    private class TownProg(val prog: Int) {
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aNor = GLES20.glGetAttribLocation(prog, "aNor")
        val aCol = GLES20.glGetAttribLocation(prog, "aCol")
        val uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        val uSun = GLES20.glGetUniformLocation(prog, "uSun")
    }
    private var litP: TownProg? = null
    private var emisP: TownProg? = null

    // 缓冲
    private var litVbo = 0; private var litCount = 0
    private var emisVbo = 0; private var emisCount = 0
    private var quadVbo = 0
    private var starVbo = 0
    private var farVbo = 0; private var farCount = 0

    // CPU 几何副本（上下文重建重传）
    private var cpuLit: FloatArray? = null
    private var cpuEmis: FloatArray? = null
    private val stars = TownGeometry.buildTownStars(worldSeed)
    private val farScenery = TownGeometry.buildFarScenery(worldSeed)   // 远景层（§3.3·与场景独立·随 worldSeed 确定）

    @Volatile private var sky: TownSkyParams? = null

    private val sun = floatArrayOf(-0.55f, 0.5f, 0.42f) // demo:L214

    /** 提交小镇几何 + 天空（GL 线程·queueEvent 调）。 */
    fun submitTown(data: TownGeometryData, skyParams: TownSkyParams) {
        cpuLit = data.lit; cpuEmis = data.emis; sky = skyParams
        if (glReady) uploadTown()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            bgProg = link(PlanetShaders.BG_VS, TownShaders.T_BG_FS)
            starProg = link(PlanetShaders.STAR_VS, PlanetShaders.STAR_FS)
            litP = TownProg(link(ContinentShaders.C_VS, TownShaders.T_FS_LIT))
            emisP = TownProg(link(ContinentShaders.C_VS, ContinentShaders.C_FS_EMIS))
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

        val quad = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)
        quadVbo = arrayBuffer(floatBuf(quad), quad.size * 4)
        starVbo = arrayBuffer(floatBuf(stars), stars.size * 4)
        farVbo = arrayBuffer(floatBuf(farScenery), farScenery.size * 4); farCount = farScenery.size / 9

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        startNanos = System.nanoTime()
        lastNanos = startNanos
        glReady = true
        uploadTown() // 上下文重建后用 CPU 副本重传（首建时副本可能为 null → 待 submitTown）
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
        val mvp = TownMath.townMvp(cam.yaw, cam.pitch, cam.dist, cam.tx, cam.ty, cam.tz, aspect)
        val s = sky

        // ── ① 背景（关深度）② 星点（关深度写）── demo:L289-296 无剔除
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        if (s != null) {
            GLES20.glUseProgram(bgProg)
            bindAttrib(quadVbo, bgAPos, 2, 0, 0)
            GLES20.glUniform3fv(bgUSky, 7, s.colors, 0)
            GLES20.glUniform1fv(bgUSkyPos, 7, s.pos, 0)
            GLES20.glUniform1f(bgUGlow, s.glowA)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        }
        GLES20.glUseProgram(starProg)
        bindAttrib(starVbo, starAStar, 4, 0, 0)
        GLES20.glUniform1f(starUTime, uTime)
        GLES20.glUniform1f(starUAnim, if (frozen) 0f else 1f)
        GLES20.glUniform1f(starUScale, pointScale)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, TownGeometry.STAR_COUNT)

        // ── ②b 远景层（山剪影 + 邻村灯火·emis flat·世界锚定于盒景北缘外·同场景 MVP 贴地平线·深度仍关=背景层·§3.3）──
        if (farCount > 0) drawScene(emisP!!, farVbo, farCount, mvp)

        // ── ③ lit（深度测试开·禁背面剔除）──
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDepthMask(true)
        if (litCount > 0 && s != null) drawScene(litP!!, litVbo, litCount, mvp)

        // ── ④ emis（自发光·窗/灯头·demo:L296）──
        if (emisCount > 0 && s != null) drawScene(emisP!!, emisVbo, emisCount, mvp)

        // 首帧揭幕：几何已上传且真正画出后才触发（避免揭在空场景上）。
        if (!firstFrameSent && litCount > 0 && s != null) { firstFrameSent = true; onFirstFrame() }
    }

    private fun drawScene(sp: TownProg, vbo: Int, count: Int, mvp: FloatArray) {
        GLES20.glUseProgram(sp.prog)
        bindAttrib(vbo, sp.aPos, 3, 36, 0)
        bindAttrib(vbo, sp.aNor, 3, 36, 12)
        bindAttrib(vbo, sp.aCol, 3, 36, 24)
        GLES20.glUniformMatrix4fv(sp.uMVP, 1, false, mvp, 0)
        if (sp.uSun >= 0) GLES20.glUniform3fv(sp.uSun, 1, sun, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
    }

    private fun uploadTown() {
        cpuLit?.let { litVbo = replaceBuffer(litVbo, it); litCount = it.size / 9 }
        cpuEmis?.let { emisVbo = replaceBuffer(emisVbo, it); emisCount = it.size / 9 }
    }

    private fun replaceBuffer(old: Int, data: FloatArray): Int {
        if (old != 0) GLES20.glDeleteBuffers(1, intArrayOf(old), 0)
        return arrayBuffer(floatBuf(data), data.size * 4)
    }

    private fun bindAttrib(vbo: Int, loc: Int, size: Int, stride: Int, offset: Int) {
        if (loc < 0) return // 共用 C_VS 时 emis 程序会剔除未用的 aNor → 跳过（demo 同款无害）
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
            throw RuntimeException("town shader compile failed: $log")
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
            throw RuntimeException("town program link failed: $log")
        }
        return prog
    }
}
