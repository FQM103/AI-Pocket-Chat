package com.situ.aichat.ui.world.gl

import android.view.Choreographer

/**
 * 共享帧泵（W9b 图纸 §2/§3.8·从 [com.situ.aichat.ui.world.planet.PlanetGLView] 抽出参数化·世界四景共用）。
 * **时间基准节流**（性能卷 B·2026-08-02）：按「距上次实画的真实时间」判定这一拍画不画，任何屏幕刷新率下
 * 目标帧率恒定——旧的「隔拍 / 每拍」数帧法假设 60Hz 屏，在 120Hz 屏上出力翻倍（环境态 60Hz、交互态 120Hz）。
 * - 冻结（[frozen]=reduceMotion/staticMode）：手势期间（[gesturing]）按交互档节流渲染 + 松手补 1 帧（补帧不节流）；
 * - 交互（[highFps]）：交互档 = 60Hz（[INTERACTIVE_MIN_INTERVAL_NANOS]）；
 * - 环境态：环境档 = 30Hz（[AMBIENT_MIN_INTERVAL_NANOS]）。
 *
 * [onTick] 每一拍都跑（无论是否渲染·转场信号轮询用·默认空）；[render] 只在需渲染的拍触发。
 * 均在 Choreographer 回调（UI 线程）执行。
 */
internal class WorldFramePump(
    private val frozen: () -> Boolean,
    private val gesturing: () -> Boolean,
    private val highFps: () -> Boolean,
    private val render: () -> Unit,
    private val onTick: () -> Unit = {},
) {
    private var running = false

    /** 上次**实画**那一拍的帧时间戳。初值 0 / [stop]→[start] 不重置 → 首拍与重启首拍必渲染（resumeWorld 即出帧）。 */
    private var lastRenderNanos = 0L

    /** 静帧模式松手补帧（终态帧·绕节流立即画）。 */
    private var settleFrames = 0

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            onFrame(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * 单拍判定体（[callback] 唯一调用方·抽出为 T1 测试接缝：running 门与重投递留在 [callback] 里，
     * 本函数纯粹是「这一拍干什么」）。
     */
    internal fun onFrame(frameTimeNanos: Long) {
        onTick()
        if (frozen()) {
            if (gesturing()) {
                if (shouldRender(frameTimeNanos, lastRenderNanos, INTERACTIVE_MIN_INTERVAL_NANOS)) {
                    lastRenderNanos = frameTimeNanos; render()
                }
                settleFrames = 1 // 恒置：表达「手势在进行·将来松手要补终态帧」，与本拍是否实画解耦
            } else if (settleFrames > 0) {
                render(); settleFrames-- // 松手补帧：立即画·不节流·不更新 lastRenderNanos
            }
        } else if (highFps()) {
            if (shouldRender(frameTimeNanos, lastRenderNanos, INTERACTIVE_MIN_INTERVAL_NANOS)) {
                lastRenderNanos = frameTimeNanos; render()
            }
        } else {
            if (shouldRender(frameTimeNanos, lastRenderNanos, AMBIENT_MIN_INTERVAL_NANOS)) {
                lastRenderNanos = frameTimeNanos; render()
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(callback)
    }

    companion object {
        /** 环境态目标 30Hz：33ms 阈值自带 ~0.33ms 容差，120/90/60Hz 上分别每 4/3/2 拍精确 30Hz（144Hz 每 5 拍 ≈28.8Hz）。 */
        const val AMBIENT_MIN_INTERVAL_NANOS = 33_000_000L

        /** 交互态目标 60Hz（拍板 2026-08-02·PlanetCamera.wantsHighFps KDoc 的原意图）：120Hz 每 2 拍、60Hz 每拍。 */
        const val INTERACTIVE_MIN_INTERVAL_NANOS = 16_000_000L

        /** 时间基准节流判定（纯函数·T1 直测）：与上次实画时间相减，够间隔才画（**禁相位累积**——空窗后会 burst）。 */
        fun shouldRender(nowNanos: Long, lastRenderNanos: Long, minIntervalNanos: Long): Boolean =
            nowNanos - lastRenderNanos >= minIntervalNanos
    }
}
