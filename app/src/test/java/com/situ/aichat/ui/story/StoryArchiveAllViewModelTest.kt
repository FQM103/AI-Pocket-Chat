package com.situ.aichat.ui.story

import android.os.Looper
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryDeleter
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.story.StoryUnlockNotificationScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 结局档案全览 VM T2（2026-08-04 归档长按删除卷）：archived 流只含已完结（读侧回归钉）+
 * deleteStory 委托共用删除件（级联删库+撤解锁闹钟·真 [StoryDeleter] 吃同一批 mock）+ 仓库抛错吞掉不崩
 * （VM 存活可再删）。MockK 假仓库，viewModelScope 由 Robolectric 主循环驱动（照 StoryBookshelfViewModelTest 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryArchiveAllViewModelTest {

    private val repo = mockk<StoryRepository>()
    private val unlockScheduler = mockk<StoryUnlockNotificationScheduler>(relaxed = true)

    private fun story(id: String, status: String) = StoryEntity(id = id, title = "书$id", status = status)

    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    @Test
    fun 归档流_只含已完结() {
        every { repo.observeStoriesLite() } returns flowOf(
            listOf(
                story("s1", StoryStatus.COMPLETED),
                story("s2", StoryStatus.SERIALIZING),
                story("s3", StoryStatus.PAUSED),
                story("s4", StoryStatus.COMPLETED),
            ),
        )
        val viewModel = StoryArchiveAllViewModel(repo, StoryDeleter(repo, unlockScheduler))
        // WhileSubscribed 惰性流：先挂订阅者再等值到位。
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { viewModel.archived.collect {} }
        await("archived 流出值") { viewModel.archived.value.isNotEmpty() }

        assertEquals(listOf("s1", "s4"), viewModel.archived.value.map { it.id })
        scope.cancel()
    }

    @Test
    fun 删除_委托仓库级联删除_并撤该书解锁闹钟() {
        every { repo.observeStoriesLite() } returns flowOf(emptyList())
        var deleted = false
        coEvery { repo.getChapterNumbers("s1") } returns listOf(4)
        coEvery { repo.deleteStory("s1") } answers { deleted = true }
        val viewModel = StoryArchiveAllViewModel(repo, StoryDeleter(repo, unlockScheduler))

        viewModel.deleteStory("s1")
        await("删除写库") { deleted }

        coVerify(exactly = 1) { repo.deleteStory("s1") }
        verify(exactly = 1) { unlockScheduler.cancelUnlocks("s1", listOf(4)) }
    }

    @Test
    fun 删除_仓库抛错_吞掉不崩且可再删() {
        every { repo.observeStoriesLite() } returns flowOf(emptyList())
        coEvery { repo.getChapterNumbers(any()) } returns emptyList()
        coEvery { repo.deleteStory("bad") } throws RuntimeException("disk io")
        var deleted = false
        coEvery { repo.deleteStory("ok") } answers { deleted = true }
        val viewModel = StoryArchiveAllViewModel(repo, StoryDeleter(repo, unlockScheduler))

        viewModel.deleteStory("bad")
        shadowOf(Looper.getMainLooper()).idle()
        viewModel.deleteStory("ok")
        await("二次删除写库") { deleted }

        coVerify(exactly = 1) { repo.deleteStory("bad") }
        coVerify(exactly = 1) { repo.deleteStory("ok") }
        // 失败那本闹钟一个不撤（书还在网格上，追更提醒不能丢）；成功那本照撤。
        verify(exactly = 0) { unlockScheduler.cancelUnlocks("bad", any()) }
        verify(exactly = 1) { unlockScheduler.cancelUnlocks("ok", any()) }
    }
}
