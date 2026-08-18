package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryReadingProgressStore
import com.situ.aichat.story.StoryUnlockNotificationScheduler
import com.situ.aichat.story.StoryWorldInfoService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 设定页「创作设定」七字段草稿 → persist 第三条列级写 T2（图纸二 D2·验收 T2-2 的 persist 部分）：
 * 七列实参逐个核、E13 题材空白回退原值、既有两条写零变（签名与实参不受新写路影响）。
 *
 * MockK 假仓库；viewModelScope 由 Robolectric 主循环驱动（照 [StorySettingsViewModelTest] 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorySettingsViewModelCreativeTest {

    private val repo = mockk<StoryRepository>()
    private val generationService = mockk<StoryGenerationService>()
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)
    private val worldInfoService = mockk<StoryWorldInfoService>(relaxed = true)
    private val readingProgressStore = mockk<StoryReadingProgressStore>(relaxed = true)
    private val functionRouter = mockk<ApiFunctionRouter>()
    private val apiConfigs = mockk<ApiConfigRepository>()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val unlockScheduler = mockk<StoryUnlockNotificationScheduler>(relaxed = true)

    private val scope = CoroutineScope(Dispatchers.Main)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 一本设定齐全的书（七字段都非默认值，便于分辨「原样带过去」与「被写成默认」）。 */
    private fun story() = StoryEntity(
        id = "s1",
        title = "对面楼的灯",
        genre = "都市",
        writingStyle = "网文爽文",
        narrativePerson = "second",
        chapterLengthPreference = 5000,
        chatInfluenceWeight = "medium",
        worldSetting = "凌晨一点的写字楼",
        plotDirection = "先冷后热",
        updateMode = "free",
    )

    private fun vm(entity: StoryEntity = story()): StorySettingsViewModel {
        every { repo.observeStory(any()) } returns flowOf(entity)
        coEvery { repo.getRoles(any()) } returns emptyList()
        every { settingsRepository.appSettings } returns flowOf(AppSettings())
        every { functionRouter.assignments } returns flowOf(emptyMap())
        every { apiConfigs.observeAll() } returns flowOf(emptyList())
        every { apiConfigs.observeActive() } returns flowOf(null)
        coEvery { repo.updateStorySettings(any(), any(), any(), any()) } just Runs
        coEvery { repo.updateCreativeSettings(any(), any(), any(), any(), any(), any(), any(), any()) } just Runs
        val vm = StorySettingsViewModel(
            SavedStateHandle(mapOf("storyId" to "s1")),
            repo,
            generationService,
            taskManager,
            worldInfoService,
            readingProgressStore,
            mockk(relaxed = true), // StoryArchiver（本组用例不碰归档）
            mockk(relaxed = true), // StoryDeleter（本组用例不碰删除）
            unlockScheduler, // 追更→自由撤闹钟联动（本文件两例专用·其余用例零触碰）
            mockk(relaxed = true), // StoryPersonaDrafter（本组用例不碰起草）
            mockk(relaxed = true), // StoryOutlineOrchestrator（本组用例不碰重排）
            functionRouter,
            apiConfigs,
            settingsRepository,
            mockk(relaxed = true),
        )
        await("草稿初始化") { vm.draft.value != null }
        return vm
    }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    private fun persist(vm: StorySettingsViewModel): Boolean = runBlocking { vm.persist() }

    @Test
    fun 草稿初始化_七字段从故事快照灌入() {
        val d = vm().draft.value!!
        assertEquals("都市", d.genre)
        assertEquals("网文爽文", d.writingStyle)
        assertEquals("second", d.narrativePerson)
        assertEquals(5000, d.chapterLengthPreference)
        assertEquals("medium", d.chatInfluenceWeight)
        assertEquals("凌晨一点的写字楼", d.worldSetting)
        assertEquals("先冷后热", d.plotDirection)
    }

    @Test
    fun 草稿初始化_世界观与剧情方向为null的书_灌空串() {
        val d = vm(story().copy(worldSetting = null, plotDirection = null)).draft.value!!
        assertEquals("", d.worldSetting)
        assertEquals("", d.plotDirection)
    }

    @Test
    fun persist_七列实参逐个落库() {
        val vm = vm()
        vm.updateDraft {
            it.copy(
                genre = "悬疑",
                writingStyle = "冷硬派",
                narrativePerson = "first",
                chapterLengthPreference = 3000,
                chatInfluenceWeight = "strong",
                worldSetting = "海边小镇的雨季",
                plotDirection = "从一通电话开始",
            )
        }
        assertTrue(persist(vm))

        coVerify(exactly = 1) {
            repo.updateCreativeSettings(
                "s1", "悬疑", "冷硬派", "first", 3000, "strong", "海边小镇的雨季", "从一通电话开始",
            )
        }
    }

    @Test
    fun persist_世界观与剧情方向清空_归null不落空串() {
        val vm = vm()
        vm.updateDraft { it.copy(worldSetting = "", plotDirection = "") }
        assertTrue(persist(vm))

        coVerify(exactly = 1) {
            repo.updateCreativeSettings("s1", "都市", "网文爽文", "second", 5000, "medium", null, null)
        }
    }

    @Test
    fun persist_题材编辑成空白_回退原值绝不落空题材() {
        // E13：题材是提示词多处引用的锚——用户把它删空，落库必须回退成进屏时的原值。
        val vm = vm()
        vm.updateDraft { it.copy(genre = "   ") }
        assertTrue(persist(vm))

        coVerify(exactly = 1) {
            repo.updateCreativeSettings("s1", "都市", any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            repo.updateCreativeSettings("s1", "", any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            repo.updateCreativeSettings("s1", "   ", any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun persist_题材两侧空白_trim后落库() {
        val vm = vm()
        vm.updateDraft { it.copy(genre = "  科幻  ") }
        assertTrue(persist(vm))

        coVerify(exactly = 1) {
            repo.updateCreativeSettings("s1", "科幻", any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun persist_更新模式那条写零变_创作设定那条照旧() {
        // 卷二 J3：记忆四字段已移出草稿（改由档案编辑页即改即存），persist 只剩这两条列级写。
        val vm = vm()
        vm.updateDraft { it.copy(updateMode = "chase", unlockHour = 7, unlockMinute = 30) }
        assertTrue(persist(vm))

        coVerify(exactly = 1) { repo.updateStorySettings("s1", "chase", 7, 30) }
        coVerify(exactly = 1) { repo.updateCreativeSettings("s1", any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun persist_写库抛异常_返回false并置错误() {
        val vm = vm()
        coEvery { repo.updateCreativeSettings(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("磁盘满了")

        assertEquals(false, persist(vm))
        assertEquals("磁盘满了", vm.error.value)
    }

    @Test
    fun persist_草稿未初始化_直接返回真且不写创作设定() {
        every { repo.observeStory(any()) } returns flowOf(null)
        coEvery { repo.getRoles(any()) } returns emptyList()
        every { settingsRepository.appSettings } returns flowOf(AppSettings())
        every { functionRouter.assignments } returns flowOf(emptyMap())
        every { apiConfigs.observeAll() } returns flowOf(emptyList())
        every { apiConfigs.observeActive() } returns flowOf(null)
        val vm = StorySettingsViewModel(
            SavedStateHandle(mapOf("storyId" to "s1")),
            repo,
            generationService,
            taskManager,
            worldInfoService,
            readingProgressStore,
            mockk(relaxed = true), // StoryArchiver（本组用例不碰归档）
            mockk(relaxed = true), // StoryDeleter（本组用例不碰删除）
            unlockScheduler, // 追更→自由撤闹钟联动（本文件两例专用·其余用例零触碰）
            mockk(relaxed = true), // StoryPersonaDrafter（本组用例不碰起草）
            mockk(relaxed = true), // StoryOutlineOrchestrator（本组用例不碰重排）
            functionRouter,
            apiConfigs,
            settingsRepository,
            mockk(relaxed = true),
        )
        assertNull(vm.draft.value)
        assertTrue(persist(vm))
        coVerify(exactly = 0) { repo.updateCreativeSettings(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    /**
     * 追更→自由：清解锁列**必须连撤**已排闹钟（2026-08-04 相邻缺口）——只清列不撤，
     * 到点仍发「第 N 章已解锁」冗余通知（全书早已解锁）。先清列后撤钟（DB 为真源，闹钟跟随）。
     */
    @Test
    fun persist_追更切自由_清解锁列并撤全书闹钟() {
        coEvery { repo.clearChapterUnlocks("s1") } just Runs
        val vm = vm(story().copy(updateMode = "chase"))
        vm.updateDraft { it.copy(updateMode = "free") }
        assertTrue(persist(vm))

        coVerifyOrder {
            repo.clearChapterUnlocks("s1")
            unlockScheduler.cancelUnlocksForStory("s1")
        }
    }

    /** 非切换保存（追更进追更出）：清列与撤钟一个都不该发生——普通改设定绝不动追更提醒。 */
    @Test
    fun persist_追更原样保存_不清列不撤闹钟() {
        val vm = vm(story().copy(updateMode = "chase"))
        assertTrue(persist(vm))

        coVerify(exactly = 0) { repo.clearChapterUnlocks(any()) }
        coVerify(exactly = 0) { unlockScheduler.cancelUnlocksForStory(any()) }
    }
}
