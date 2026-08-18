package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StoryStateTransitions 观测闸真值表（T1·ST3a）。
 *
 * 期望集**从写点勘察独立反推**（非照搬实现的 map）：逐 from 断言其合法 to 全集，
 * 并覆盖非法样例、from=null 放行、未知状态串。Log.w 走 returnDefaultValues（build.gradle testOptions），
 * 纯 JVM 下为 no-op，不需 mockkStatic。
 */
class StoryStateTransitionsTest {

    private val allStatuses = listOf(
        StoryStatus.SERIALIZING,
        StoryStatus.WAITING_CHOICE,
        StoryStatus.COMPLETED,
        StoryStatus.PAUSED,
        StoryStatus.GENERATING,
        StoryStatus.GENERATION_FAILED,
    )

    /** 逐 from 断言合法集 = 期望集（期望集独立源自各写点勘察，见每例注释）。 */
    private fun assertLegalSet(from: String, expectedLegal: Set<String>) {
        for (to in allStatuses) {
            assertEquals(
                "$from → $to",
                to in expectedLegal,
                StoryStateTransitions.check(from, to, site = "test"),
            )
        }
    }

    // ── 逐 from 真值表 ──

    @Test
    fun `serializing 六向全通`() {
        // 起生成(generating)/togglePause(paused)/自动连载路 materialize(serializing|waitingChoice|completed)/
        // handleFailure 边缘(generationFailed)/复位类自环(serializing)
        assertLegalSet(
            StoryStatus.SERIALIZING,
            setOf(
                StoryStatus.SERIALIZING,
                StoryStatus.WAITING_CHOICE,
                StoryStatus.COMPLETED,
                StoryStatus.GENERATING,
                StoryStatus.PAUSED,
                StoryStatus.GENERATION_FAILED,
            ),
        )
    }

    @Test
    fun `waitingChoice 去 serializing 完结归档 或失败收尾`() {
        // 落选择/继续推进/请求结局/重写 → serializing；materialize 置 waitingChoice 后尾部异常 → generationFailed；
        // 书架长按「完结归档」（ST10-4 archiveStory）→ completed
        assertLegalSet(
            StoryStatus.WAITING_CHOICE,
            setOf(StoryStatus.SERIALIZING, StoryStatus.COMPLETED, StoryStatus.GENERATION_FAILED),
        )
    }

    @Test
    fun `completed 只去 serializing 与失败收尾`() {
        // 开启续篇(continueStory)/重写末章 → serializing；materialize 置 completed 后尾部异常 → generationFailed
        assertLegalSet(
            StoryStatus.COMPLETED,
            setOf(StoryStatus.SERIALIZING, StoryStatus.GENERATION_FAILED),
        )
    }

    @Test
    fun `paused 恢复连载或完结归档`() {
        // togglePause/设置页恢复/暂停中重写 → serializing；书架「完结归档」（ST10-4）→ completed；仍不可直接起生成
        assertLegalSet(StoryStatus.PAUSED, setOf(StoryStatus.SERIALIZING, StoryStatus.COMPLETED))
    }

    @Test
    fun `generating 去三种落库结果或失败`() {
        // 手动路 materialize decideStatus → serializing|waitingChoice|completed；handleFailure/recoverStuck → generationFailed
        assertLegalSet(
            StoryStatus.GENERATING,
            setOf(
                StoryStatus.SERIALIZING,
                StoryStatus.WAITING_CHOICE,
                StoryStatus.COMPLETED,
                StoryStatus.GENERATION_FAILED,
            ),
        )
    }

    @Test
    fun `generationFailed 重试或完结归档`() {
        // retryGeneration → serializing；retry 持旧句柄直接起生成 → generating；书架「完结归档」（ST10-4）→ completed
        assertLegalSet(
            StoryStatus.GENERATION_FAILED,
            setOf(StoryStatus.SERIALIZING, StoryStatus.GENERATING, StoryStatus.COMPLETED),
        )
    }

    // ── 非法样例（显式钉几个关键红线）──

    @Test
    fun `非法样例逐项为 false`() {
        // 暂停态不许直接起生成（须先恢复连载）
        assertFalse(StoryStateTransitions.check(StoryStatus.PAUSED, StoryStatus.GENERATING, "test"))
        // 完结态不许倒回等待选择
        assertFalse(StoryStateTransitions.check(StoryStatus.COMPLETED, StoryStatus.WAITING_CHOICE, "test"))
        // 生成中不许被暂停（书架 togglePause 的 else->return 已挡）
        assertFalse(StoryStateTransitions.check(StoryStatus.GENERATING, StoryStatus.PAUSED, "test"))
        // 等待选择不许绕过落选择直接起生成（须先 commitUserChoice 置 serializing）
        assertFalse(StoryStateTransitions.check(StoryStatus.WAITING_CHOICE, StoryStatus.GENERATING, "test"))
        // 失败态不许被暂停
        assertFalse(StoryStateTransitions.check(StoryStatus.GENERATION_FAILED, StoryStatus.PAUSED, "test"))
        // generating 自环不存在（activeTasks putIfAbsent 去重）
        assertFalse(StoryStateTransitions.check(StoryStatus.GENERATING, StoryStatus.GENERATING, "test"))
    }

    // ── from=null 放行 ──

    @Test
    fun `from 为 null 一律放行`() {
        for (to in allStatuses) {
            assertTrue("null → $to", StoryStateTransitions.check(null, to, "test"))
        }
    }

    // ── 未知状态串 ──

    @Test
    fun `未知 from 或 to 视为非法`() {
        assertFalse(StoryStateTransitions.check("bogus", StoryStatus.SERIALIZING, "test"))
        assertFalse(StoryStateTransitions.check(StoryStatus.SERIALIZING, "bogus", "test"))
        assertFalse(StoryStateTransitions.check("", StoryStatus.SERIALIZING, "test"))
    }
}
