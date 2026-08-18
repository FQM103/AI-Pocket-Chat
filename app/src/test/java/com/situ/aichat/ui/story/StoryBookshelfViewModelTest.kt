package com.situ.aichat.ui.story

import android.os.Looper
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryArchiver
import com.situ.aichat.story.StoryDeleter
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryReadingProgressStore
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.story.StoryUnlockNotificationScheduler
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 书架 VM T2（ST10-4 完结归档）：fresh 读守卫三分支（非完结写 COMPLETED+成功 toast / 生成中拒绝+忙 toast /
 * 已完结幂等静默）+ togglePause 既有语义回归钉。MockK 假仓库/任务管理器；viewModelScope 由 Robolectric
 * 主循环驱动（照 StorySettingsViewModelTest 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryBookshelfViewModelTest {

    private val repo = mockk<StoryRepository>()
    private val taskManager = mockk<StoryGenerationTaskManager>()
    private val readingProgressStore = mockk<StoryReadingProgressStore>(relaxed = true)
    private val unlockScheduler = mockk<StoryUnlockNotificationScheduler>(relaxed = true)

    private val activeGenerations =
        MutableStateFlow<Map<String, StoryGenerationTaskManager.GenerationProgress>>(emptyMap())

    private fun vm(): StoryBookshelfViewModel {
        every { repo.observeStoriesLite() } returns flowOf(emptyList())
        every { taskManager.activeGenerations } returns activeGenerations
        // ST11：归档守卫+写库已抽到共用 StoryArchiver（只搬不改）。这里注入**真** Archiver（吃同一批 mock），
        // 故归档五例的断言一字未改仍看着同一套行为（E9 回归锁）。删除同姿势注**真** Deleter（2026-08-04 撤闹钟卷）：
        // 删除两例仍断言 repo 真删，另可断言撤闹钟联动。
        return StoryBookshelfViewModel(
            repo, taskManager, readingProgressStore, StoryArchiver(repo, taskManager),
            StoryDeleter(repo, unlockScheduler),
        )
    }

    private fun story(id: String = "s1", status: String) = StoryEntity(id = id, title = "书", status = status)

    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    /** 订阅 toast 事件流（先订阅后触发——SharedFlow 无 replay，后订阅收不到）。 */
    private fun collectToasts(viewModel: StoryBookshelfViewModel, into: MutableList<Int>): CoroutineScope {
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { viewModel.toastEvents.collect { into += it } }
        shadowOf(Looper.getMainLooper()).idle()
        return scope
    }

    @Test
    fun 归档_等待选择书_写COMPLETED并发成功toast() {
        coEvery { repo.getStory("s1") } returns story(status = StoryStatus.WAITING_CHOICE)
        coEvery { repo.updateStatus(any(), any(), any()) } just Runs
        val viewModel = vm()
        val toasts = mutableListOf<Int>()
        val scope = collectToasts(viewModel, toasts)

        viewModel.archiveStory("s1")
        await("归档写库") { toasts.isNotEmpty() }

        coVerify(exactly = 1) { repo.updateStatus("s1", StoryStatus.COMPLETED, any()) }
        assertEquals(listOf(R.string.story_archived_toast), toasts)
        scope.cancel()
    }

    @Test
    fun 归档_fresh读到生成中_拒绝且零写库() {
        // 长按时刻卡片快照是 waitingChoice，fresh 读发现已进 generating（陈旧快照防御，PITFALLS 1b）
        coEvery { repo.getStory("s1") } returns story(status = StoryStatus.GENERATING)
        val viewModel = vm()
        val toasts = mutableListOf<Int>()
        val scope = collectToasts(viewModel, toasts)

        viewModel.archiveStory("s1")
        await("忙提示") { toasts.isNotEmpty() }

        coVerify(exactly = 0) { repo.updateStatus(any(), any(), any()) }
        assertEquals(listOf(R.string.story_archive_busy_toast), toasts)
        scope.cancel()
    }

    @Test
    fun 归档_手动生成任务活跃_拒绝且零写库() {
        coEvery { repo.getStory("s1") } returns story(status = StoryStatus.SERIALIZING)
        activeGenerations.value = mapOf("s1" to mockk())
        val viewModel = vm()
        val toasts = mutableListOf<Int>()
        val scope = collectToasts(viewModel, toasts)

        viewModel.archiveStory("s1")
        await("忙提示") { toasts.isNotEmpty() }

        coVerify(exactly = 0) { repo.updateStatus(any(), any(), any()) }
        assertEquals(listOf(R.string.story_archive_busy_toast), toasts)
        scope.cancel()
    }

    @Test
    fun 归档_已完结_幂等静默零写零toast() {
        coEvery { repo.getStory("s1") } returns story(status = StoryStatus.COMPLETED)
        var lookedUp = false
        coEvery { repo.getStory("s1") } answers { lookedUp = true; story(status = StoryStatus.COMPLETED) }
        val viewModel = vm()
        val toasts = mutableListOf<Int>()
        val scope = collectToasts(viewModel, toasts)

        viewModel.archiveStory("s1")
        await("fresh 读完成") { lookedUp }
        shadowOf(Looper.getMainLooper()).idle()

        coVerify(exactly = 0) { repo.updateStatus(any(), any(), any()) }
        assertTrue(toasts.isEmpty())
        scope.cancel()
    }

    @Test
    fun 删除_委托仓库级联删除_并撤该书解锁闹钟() {
        var deleted = false
        coEvery { repo.getChapterNumbers("s1") } returns listOf(2, 3)
        coEvery { repo.deleteStory("s1") } answers { deleted = true }
        val viewModel = vm()

        viewModel.deleteStory("s1")
        await("删除写库") { deleted }

        coVerify(exactly = 1) { repo.deleteStory("s1") }
        verify(exactly = 1) { unlockScheduler.cancelUnlocks("s1", listOf(2, 3)) }
    }

    @Test
    fun 删除_仓库抛错_吞掉不崩且可再删() {
        // 归档卡长按删除共用此路（2026-08-04 卷）：DB 异常只打日志，VM 存活、下一次删除照常；失败那本的闹钟一个不撤（书还在）。
        coEvery { repo.getChapterNumbers(any()) } returns emptyList()
        coEvery { repo.deleteStory("bad") } throws RuntimeException("disk io")
        var deleted = false
        coEvery { repo.deleteStory("ok") } answers { deleted = true }
        val viewModel = vm()

        viewModel.deleteStory("bad")
        shadowOf(Looper.getMainLooper()).idle()
        viewModel.deleteStory("ok")
        await("二次删除写库") { deleted }

        coVerify(exactly = 1) { repo.deleteStory("bad") }
        coVerify(exactly = 1) { repo.deleteStory("ok") }
        verify(exactly = 0) { unlockScheduler.cancelUnlocks("bad", any()) }
        verify(exactly = 1) { unlockScheduler.cancelUnlocks("ok", any()) }
    }

    @Test
    fun 暂停_仅连载中与已暂停互切_其它状态零写回归钉() {
        coEvery { repo.updateStatus(any(), any(), any()) } just Runs
        val viewModel = vm()

        var done = false
        coEvery { repo.updateStatus("s1", StoryStatus.PAUSED, any()) } answers { done = true }
        viewModel.togglePause(story(status = StoryStatus.SERIALIZING))
        await("暂停写库") { done }

        viewModel.togglePause(story(id = "s2", status = StoryStatus.WAITING_CHOICE))
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 0) { repo.updateStatus("s2", any(), any()) }
    }
}
