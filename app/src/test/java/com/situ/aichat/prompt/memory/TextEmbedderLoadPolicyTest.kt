package com.situ.aichat.prompt.memory

import android.content.Context
import android.content.res.AssetManager
import com.situ.aichat.prompt.memory.TextEmbedder.LoadState
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [TextEmbedder] 加载策略断言：
 * - #2 瞬态/永久失败分流：内存不足（[OutOfMemoryError]）为瞬态、不计入「连续 [3] 次即本进程放弃」的闩，
 *   下次调用自然重试；asset 缺失/损坏、模型不兼容等确定性失败为永久、逐次累加。断言从设计独立反推
 *   （见 [TextEmbedder.isTransientFailure] / [TextEmbedder.nextLoadFailureCount] 的纯函数契约）。
 * - #3 加载状态被动记录：读取 [TextEmbedder.loadState] **绝不触发**懒加载（区别于会触发加载的 isAvailable）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextEmbedderLoadPolicyTest {

    // ---- #2 瞬态 / 永久失败分流（纯函数 T1）----

    @Test fun oom_isTransient() {
        assertTrue(TextEmbedder.isTransientFailure(OutOfMemoryError("low mem")))
    }

    @Test fun wrappedOom_isTransient() {
        // 被包裹的 OOM（cause）也判为瞬态。
        assertTrue(TextEmbedder.isTransientFailure(RuntimeException("wrap", OutOfMemoryError())))
    }

    @Test fun missingAsset_isPermanent() {
        assertFalse(TextEmbedder.isTransientFailure(IOException("asset missing")))
    }

    @Test fun genericError_isPermanent() {
        assertFalse(TextEmbedder.isTransientFailure(IllegalStateException("bad model output")))
    }

    @Test fun transientFailures_doNotConsumeLatchBudget() {
        var count = 0
        repeat(5) { count = TextEmbedder.nextLoadFailureCount(count, OutOfMemoryError()) }
        assertEquals(0, count)
    }

    @Test fun permanentFailures_incrementEachTime() {
        var count = 0
        repeat(3) { count = TextEmbedder.nextLoadFailureCount(count, IOException()) }
        assertEquals(3, count)
    }

    @Test fun transientDoesNotStealBudgetFromPermanent() {
        // 关键回归：一次内存打盹（瞬态）不该缩短永久失败的放弃预算。
        var count = 0
        repeat(5) { count = TextEmbedder.nextLoadFailureCount(count, OutOfMemoryError()) }
        assertEquals("瞬态失败不应计入永久闩", 0, count)
        repeat(2) { count = TextEmbedder.nextLoadFailureCount(count, IOException()) }
        assertEquals(2, count) // 两次永久，尚未到放弃阈值 3
        count = TextEmbedder.nextLoadFailureCount(count, IOException())
        assertEquals("第 3 次永久失败才达放弃阈值", 3, count)
    }

    // ---- #2 接线集成：ensureLoaded 的 catch 真的用了分流（防"改回 loadFailures++ 仍全绿"的假绿）----

    /** 用 MockK 让 asset 打开抛 [IOException]（永久失败）：连触发 3 次加载，验证计数 1→2→3 且第 3 次翻 FAILED、之后不再重试。 */
    @Test fun ensureLoad_permanentFailure_incrementsAndLatchesFailed() {
        val embedder = TextEmbedder(contextThrowing(IOException("asset missing")))

        embedder.isAvailable // 1
        assertEquals(1, embedder.loadFailures)
        assertEquals(LoadState.NOT_ATTEMPTED, embedder.loadState.value)
        embedder.isAvailable // 2
        assertEquals(2, embedder.loadFailures)
        assertEquals(LoadState.NOT_ATTEMPTED, embedder.loadState.value)
        embedder.isAvailable // 3 → 达阈值放弃
        assertEquals(3, embedder.loadFailures)
        assertEquals(LoadState.FAILED, embedder.loadState.value)
        embedder.isAvailable // 闩死后不再重试、计数不再增长
        assertEquals(3, embedder.loadFailures)
    }

    /** 用 MockK 让 asset 打开抛 [OutOfMemoryError]（瞬态失败）：连触发 5 次，验证永不计入永久闩、状态不翻 FAILED。 */
    @Test fun ensureLoad_transientFailure_neverLatches() {
        val embedder = TextEmbedder(contextThrowing(OutOfMemoryError("oom")))

        repeat(5) { embedder.isAvailable }
        assertEquals("瞬态失败不应计入永久闩", 0, embedder.loadFailures)
        assertEquals(LoadState.NOT_ATTEMPTED, embedder.loadState.value)
    }

    /** 构造一个 [Context]，其 assets.open 恒抛 [t]——把加载引向 ensureLoaded 的 catch 分流路径。 */
    private fun contextThrowing(t: Throwable): Context {
        val assets = mockk<AssetManager>()
        every { assets.open(any()) } throws t
        return mockk<Context>().also { every { it.assets } returns assets }
    }

    // ---- #3 加载状态被动记录（读取不触发加载）----

    @Test fun loadState_initiallyNotAttempted_andReadingDoesNotTriggerLoad() {
        val embedder = TextEmbedder(RuntimeEnvironment.getApplication())
        // 只读 loadState、绝不调 isAvailable/embed → 读取是被动的，不触发懒加载，状态恒为 NOT_ATTEMPTED。
        assertEquals(LoadState.NOT_ATTEMPTED, embedder.loadState.value)
        assertEquals(LoadState.NOT_ATTEMPTED, embedder.loadState.value)
        // 强证被动：只读状态未发生任何加载尝试（失败计数仍为 0）。
        assertEquals(0, embedder.loadFailures)
    }
}
