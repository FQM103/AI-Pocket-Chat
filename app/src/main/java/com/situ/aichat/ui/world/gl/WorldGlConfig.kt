package com.situ.aichat.ui.world.gl

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * GLES2 + 4xMSAA·失败退无 MSAA（W9b 图纸 §2·从 [com.situ.aichat.ui.world.planet.PlanetGLView] **只搬不改**
 * 抽出·星球 / 大陆两 GL 视图共用）。逻辑与抽出前逐字节一致。
 */
internal class MsaaConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        pick(egl, display, msaa = true)?.let { return it }
        return pick(egl, display, msaa = false)
            ?: throw IllegalStateException("no EGL config (GLES2) available")
    }

    private fun pick(egl: EGL10, display: EGLDisplay, msaa: Boolean): EGLConfig? {
        val attribs = buildList {
            addAll(listOf(EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT))
            addAll(listOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8))
            addAll(listOf(EGL10.EGL_ALPHA_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16))
            if (msaa) addAll(listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4))
            add(EGL10.EGL_NONE)
        }.toIntArray()
        val num = IntArray(1)
        if (!egl.eglChooseConfig(display, attribs, null, 0, num) || num[0] == 0) return null
        val configs = arrayOfNulls<EGLConfig>(num[0])
        egl.eglChooseConfig(display, attribs, configs, num[0], num)
        return configs[0]
    }

    private companion object {
        const val EGL_OPENGL_ES2_BIT = 4
    }
}
