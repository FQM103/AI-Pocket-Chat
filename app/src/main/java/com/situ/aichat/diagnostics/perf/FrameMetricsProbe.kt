package com.situ.aichat.diagnostics.perf

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 掉帧探针（图纸 §3.4 尺 3）：挂 AOSP 原生 [Window.addOnFrameMetricsAvailableListener]，
 * 按「场景会话」（进屏 → 离屏）把每帧耗时喂进内存直方图，**离场时才落一条**汇总样本。
 *
 * 三条硬规矩：
 * - **只在「开关开 + 进入被观测屏」时注册监听，离场即摘**（图纸 J5）——平时一行代码都不跑。
 * - 帧数据**绝不逐帧落盘**（图纸 J2）：聚合在 [FrameHistogram] 里，内存恒定。
 * - 帧预算按**真实刷新率**算（[FrameHistogram.budgetMs]），绝不写死 16.67（图纸 §9④）。
 *
 * 监听回调跑在自有后台 [HandlerThread] 上（系统要求给 Handler；给主线程等于让测量工具自己拖慢主线程）。
 */
@Singleton
class FrameMetricsProbe @Inject constructor(
    private val collector: PerfCollector,
    private val deviceHealthProbe: DeviceHealthProbe,
) {
    private var window: Window? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var registered = false

    private val lock = Any()
    private var session: Session? = null

    init {
        // 采集被关掉 → 立刻收摊（图纸 §3.1「开 → 关：立即摘除监听」）。在飞的这段会话是半截测量，随之丢弃。
        collector.addEnabledListener { enabled -> if (!enabled) endSession() }
    }

    /** 由 `MainActivity.onCreate` 交出窗口；没有窗口就永远不注册。 */
    fun attach(window: Window) {
        this.window = window
    }

    /** 由 `MainActivity.onDestroy` 摘除。 */
    fun detach() {
        endSession()
        window = null
    }

    /** 进入被观测场景。快速进退 / 场景嵌套时隐含先离场，绝不产生跨场景混合样本（§5 E11）。 */
    fun onEnter(scene: String) {
        endSession()
        if (!collector.isEnabled) return
        val w = window ?: return
        synchronized(lock) { session = Session(scene, System.nanoTime(), FrameHistogram(currentRefreshHz(w))) }
        registerListener(w)
    }

    /** 离开被观测场景：摘监听 + 落一条 `frames` 样本与一条 `health` 样本。 */
    fun onExit() {
        endSession()
    }

    // MARK: - 私有

    /**
     * 每帧回调。[FrameMetrics] 是系统复用的对象，**必须当场读完**，不许存起来晚点用。
     * 这里只做一次除法和一次数组自增，不分配任何对象。
     */
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val ms = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / NANOS_PER_MILLI_D
        synchronized(lock) { session?.histogram?.add(ms) }
    }

    /** 真实刷新率（取整）。取不到 → [DEFAULT_REFRESH_HZ]，且该值会如实写进样本供分析侧识别。 */
    private fun currentRefreshHz(w: Window): Int = runCatching {
        w.decorView.display?.refreshRate?.roundToInt()?.coerceAtLeast(1) ?: DEFAULT_REFRESH_HZ
    }.getOrDefault(DEFAULT_REFRESH_HZ)

    private fun registerListener(w: Window) {
        if (registered) return
        val thread = handlerThread ?: HandlerThread(THREAD_NAME).also { it.start(); handlerThread = it }
        val h = handler ?: Handler(thread.looper).also { handler = it }
        runCatching { w.addOnFrameMetricsAvailableListener(listener, h) }.onSuccess { registered = true }
    }

    private fun unregisterListener() {
        if (!registered) return
        runCatching { window?.removeOnFrameMetricsAvailableListener(listener) }
        registered = false
    }

    /** 封口当前会话（幂等：没有会话就只确保监听已摘）。一帧都没收到 → 不落空样本。 */
    private fun endSession() {
        val ended = synchronized(lock) { session.also { session = null } }
        unregisterListener()
        val h = ended?.histogram ?: return
        if (h.frameCount == 0) return
        collector.record(
            PerfSample.Frames(
                header = collector.newHeader(PerfSampleKind.FRAMES),
                scene = ended.scene,
                durationMs = (System.nanoTime() - ended.startNanos) / ForegroundTrace.NANOS_PER_MILLI,
                frameCount = h.frameCount,
                jankCount = h.jankCount,
                severeJankCount = h.severeJankCount,
                p50Ms = h.percentileMs(0.50),
                p95Ms = h.percentileMs(0.95),
                p99Ms = h.percentileMs(0.99),
                maxMs = h.maxMs,
                buckets = h.bucketCounts(),
                refreshHz = h.refreshHz,
            ),
        )
        collector.record(deviceHealthProbe.sample(collector.newHeader(PerfSampleKind.HEALTH), ended.scene))
        // 场景离场 = 强制 flush 点（图纸 §3.3）。
        collector.requestFlush()
    }

    private class Session(val scene: String, val startNanos: Long, val histogram: FrameHistogram)

    companion object {
        private const val THREAD_NAME = "perf-frame-metrics"
        private const val NANOS_PER_MILLI_D = 1_000_000.0

        /** 读不到显示器刷新率时的兜底（安卓保底档）。 */
        const val DEFAULT_REFRESH_HZ = 60
    }
}
