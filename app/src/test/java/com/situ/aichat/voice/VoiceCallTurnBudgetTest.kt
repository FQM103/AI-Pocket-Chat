package com.situ.aichat.voice

import com.situ.aichat.data.model.ThinkingBudgetLevel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T1（C3 通话响应预算）：规格独立反推——
 *  - 看门狗只管「第一个流事件之前」：预算内颗粒无收 → [VoiceCallTurnBudget.FirstStreamEventTimeout]；
 *    首事件（含 reasoning）到达即撤狗，之后再久的静默交还底层超时；
 *  - 外部取消原样传播（挂断/思考中打断不得被误报成超时）；空流正常结束不得自爆；
 *  - 思考档钳制：OFF 保持、其余全部只降到 LOW、绝不反向升档。
 */
class VoiceCallTurnBudgetTest {

    @Test
    fun `no stream event within budget - throws FirstStreamEventTimeout`() = runTest {
        val never = flow<Int> { awaitCancellation() }
        try {
            VoiceCallTurnBudget.collectWithFirstEventBudget(never, budgetMs = 20_000L) {}
            fail("应在预算耗尽时抛 FirstStreamEventTimeout")
        } catch (e: VoiceCallTurnBudget.FirstStreamEventTimeout) {
            // 预期：20s 虚拟时间内一个事件都没有。
        }
    }

    @Test
    fun `first event arrives - watchdog disarmed, later silence is not our business`() = runTest {
        val collected = mutableListOf<Int>()
        val slowTail = flow {
            emit(1) // 首事件在预算内到达 → 撤狗
            delay(90_000) // 远超预算的静默（现实=思考后慢慢生成）——不许再触发看门狗
            emit(2)
        }
        VoiceCallTurnBudget.collectWithFirstEventBudget(slowTail, budgetMs = 20_000L) { collected += it }
        assertEquals(listOf(1, 2), collected)
    }

    @Test
    fun `external cancellation propagates as cancellation, never masked as timeout`() = runTest {
        val never = flow<Int> { awaitCancellation() }
        val job = launch {
            VoiceCallTurnBudget.collectWithFirstEventBudget(never, budgetMs = 20_000L) {}
        }
        advanceTimeBy(1_000)
        job.cancel() // = 挂断 / 思考中被用户打断
        job.join()
        assertTrue("外部取消必须是干净取消，不是超时失败", job.isCancelled)
    }

    @Test
    fun `empty flow completing normally does not blow up on the watchdog`() = runTest {
        VoiceCallTurnBudget.collectWithFirstEventBudget(emptyFlow<Int>(), budgetMs = 20_000L) {
            fail("空流不应回调")
        }
    }

    @Test
    fun `thinking level clamps down to LOW and never re-enables OFF`() {
        assertEquals(ThinkingBudgetLevel.OFF, VoiceCallTurnBudget.clampThinkingForCall(ThinkingBudgetLevel.OFF))
        assertEquals(ThinkingBudgetLevel.LOW, VoiceCallTurnBudget.clampThinkingForCall(ThinkingBudgetLevel.AUTO))
        assertEquals(ThinkingBudgetLevel.LOW, VoiceCallTurnBudget.clampThinkingForCall(ThinkingBudgetLevel.LOW))
        assertEquals(ThinkingBudgetLevel.LOW, VoiceCallTurnBudget.clampThinkingForCall(ThinkingBudgetLevel.MEDIUM))
        assertEquals(ThinkingBudgetLevel.LOW, VoiceCallTurnBudget.clampThinkingForCall(ThinkingBudgetLevel.HIGH))
    }
}
