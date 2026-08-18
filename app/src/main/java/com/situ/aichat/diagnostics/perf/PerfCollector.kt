package com.situ.aichat.diagnostics.perf

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.situ.aichat.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** 回前台各 pass 的固定名字（导出报告按这些名字对齐前后两次采集，改名 = 历史数据对不上）。 */
object PerfPassNames {
    /**
     * 进程内**第一趟**回前台才有的标记 pass（值 = 从进程启动到这一刻的毫秒数）。
     * 待采清单「冷启动 ≥3」靠它认——用既有的 `passes` 列表就够，不必为此新增样本类型或字段。
     */
    const val COLD_START = "cold_start"
    const val ENTRY_MAIN_THREAD = "entry_main_thread"
    const val BG_DIAGNOSTICS = "bg_diagnostics"
    const val NOTIFY_MATERIALIZE = "notify_materialize"
    const val SCHEDULE_ENSURE_TODAY = "schedule_ensure_today"
    const val MOMENT_PASS = "moment_pass"
    const val MEETING_MISSED = "meeting_missed"
    const val PET_MAINTENANCE = "pet_maintenance"
    const val ECONOMY_MAINTENANCE = "economy_maintenance"
    const val COLD_START_HEAL = "cold_start_heal"
    const val STORY_PASS = "story_pass"
    const val WORLD_LINK = "world_link"
    const val MOMENT_LOOP = "moment_loop"
}

/**
 * 性能采集总入口（图纸 §2.1 / §3.1 / §3.3）。骨架照 [com.situ.aichat.diagnostics.ContextLogService]：
 * **自有 scope**（勿借 viewModelScope）+ 攒批落盘 + 全程静默失败。
 *
 * 关闭时零成本（图纸 J5）：采集点只读进程内 [isEnabled] 这个 `@Volatile` 布尔，关 → 立即 return，
 * **不读 DataStore、不 suspend、不分配对象**。开关值由 DataStore 变更推着刷新，不是每次采集去问。
 */
