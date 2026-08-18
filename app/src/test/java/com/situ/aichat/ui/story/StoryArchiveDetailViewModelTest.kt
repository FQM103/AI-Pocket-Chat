package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryEndingType
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 结局档案 VM 数据装配 T2（ST8·契约 §11「档案卡数据装配：章数/选择数/结局类型映射」）。
 * MockK 假掉仓库·Robolectric 驱动 viewModelScope（照 StoryReaderViewModelTest 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryArchiveDetailViewModelTest {

    private val repository = mockk<StoryRepository>()
    private val generationService = mockk<StoryGenerationService>(relaxed = true)
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)

    private fun vm(storyId: String = "s1") = StoryArchiveDetailViewModel(
        SavedStateHandle(mapOf("storyId" to storyId)), repository, generationService, taskManager,
    )

    @Test
    fun 装配_章数选择数结局类型映射入uiState() = runBlocking {
        val story = StoryEntity(
            id = "s1", title = "书", genre = "言情", writingStyle = "严肃文学",
            finalEndingType = StoryEndingType.CUSTOM,
        )
        coEvery { repository.getStory("s1") } returns story
        coEvery { repository.getChapters("s1") } returns listOf(
            StoryChapterEntity(id = "c1", storyId = "s1", chapterNumber = 1, content = "第一章。", userChoice = "A"),
            StoryChapterEntity(id = "c2", storyId = "s1", chapterNumber = 2, content = "尾声。他们重逢了。", userChoice = null),
        )

        val vm = vm()
        shadowOf(Looper.getMainLooper()).idle()

        val state = vm.uiState.value
        assertNotNull("uiState 应装配完成", state)
        assertEquals(2, state!!.digest.chapterCount)
        assertEquals(1, state.digest.choiceCount)                 // 仅 c1 有 userChoice
        assertEquals(StoryEndingType.CUSTOM, state.digest.endingType)
        assertEquals("尾声。他们重逢了。", state.digest.quote)      // 末章(c2)末段（短段整段返回）
    }

    @Test
    fun 装配_无该故事时uiState保持null() = runBlocking {
        coEvery { repository.getStory("missing") } returns null

        val vm = vm(storyId = "missing")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(null, vm.uiState.value)
    }

    @Test
    fun 导出txt_装配后按格式串产出() = runBlocking {
        val story = StoryEntity(id = "s1", title = "书名", genre = "言情", writingStyle = "严肃文学")
        coEvery { repository.getStory("s1") } returns story
        coEvery { repository.getChapters("s1") } returns listOf(
            StoryChapterEntity(id = "c1", storyId = "s1", chapterNumber = 1, title = "初见", content = "[mood:warm]相遇。", userChoice = "打招呼"),
        )

        val vm = vm()
        shadowOf(Looper.getMainLooper()).idle()

        val txt = vm.buildTxt("第 %1\$d 话 · %2\$s", "▶ 你的选择：%1\$s")
        assertNotNull(txt)
        assert(txt!!.startsWith("书名"))
        assert(txt.contains("第 1 话 · 初见"))
        assert(!txt.contains("[mood:"))                            // 标签剥净
        assert(txt.contains("▶ 你的选择：打招呼"))
    }

    // ── ST11 §3.5 档案救济：「继续写这个故事」 ──

    /**
     * 存量已完结书请回在读区：走 continueStory（照设置页先例）→ 用重读的 fresh 快照起生成 → 发成功事件。
     * 期望独立反推自 §3.5 与 E7：判定链改动不回溯，被旧规则误关进档案的书必须有救济路。
     */
    @Test
    fun ST11_继续写_经continueStory复活并起生成且发成功事件() = runBlocking {
        val story = StoryEntity(id = "s1", title = "书", status = StoryStatus.COMPLETED)
        coEvery { repository.getStory("s1") } returns story
        coEvery { repository.getChapters("s1") } returns emptyList()
        val viewModel = vm()
        shadowOf(Looper.getMainLooper()).idle()

        val events = mutableListOf<Unit>()
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { viewModel.continueWritingDone.collect { events += it } }
        shadowOf(Looper.getMainLooper()).idle()

        viewModel.continueWriting()
        shadowOf(Looper.getMainLooper()).idle()

        coVerifyOrder {
            generationService.continueStory(story, any())   // 先复活（completed → serializing）
            repository.getStory("s1")                        // 再读 fresh 快照
            taskManager.startGeneration(any())               // 才起生成
        }
        assertEquals("成功应发一次「已回到连载」事件（屏幕据此 toast + onBack）", 1, events.size)
        assertNull("成功路不该有错误", viewModel.error.value)
        scope.cancel()
    }

    /** 失败（断网/无 key）→ 呈现错误、不发成功事件、不退出档案（异常绝不穿透闪退）。 */
    @Test
    fun ST11_继续写失败_呈现错误且不发成功事件() = runBlocking {
        val story = StoryEntity(id = "s1", title = "书", status = StoryStatus.COMPLETED)
        coEvery { repository.getStory("s1") } returns story
        coEvery { repository.getChapters("s1") } returns emptyList()
        coEvery { generationService.continueStory(any(), any()) } throws RuntimeException("没配 key")
        val viewModel = vm()
        shadowOf(Looper.getMainLooper()).idle()

        val events = mutableListOf<Unit>()
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { viewModel.continueWritingDone.collect { events += it } }
        shadowOf(Looper.getMainLooper()).idle()

        viewModel.continueWriting()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("没配 key", viewModel.error.value)
        assertEquals("失败路不许发成功事件（不该把用户弹出档案）", 0, events.size)
        coVerify(exactly = 0) { taskManager.startGeneration(any()) }
        scope.cancel()
    }
}
