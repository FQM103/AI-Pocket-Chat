package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已存走向两写口 T2（图纸 2026-08-06「已存走向推进区状态化」§7 T2-1·边界 E6/E7/E8/E9/E10/E18）。
 *
 * 断言从图纸 §3.3/§3.4/§3.5 的规格独立反推，钉的是「到底写了什么、没写什么」：
 * - **覆盖** = `commitUserChoice(setSerializing = false, fromStatus = null)` 六个实参逐一钉死（状态零碰）；
 * - **撤回** = `withdrawUserChoice`，`revertToWaitingChoice` 只在「有选项章 && fresh 状态 SERIALIZING」为 true；
 * - **忙碌**双闸各一例，返回 false 且**一条库都不写**；
 * - 结局意图三注入点契约：两个写口都**不许**调 `clearEndingRequest`（反向断言）。
 */
class StoryDirectionEditorTest {

    private val repository = mockk<StoryRepository>(relaxed = true)
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)

    private val sid = "s1"
    private val chid = "ch1"
    private val now = 1_700_000_000_000L

    private fun givenStory(status: String = StoryStatus.SERIALIZING, exists: Boolean = true) {
        coEvery { repository.getStory(sid) } returns
            if (exists) StoryEntity(id = sid, status = status) else null
    }

    private fun givenActiveGenerations(vararg ids: String) {
        every { taskManager.activeGenerations } returns
            MutableStateFlow(ids.associateWith { mockk<StoryGenerationTaskManager.GenerationProgress>(relaxed = true) })
    }

    private suspend fun overwrite(text: String) =
        StoryDirectionEditor.overwrite(repository, taskManager, sid, chid, text, now)

    private suspend fun withdraw(hasChoice: Boolean) =
        StoryDirectionEditor.withdraw(repository, taskManager, sid, chid, hasChoice, now)

    /** 「一条库都没写」的统一反向断言（两个写口 + 状态直写全扫）。 */
    private fun assertNothingWritten() {
        coVerify(exactly = 0) { repository.commitUserChoice(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.withdrawUserChoice(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.updateStatus(any(), any(), any()) }
    }

    // ── E6 覆盖写：参数逐一钉死 ──

    /**
     * E6：覆盖 = 只改走向那一列。`setSerializing = false` 与 `fromStatus = null` 是本卷的机制锁——
     * 前者保证「改走向不把书的状态推来推去」，后者保证观测闸不被一次非状态写误触发。文本前后空白照 trim。
     */
    @Test
    fun E6_覆盖写_六实参逐一钉死_状态零碰() = runTest {
        givenStory()
        givenActiveGenerations()

        assertTrue(overwrite("  让她在温泉旅馆偶遇两人  "))

        coVerify(exactly = 1) {
            repository.commitUserChoice(
                storyId = sid,
                chapterId = chid,
                choice = "让她在温泉旅馆偶遇两人",
                nowMillis = now,
                setSerializing = false,
                fromStatus = null,
            )
        }
        coVerify(exactly = 0) { repository.updateStatus(any(), any(), any()) }
    }

    /** 已答的章照写不误（E17：并行清空后再覆盖，新走向直接成为当前走向，无害）。状态非连载态也照写。 */
    @Test
    fun 覆盖写_暂停态的书也只改走向不动状态() = runTest {
        givenStory(status = StoryStatus.PAUSED)
        givenActiveGenerations()

        assertTrue(overwrite("换个方向"))

        coVerify(exactly = 1) {
            repository.commitUserChoice(sid, chid, "换个方向", now, setSerializing = false, fromStatus = null)
        }
        coVerify(exactly = 0) { repository.updateStatus(any(), any(), any()) }
    }

    /** 空白文本 = 无事可做：返回 true（不报忙碌）且一条库都不写；UI 的 dirty 判据本就该先挡住。 */
    @Test
    fun 覆盖写_空白文本_成功且零写() = runTest {
        givenStory()
        givenActiveGenerations()

        assertTrue(overwrite(""))
        assertTrue(overwrite("   \n  "))

        assertNothingWritten()
    }

    // ── E18 故事查无 ──

    /** E18：故事已被删/查无 → 静默 no-op 成功（照 planFinale 的 `return@runCatching` 语义），零写。 */
    @Test
    fun E18_故事查无_两写口都静默成功且零写() = runTest {
        givenStory(exists = true)
        givenActiveGenerations()
        coEvery { repository.getStory(sid) } returns null

        assertTrue(overwrite("有内容"))
        assertTrue(withdraw(hasChoice = true))

        assertNothingWritten()
    }

    // ── E10 忙碌双闸 ──

    /** E10 闸一：书的状态就是 generating（手动路已写库）→ 拒写，返回 false。 */
    @Test
    fun E10_状态generating_两写口都拒写() = runTest {
        givenStory(status = StoryStatus.GENERATING)
        givenActiveGenerations()

        assertFalse(overwrite("生成中还想改"))
        assertFalse(withdraw(hasChoice = true))

        assertNothingWritten()
    }

    /** E10 闸二：状态还没写到 generating，但 TaskManager 里已有本书的活跃任务 → 同样拒写。 */
    @Test
    fun E10_活跃生成任务含本书_两写口都拒写() = runTest {
        givenStory(status = StoryStatus.SERIALIZING)
        givenActiveGenerations(sid)

        assertFalse(overwrite("生成中还想改"))
        assertFalse(withdraw(hasChoice = true))

        assertNothingWritten()
    }

    /** 反向钉：活跃任务是**别的书**时不许误伤。 */
    @Test
    fun 别的书在生成_本书照常可写() = runTest {
        givenStory()
        givenActiveGenerations("another-story")

        assertTrue(overwrite("照写"))

        coVerify(exactly = 1) {
            repository.commitUserChoice(sid, chid, "照写", now, setSerializing = false, fromStatus = null)
        }
    }

    // ── E7/E8/E9 撤回三分支 ──

    /** E7：无选项章（关了章末选项的书）→ 清走向、**状态不动**（撤回后回态 A，追更照常按自然发展写）。 */
    @Test
    fun E7_撤回_无选项章_不回转状态() = runTest {
        givenStory(status = StoryStatus.SERIALIZING)
        givenActiveGenerations()

        assertTrue(withdraw(hasChoice = false))

        coVerify(exactly = 1) {
            repository.withdrawUserChoice(
                storyId = sid,
                chapterId = chid,
                revertToWaitingChoice = false,
                fromStatus = StoryStatus.SERIALIZING,
                nowMillis = now,
            )
        }
    }

    /**
     * E8：有选项章 + 连载中 → 同事务回转 waitingChoice。
     * 不回转的话追更自动路（只捞 SERIALIZING 的书）会对「重新待答的那个选择」裸跑生成。
     */
    @Test
    fun E8_撤回_有选项章且连载中_回转等待选择() = runTest {
        givenStory(status = StoryStatus.SERIALIZING)
        givenActiveGenerations()

        assertTrue(withdraw(hasChoice = true))

        coVerify(exactly = 1) {
            repository.withdrawUserChoice(
                storyId = sid,
                chapterId = chid,
                revertToWaitingChoice = true,
                fromStatus = StoryStatus.SERIALIZING,
                nowMillis = now,
            )
        }
    }

    /**
     * E9：有选项章但书是暂停 / 生成失败 → **只清选择不回转**。
     * 这两条边在转换表里不合法，且语义上撤回 ≠ 恢复连载。
     */
    @Test
    fun E9_撤回_有选项章但暂停或失败态_不回转() = runTest {
        for (status in listOf(StoryStatus.PAUSED, StoryStatus.GENERATION_FAILED)) {
            val repo = mockk<StoryRepository>(relaxed = true)
            val tm = mockk<StoryGenerationTaskManager>(relaxed = true)
            coEvery { repo.getStory(sid) } returns StoryEntity(id = sid, status = status)
            every { tm.activeGenerations } returns MutableStateFlow(emptyMap())

            assertTrue(StoryDirectionEditor.withdraw(repo, tm, sid, chid, chapterHasChoice = true, nowMillis = now))

            coVerify(exactly = 1) {
                repo.withdrawUserChoice(sid, chid, revertToWaitingChoice = false, fromStatus = status, nowMillis = now)
            }
        }
    }

    /** 等待选择态的书撤回：既然已经在等选择了，也没有可回转的（revert 只认 SERIALIZING 一档）。 */
    @Test
    fun 撤回_已是等待选择态_不重复回转() = runTest {
        givenStory(status = StoryStatus.WAITING_CHOICE)
        givenActiveGenerations()

        assertTrue(withdraw(hasChoice = true))

        coVerify(exactly = 1) {
            repository.withdrawUserChoice(
                sid, chid, revertToWaitingChoice = false, fromStatus = StoryStatus.WAITING_CHOICE, nowMillis = now,
            )
        }
    }

    // ── 契约反向钉 ──

    /**
     * 结局意图「恰三注入点」契约（commitPendingChoice / forceContinue / rewrite）：
     * 本卷两个新写口**一个都不许**碰它——改走向/撤走向不是生成触发动作，真要生成时 forceContinue 会清。
     * 顺带钉：两写口都不触发生成。
     */
    @Test
    fun 两写口都不清结局意图_也不触发生成() = runTest {
        givenStory()
        givenActiveGenerations()

        assertTrue(overwrite("新走向"))
        assertTrue(withdraw(hasChoice = true))

        coVerify(exactly = 0) { repository.clearEndingRequest(any(), any()) }
        coVerify(exactly = 0) { taskManager.startGeneration(any()) }
        coVerify(exactly = 0) { repository.updateStory(any()) }
    }
}