@Singleton
class PerfCollector @Inject constructor(
    private val store: PerfStore,
    private val scaleSnapshot: ScaleSnapshot,
    settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 内存攒批队列。临界区极短（只 add / 整体搬走），采集点不等待写盘。 */
    private val queue = ArrayList<PerfSample>(FLUSH_BATCH)
    private var lastFlushMillis = 0L // 由 queue 的 synchronized 保护

    @Volatile
    var isEnabled: Boolean = false
        private set

    /**
     * 尚未封口的「回前台」计时（图纸 §3.2 一趟回前台 = 一条 `foreground` 样本）。
     * 回前台的 pass 是异步的、完成时刻各不相同，所以不在同步体结束时就封口，而是**攒到下一次回前台、
     * 或下一次强制 flush 时**才落样本 —— 那时慢 pass 早已报完，且无需引入任何「等多久」的魔法常量。
     */
    private val pendingTrace = AtomicReference<ForegroundTrace?>(null)

    /** 进程内是否已开过一趟回前台计时（用于认冷启动）。 */
    @Volatile
    private var firstTraceStarted = false

    private val enabledListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    init {
        scope.launch {
            settingsRepository.appSettings
                .map { it.perfCollectEnabled }
                .distinctUntilChanged()
                .collect { applyEnabled(it) }
        }
    }

    /** 采集点通用入口：入队一条样本（fire-and-forget）。关 → 立即返回。 */
    fun record(sample: PerfSample) {
        if (!isEnabled) return
        enqueue(sample)
    }

    /** 造一个当前时刻的样本头。 */
    fun newHeader(kind: String): PerfHeader =
        PerfHeader(PERF_SCHEMA_VERSION, System.currentTimeMillis(), kind)

    /**
     * 尺 4：记一趟设置手势（图纸 §3.4）。
     *
     * [writesInGesture] = **一趟手势里滑杆值变了多少次**，也就是「每跨一档就写一次设置」那种实现会造成多少次
     * DataStore **全量重写** —— SET-2/SET-3 的判定线「一趟拖动 >20 次全量重写 → 直接做」比的就是这个数。
     * （上下文日志那根滑杆本卷已改成松手才写、真写入恒 1 次；这里量的是**拖动本身有多密**，也就是其余
     * 14 个未改调用点要付的代价。）[payloadBytes] = 设置 DataStore 文件大小 = 一次全量重写的字节数。
     */
    fun recordSettingsWrite(screen: String, key: String, writesInGesture: Int, gestureMs: Long) {
        if (!isEnabled) return
        enqueue(
            PerfSample.SettingsWrite(
                header = newHeader(PerfSampleKind.SETTINGS_WRITE),
                screen = screen,
                key = key,
                writesInGesture = writesInGesture,
                gestureMs = gestureMs,
                payloadBytes = settingsPayloadBytes(),
            ),
        )
    }

    /** 开一趟「回前台」计时；关 → null（调用侧 [timedPass] 对 null 是零成本直通）。 */
    fun beginForegroundTrace(): ForegroundTrace? {
        if (!isEnabled) return null
        val trace = ForegroundTrace(System.nanoTime(), System.currentTimeMillis())
        // 进程内第一趟 = 冷启动，插一条标记 pass（值 = 进程启动到现在的毫秒数）。
        if (!firstTraceStarted) {
            firstTraceStarted = true
            trace.recordPass(PerfPassNames.COLD_START, millisSinceProcessStart())
        }
        pendingTrace.getAndSet(trace)?.let { previous -> scope.launch { materialize(previous) } }
        return trace
    }

    /**
     * 订阅开关变化（[FrameMetricsProbe] 等常驻探针据此挂/摘监听）。回调在采集 scope 的 IO 线程上跑，
     * 抛异常不影响其他订阅者。
     */
    fun addEnabledListener(listener: (Boolean) -> Unit) {
        enabledListeners += listener
    }

    /** 强制落盘（开关关闭 / 场景离场 / 导出前）。 */
    suspend fun flushNow() {
        pendingTrace.getAndSet(null)?.let { materialize(it) }
        val batch = synchronized(queue) { drainLocked(System.currentTimeMillis()) } ?: return
        store.append(batch)
    }

    /** [flushNow] 的 fire-and-forget 版（UI / 探针回调里用）。 */
    fun requestFlush() {
        scope.launch { runCatching { flushNow() }.onFailure { Log.e(TAG, "强制 flush 失败: ${it.message}") } }
    }

    // MARK: - 私有

    private suspend fun applyEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        isEnabled = enabled
        enabledListeners.forEach { runCatching { it(enabled) } }
        // 开 → 关：把已攒的样本落盘再停（图纸 §3.1）。
        if (!enabled) runCatching { flushNow() }.onFailure { Log.e(TAG, "关采集时 flush 失败: ${it.message}") }
    }

    /** 入队；到阈值（攒够 [FLUSH_BATCH] 条 或 距上次 flush 满 [FLUSH_INTERVAL_MS]）就把整批搬出来，否则 null。 */
    private fun addToQueue(sample: PerfSample): List<PerfSample>? = synchronized(queue) {
        queue += sample
        val now = System.currentTimeMillis()
        if (queue.size >= FLUSH_BATCH || now - lastFlushMillis >= FLUSH_INTERVAL_MS) drainLocked(now) else null
    }

    /** 采集点用的 fire-and-forget 入队：到阈值就派协程写盘，采集点不等待。 */
    private fun enqueue(sample: PerfSample) {
        val batch = addToQueue(sample) ?: return
        scope.launch { store.append(batch) }
    }

    /** 从进程启动到现在的毫秒数。取不到 → 0（只影响冷启动标记的数值，不影响它是不是标记）。 */
    private fun millisSinceProcessStart(): Long = runCatching {
        SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
    }.getOrDefault(0L)

    /** 设置 DataStore 文件大小（一次「全量重写」的字节代价）。读不到 → 0。 */
    private fun settingsPayloadBytes(): Int = runCatching {
        store.settingsDataStoreBytes()
    }.getOrDefault(0)

    /** 已持 queue 锁调用：把队列整体搬走。空队列 → null。 */
    private fun drainLocked(nowMillis: Long): List<PerfSample>? {
        if (queue.isEmpty()) return null
        val batch = ArrayList(queue)
        queue.clear()
        lastFlushMillis = nowMillis
        return batch
    }

    /**
     * 把一趟回前台计时封口成 `foreground` 样本。一条 pass 都没记 / 已封口 → 不落样本。
     *
     * **封口即强制 flush**（不看攒批阈值）：一趟测量已经完结、频率至多「每次回前台一次」，立刻写盘既便宜又省掉
     * 一个真实的竞态——上一趟的封口是派协程异步做的，若它还要等阈值，就可能刚好落在 [flushNow] 抽干队列之后，
     * 于是「导出前已 flush」的承诺被打破、那条样本要等到下一次阈值才出现。
     * 本身是 suspend，写盘**同步做完**再返回。
     */
    private suspend fun materialize(trace: ForegroundTrace) {
        val snapshot = trace.close() ?: return
        val sample = PerfSample.Foreground(
            header = PerfHeader(PERF_SCHEMA_VERSION, snapshot.startMillis, PerfSampleKind.FOREGROUND),
            totalMs = snapshot.totalMs,
            passes = snapshot.passes,
            scale = scaleSnapshot.capture(),
        )
        val batch = synchronized(queue) {
            queue += sample
            drainLocked(System.currentTimeMillis())
        } ?: return
        store.append(batch)
    }

    companion object {
        private const val TAG = "PerfCollector"

        /** 图纸 §9② 锁定值。 */
        const val FLUSH_BATCH = 20
        const val FLUSH_INTERVAL_MS = 30_000L
    }
}

