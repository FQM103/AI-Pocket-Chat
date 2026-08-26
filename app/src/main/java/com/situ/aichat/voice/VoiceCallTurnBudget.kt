package com.situ.aichat.voice

import com.situ.aichat.data.model.ThinkingBudgetLevel
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 通话回合的响应预算（C3 通话体验加固 2026-07-12；看门狗 2026-08-25 改行级活性）。通话是强实时场景：
 * 真人 5 秒无回应就会「喂？」，而全局 OkHttp readTimeout=60s、思考模型可长考数十秒——两者按文字聊天的
 * 耐心设计，在通话里都是体感灾难。两个纯逻辑件（T1 直测，[VoiceCallTurnService] 织入）：
 *
 *  - [collectWithFirstEventBudget]：收流加「首流事件看门狗」——活性按 **SSE 行级** 计（数据行、keep-alive
 *    注释行、空分隔行，行在流动就是服务器活着），静默（连一行都没有）**累计**满 [FIRST_EVENT_BUDGET_MS]
 *    才判死；思考模型闭麦长考、服务商只发 keep-alive 注释行时绝不误杀，真僵死才触发。首个流事件到达即
 *    永久撤狗，之后交还底层超时（OkHttp readTimeout）兜底。
 *  - [clampThinkingForCall]：思考强度钳到 LOW（OFF 保持关闭，绝不反向升档）——通话里保智商、压首字延迟。
 */
internal object VoiceCallTurnBudget {

    /** 通话首流事件预算：静默（连一行 SSE 都没有）累计 20s → 判本轮死，走失败兜底。 */
    const val FIRST_EVENT_BUDGET_MS = 20_000L

    /** 看门狗轮询步长：每秒核对一次活性计数；真实时间判死过冲 ≤1 步长。 */
    const val LIVENESS_POLL_MS = 1_000L

    /** 预算内没等到任何流事件。非 CancellationException：会被调用方的错误路径记日志并走失败兜底。 */
    class FirstStreamEventTimeout(budgetMs: Long) :
        Exception("voice turn: no stream event within ${budgetMs}ms")

    /**
     * Collect the flow produced by [streamFactory]（工厂须把 [onLiveness] 喂狗回调接到 SSE 行级活性信号上），
     * 但在收到第一个元素前挂一条 [budgetMs] 静默看门狗：每 [LIVENESS_POLL_MS] 核对一次活性计数，有活性即
     * 清零累计、静默累计满 [budgetMs] 抛 [FirstStreamEventTimeout] 并取消收流；首个元素到达即永久撤狗，
     * 之后交还给底层超时（OkHttp readTimeout）兜底。外部取消原样传播。
     */
    suspend fun <T> collectWithFirstEventBudget(
        budgetMs: Long,
        streamFactory: (onLiveness: () -> Unit) -> Flow<T>,
        onEach: suspend (T) -> Unit,
    ) {
        val livenessTicks = AtomicLong(0L)
        val flow = streamFactory { livenessTicks.incrementAndGet() }
        coroutineScope {
            val watchdog = launch {
                var lastSeen = livenessTicks.get()
                var silentMs = 0L
                while (true) {
                    delay(LIVENESS_POLL_MS)
                    val now = livenessTicks.get()
                    if (now != lastSeen) {
                        lastSeen = now
                        silentMs = 0L
                    } else {
                        silentMs += LIVENESS_POLL_MS
                        if (silentMs >= budgetMs) throw FirstStreamEventTimeout(budgetMs)
                    }
                }
            }
            flow.collect { value ->
                watchdog.cancel() // 幂等；首个元素永久撤狗
                onEach(value)
            }
            watchdog.cancel() // 空流正常结束也要撤，否则 coroutineScope 等狗自爆
        }
    }

    /** 通话回合思考强度：OFF 保持、其余（AUTO/LOW/MEDIUM/HIGH）一律钳到 LOW——只降不升。 */
    fun clampThinkingForCall(level: ThinkingBudgetLevel): ThinkingBudgetLevel =
        if (level == ThinkingBudgetLevel.OFF) ThinkingBudgetLevel.OFF else ThinkingBudgetLevel.LOW
}
