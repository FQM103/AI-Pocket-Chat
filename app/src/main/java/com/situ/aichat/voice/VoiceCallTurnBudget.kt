package com.situ.aichat.voice

import com.situ.aichat.data.model.ThinkingBudgetLevel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 通话回合的响应预算（C3 通话体验加固 2026-07-12）。通话是强实时场景：真人 5 秒无回应就会「喂？」，
 * 而全局 OkHttp readTimeout=60s、思考模型可长考数十秒——两者按文字聊天的耐心设计，在通话里都是体感灾难。
 * 两个纯逻辑件（T1 直测，[VoiceCallTurnService] 织入）：
 *
 *  - [collectWithFirstEventBudget]：收流加「首事件看门狗」——预算内连一个流事件（含 reasoning）都没等到
 *    才判死；思考模型边思考边吐 reasoning 属于「服务器活着」，绝不误杀，真僵死才触发。
 *  - [clampThinkingForCall]：思考强度钳到 LOW（OFF 保持关闭，绝不反向升档）——通话里保智商、压首字延迟。
 */
internal object VoiceCallTurnBudget {

    /** 通话首流事件预算：20s 一个事件都没有（连 reasoning 都没吐）→ 判本轮死，走失败兜底。 */
    const val FIRST_EVENT_BUDGET_MS = 20_000L

    /** 预算内没等到任何流事件。非 CancellationException：会被调用方的错误路径记日志并走失败兜底。 */
    class FirstStreamEventTimeout(budgetMs: Long) :
        Exception("voice turn: no stream event within ${budgetMs}ms")

    /**
     * Collect [flow]，但在收到第一个元素前挂一条 [budgetMs] 看门狗：超时抛 [FirstStreamEventTimeout]
     * 并取消收流；首个元素到达即撤狗，之后交还给底层超时（OkHttp readTimeout）兜底。外部取消原样传播。
     */
    suspend fun <T> collectWithFirstEventBudget(flow: Flow<T>, budgetMs: Long, onEach: suspend (T) -> Unit) {
        coroutineScope {
            val watchdog = launch {
                delay(budgetMs)
                throw FirstStreamEventTimeout(budgetMs)
            }
            flow.collect { value ->
                watchdog.cancel() // 幂等；首个元素撤狗
                onEach(value)
            }
            watchdog.cancel() // 空流正常结束也要撤，否则 coroutineScope 等狗到点自爆
        }
    }

    /** 通话回合思考强度：OFF 保持、其余（AUTO/LOW/MEDIUM/HIGH）一律钳到 LOW——只降不升。 */
    fun clampThinkingForCall(level: ThinkingBudgetLevel): ThinkingBudgetLevel =
        if (level == ThinkingBudgetLevel.OFF) ThinkingBudgetLevel.OFF else ThinkingBudgetLevel.LOW
}