/**
 * 一趟「回前台」的计时累加器。线程安全（各 pass 在不同协程里完成）。
 *
 * `totalMs` = 从 `onAppForeground()` 进入，到**最后一个 pass 报完**为止的墙上时间；
 * 主线程同步段单独记成 [PerfPassNames.ENTRY_MAIN_THREAD] 这一条 pass（M13 的「单趟 ms」看它）。
 */
class ForegroundTrace internal constructor(
    private val startNanos: Long,
    private val startMillis: Long,
) {
    private val lock = Any()
    private val passes = ArrayList<PassTiming>(12)
    private var lastEndNanos = startNanos
    private var closed = false

    /** 由 [timedPass] 内联调用（故为 public）；**采集点勿直接调**。 */
    fun recordPass(name: String, ms: Long) {
        synchronized(lock) {
            if (closed) return
            passes += PassTiming(name, ms)
            lastEndNanos = System.nanoTime()
        }
    }

    /** 记「主线程同步段」耗时 = 从本 trace 创建到此刻（`onAppForeground()` 体末尾调一次）。 */
    fun recordEntryMainThread() {
        recordPass(PerfPassNames.ENTRY_MAIN_THREAD, (System.nanoTime() - startNanos) / NANOS_PER_MILLI)
    }

    /** 封口取快照。已封口或一条 pass 都没记 → null（不落空样本）。 */
    internal fun close(): Snapshot? = synchronized(lock) {
        if (closed || passes.isEmpty()) {
            closed = true
            return@synchronized null
        }
        closed = true
        Snapshot(startMillis, (lastEndNanos - startNanos) / NANOS_PER_MILLI, passes.toList())
    }

    internal data class Snapshot(val startMillis: Long, val totalMs: Long, val passes: List<PassTiming>)

    companion object {
        /** 纳秒 → 毫秒。public 是因为公开的内联函数 [timedPass] 要用它。 */
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * 给一个 pass 计时。**对 null（采集关闭）是零成本直通**：不取时间戳、不记录、内联后连 lambda 对象都不分配。
 * 块内抛异常照样计时并原样重抛（`finally`），绝不改变被包裹代码的行为。
 */
inline fun <T> ForegroundTrace?.timedPass(name: String, block: () -> T): T {
    if (this == null) return block()
    val start = System.nanoTime()
    try {
        return block()
    } finally {
        recordPass(name, (System.nanoTime() - start) / ForegroundTrace.NANOS_PER_MILLI)
    }
}
