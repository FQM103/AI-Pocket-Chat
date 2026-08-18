package com.situ.aichat.ui.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 合并等待窗调度器（契约 FABLE5_CHAT_INPUT_BAR_PROPOSAL §3.2-1 · iOS `Services/MessageDispatcher.swift` 移植）：
 * 用户发送 → 消息立即落库上屏，AI 回合押后到等待窗到期才起；窗内再发一条 = 重置计时器 → 停手后一个回合
 * 一次读完全部消息（「跟真人聊天一样」）。范围收口 [0.5, 5]s / 默认 1.5s（iOS 1–20s/默认 3s·用户拍板改）。
 *
 * 自适应（iOS `recalculateAdaptiveDebounce`）：连发间隔（<10s）样本 ≥5 时取 P75 + 0.5s 钳到范围，
 * 每次入队后重算写回持久层——设置页滑块（C2）写同一持久值，手动调后自适应从新样本继续覆写（iOS 同款语义）。
 *
 * 时序：入队先记时间戳（stamp job 不受窗重置取消影响），窗 job join 它后读**最新**持久值再计时——
 * 与 iOS「本窗用旧值」的差异仅在首窗即用新自适应值，语义等效且更简单。[delayMs] 为可注入等待原语
 * （测试免真睡；同 [ChatSendFlightState] 的 nowMs 手法）。
 */
internal class ChatMessageDispatcher(
    private val scope: CoroutineScope,
    private val persistence: Persistence,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
) {

    /** 持久层口（生产 = SettingsRepository/DataStore·测试 = 内存假实现）。waitSeconds 未设过 → null（默认值在 UI 层单源）。 */
    interface Persistence {
        suspend fun loadWaitSeconds(): Float?
        suspend fun saveWaitSeconds(value: Float)
        suspend fun loadSendTimestamps(): List<Long>
        suspend fun saveSendTimestamps(values: List<Long>)
    }

    /** 窗到期回调（AssistantTurnController 接为「起回合」）。 */
    var onReadyToSend: (() -> Unit)? = null

    private var debounceJob: Job? = null
    private var stampJob: Job? = null
    private val timestamps = mutableListOf<Long>()
    private var timestampsLoaded = false

    /** 是否有未到期的等待窗（未答恢复/断网重试据此让位——窗回合将统一作答，防双答）。 */
    val windowPending: Boolean get() = debounceJob?.isActive == true

    /**
     * 窗已武装但尚未开火（健康线 2-5b·flush 判据）：与 [windowPending] 的区别——不依赖 Job 存活。
     * VM 清理时 viewModelScope 先于 onCleared 死亡，[debounceJob] 已被取消 → windowPending 恒假，
     * 但「有已受理消息在等回合」这个事实还在；控制器据本旗标在退出时立即起窗回合（合并等待的意义随离开消失）。
     */
    var windowArmed: Boolean = false
        private set

    /** 用户发了一条新消息（消息本身已由调用方落库）：记自适应样本 + 重置等待窗。 */
    fun enqueue() {
        debounceJob?.cancel()
        val previousStamp = stampJob
        stampJob = scope.launch {
            previousStamp?.join() // 样本记录严格按入队序（stamp job 永不被窗重置取消）
            if (!timestampsLoaded) {
                timestamps += persistence.loadSendTimestamps()
                timestampsLoaded = true
            }
            timestamps += nowMs()
            while (timestamps.size > MAX_STORED_TIMESTAMPS) timestamps.removeAt(0)
            persistence.saveSendTimestamps(timestamps.toList())
            computeAdaptiveWaitSeconds(timestamps)?.let { persistence.saveWaitSeconds(it) }
        }
        startWindow()
    }

    /**
     * 只重开一窗、**不记自适应样本**（健康线 2-10：窗到期回合与无头回合占坑冲突时让位重排——
     * 这不是用户发送，掺进样本会污染 P75 学习）。
     */
    fun requeueWindow() {
        debounceJob?.cancel()
        startWindow()
    }

    /** 开/重开等待窗：join 样本尾巴后读最新持久值计时，到期回调。 */
    private fun startWindow() {
        val stamp = stampJob
        windowArmed = true
        debounceJob = scope.launch {
            stamp?.join()
            val waitSeconds = (persistence.loadWaitSeconds() ?: DEFAULT_WAIT_SECONDS)
                .coerceIn(MIN_WAIT_SECONDS, MAX_WAIT_SECONDS)
            delayMs((waitSeconds * 1000f).toLong())
            windowArmed = false
            onReadyToSend?.invoke()
        }
    }

    /** 撤销未到期的窗（VM 清理时·flush 判据由调用方先读 [windowArmed]）；已入队样本保留。 */
    fun reset() {
        debounceJob?.cancel()
        debounceJob = null
        windowArmed = false
    }

    companion object {
        /** 等待窗范围与默认（用户拍板 0.5–5s 步 0.5 默认 1.5s·设置页滑块 C2 共用此单源）。 */
        const val MIN_WAIT_SECONDS = 0.5f
        const val MAX_WAIT_SECONDS = 5.0f
        const val DEFAULT_WAIT_SECONDS = 1.5f
        const val WAIT_STEP_SECONDS = 0.5f

        /** 自适应样本上限 / 连发判定阈 / 最少样本 / 补偿（iOS MessageDispatcher.swift:40-46 同值）。 */
        const val MAX_STORED_TIMESTAMPS = 100
        const val CONTINUOUS_THRESHOLD_SECONDS = 10.0
        const val MIN_CONTINUOUS_SAMPLES = 5
        const val WAIT_PADDING_SECONDS = 0.5
    }
}

/**
 * 自适应等待窗重算（纯函数·T1·iOS `recalculateAdaptiveDebounce` 移植）：相邻发送间隔 <10s 的样本 ≥5 个时
 * 取 P75 + 0.5s，钳 [0.5, 5]；样本不足返回 null（不更新设定）。
 */
internal fun computeAdaptiveWaitSeconds(timestampsMs: List<Long>): Float? {
    if (timestampsMs.size < 2) return null
    val intervals = ArrayList<Double>(timestampsMs.size - 1)
    for (i in 1 until timestampsMs.size) {
        val seconds = (timestampsMs[i] - timestampsMs[i - 1]) / 1000.0
        if (seconds < ChatMessageDispatcher.CONTINUOUS_THRESHOLD_SECONDS) intervals.add(seconds)
    }
    if (intervals.size < ChatMessageDispatcher.MIN_CONTINUOUS_SAMPLES) return null
    val p75 = percentile75(intervals.sorted())
    return (p75 + ChatMessageDispatcher.WAIT_PADDING_SECONDS).toFloat()
        .coerceIn(ChatMessageDispatcher.MIN_WAIT_SECONDS, ChatMessageDispatcher.MAX_WAIT_SECONDS)
}

/** P75 线性插值（纯函数·T1·iOS `percentile75` 移植·入参须已升序）。 */
internal fun percentile75(sortedValues: List<Double>): Double {
    val first = sortedValues.firstOrNull() ?: return ChatMessageDispatcher.DEFAULT_WAIT_SECONDS.toDouble()
    if (sortedValues.size == 1) return first
    val rank = 0.75 * (sortedValues.size - 1)
    val lowerIndex = kotlin.math.floor(rank).toInt()
    val upperIndex = kotlin.math.ceil(rank).toInt()
    if (lowerIndex == upperIndex) return sortedValues[lowerIndex]
    val fraction = rank - lowerIndex
    return sortedValues[lowerIndex] + (sortedValues[upperIndex] - sortedValues[lowerIndex]) * fraction
}
