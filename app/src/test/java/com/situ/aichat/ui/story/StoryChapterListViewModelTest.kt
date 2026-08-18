package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryReadingProgressStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 章节列表 VM T2（2026-08-04 删除撤闹钟卷·深链兜底）：解锁通知深链落到已删书 → [storyMissing] 置位
 * （屏幕据此 Toast + 体面退回）；书存在 → 恒 false（正常打开零打扰）。期望从缺陷现象独立反推：
 * 修复前深链落死书停在假「生成中」空态。MockK 假仓库，viewModelScope 由 Robolectric 主循环驱动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryChapterListViewModelTest {

    private val repo = mockk<StoryRepository>()
    private val taskManager = mockk<StoryGenerationTaskManager>()
    private val readingProgressStore = mockk<StoryReadingProgressStore>(relaxed = true)
    private val activeGenerations =
        MutableStateFlow<Map<String, StoryGenerationTaskManager.GenerationProgress>>(emptyMap())

    private fun vm(storyId: String): StoryChapterListViewModel {
        every { repo.observeStory(storyId) } returns flowOf(null)
        every { taskManager.activeGenerations } returns activeGenerations
        return StoryChapterListViewModel(
            SavedStateHandle(mapOf("storyId" to storyId)), repo, taskManager, readingProgressStore,
        )
    }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    @Test
    fun 深链落已删书_storyMissing置位() {
        coEvery { repo.getStory("dead") } returns null
        val viewModel = vm("dead")
        await("missing 置位") { viewModel.storyMissing.value }
    }

    @Test
    fun 书存在_storyMissing恒false() {
        var checked = false
        coEvery { repo.getStory("s1") } answers { checked = true; StoryEntity(id = "s1", title = "书") }
        val viewModel = vm("s1")

        await("查证跑完") { checked }
        repeat(20) { shadowOf(Looper.getMainLooper()).idle() }
        assertFalse("书在架上绝不误弹「已删除」", viewModel.storyMissing.value)
    }
}
