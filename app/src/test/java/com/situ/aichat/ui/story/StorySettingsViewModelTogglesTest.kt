package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryReadingProgressStore
import com.situ.aichat.story.StoryWorldInfoService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 生成开关的写路 T2（图纸二 D3·验收 T2-2 的 setter 部分；2026-08-03 格式块精简后「沉浸氛围标记」
 * 那一路整链退役，此处改用仍在的「场景状态快照」开关做「不串台 / 不丢字段」的对照）：
 * 「开 = 写 null 不写 true」、E5 判空清 JSON、**E6 四向 copy-merge**（动开关不丢忌口/节奏，动忌口/节奏不丢开关）。
 *
 * E6 是防 2026-07-30 那个真 bug 复发的钉子：当时新写路直接新构造对象，把不在本 sheet 里的字段写成了 null。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorySettingsViewModelTogglesTest {

    private val repo = mockk<StoryRepository>()
    private val generationService = mockk<StoryGenerationService>()
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)
    private val worldInfoService = mockk<StoryWorldInfoService>(relaxed = true)
    private val readingProgressStore = mockk<StoryReadingProgressStore>(relaxed = true)
    private val functionRouter = mockk<ApiFunctionRouter>()
    private val apiConfigs = mockk<ApiConfigRepository>()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    private val scope = CoroutineScope(Dispatchers.Main)

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 「尚未捕获」哨兵（真值可能是 null，不能用 null 判定是否已写库）。 */
    private var captured: String? = SENTINEL

    private fun vm(existing: CustomStoryPrompts? = null): StorySettingsViewModel {
        val json = existing?.let { CustomStoryPrompts.encode(it) }
        every { repo.observeStory(any()) } returns flowOf(StoryEntity(id = "s1", genre = "都市", customPromptsJson = json))
        coEvery { repo.getStory("s1") } returns StoryEntity(id = "s1", genre = "都市", customPromptsJson = json)
        coEvery { repo.getRoles(any()) } returns emptyList()
        every { settingsRepository.appSettings } returns flowOf(AppSettings())
        every { functionRouter.assignments } returns flowOf(emptyMap())
        every { apiConfigs.observeAll() } returns flowOf(emptyList())
        every { apiConfigs.observeActive() } returns flowOf(null)
        captured = SENTINEL
        coEvery { repo.updateCustomPrompts(any(), any()) } answers { captured = secondArg(); Unit }
        return StorySettingsViewModel(
            SavedStateHandle(mapOf("storyId" to "s1")),
            repo,
            generationService,
            taskManager,
            worldInfoService,
            readingProgressStore,
            mockk(relaxed = true), // StoryArchiver（本组用例不碰归档）
            mockk(relaxed = true), // StoryDeleter（本组用例不碰删除）
            mockk(relaxed = true), // StoryUnlockNotificationScheduler（本组用例不碰闹钟）
            mockk(relaxed = true), // StoryPersonaDrafter（本组用例不碰起草）
            mockk(relaxed = true), // StoryOutlineOrchestrator（本组用例不碰重排）
            functionRouter,
            apiConfigs,
            settingsRepository,
            mockk(relaxed = true),
        )
    }

    private fun awaitWrite() {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (captured != SENTINEL) return
            Thread.sleep(5)
        }
        error("等待超时：开关写库")
    }

    private fun written(): CustomStoryPrompts? = CustomStoryPrompts.decode(captured)

    @Test
    fun 开选项_写true进JSON() {
        vm().setChapterChoicesEnabled(true)
        awaitWrite()

        assertEquals(true, written()!!.chapterChoicesEnabled)
        assertTrue(written()!!.effectiveChapterChoices)
        assertNull("另一个开关不受影响", written()!!.sceneSnapshotEnabled)
    }

    @Test
    fun 关选项_写null而不是false_默认态JSON不留这个键() {
        // 2026-08-05 拍板默认关：关 = 回到「没有这个键」的默认态（带个内容字段保住 JSON 以便断言键缺席）。
        vm(CustomStoryPrompts(chapterChoicesEnabled = true, pacingPreference = "慢热")).setChapterChoicesEnabled(false)
        awaitWrite()

        assertNull("关 = 回到「没有这个键」", written()!!.chapterChoicesEnabled)
        assertFalse("键真的不在 JSON 里", captured!!.contains("chapterChoicesEnabled"))
        assertFalse("谓词读出来是关", written()!!.effectiveChapterChoices)
    }

    @Test
    fun 关快照_写false且不碰选项开关() {
        vm(CustomStoryPrompts(chapterChoicesEnabled = false)).setSceneSnapshotEnabled(false)
        awaitWrite()

        assertEquals(false, written()!!.sceneSnapshotEnabled)
        assertEquals("已关的选项开关必须原样留着", false, written()!!.chapterChoicesEnabled)
    }

    @Test
    fun E5_开关恢复默认且其他字段全空_清JSON为null() {
        // 显式开着的书把选项关回默认（写 null）→ 整份 JSON 回到空。
        vm(CustomStoryPrompts(chapterChoicesEnabled = true)).setChapterChoicesEnabled(false)
        awaitWrite()

        assertNull("整份 JSON 判空清掉，走预设默认", captured)
    }

    @Test
    fun E5_开关恢复开但还有忌口_JSON保留() {
        vm(CustomStoryPrompts(chapterChoicesEnabled = false, bannedExpressions = "少写雨")).setChapterChoicesEnabled(true)
        awaitWrite()

        assertEquals("少写雨", written()!!.bannedExpressions)
    }

    // ── E6：四向 copy-merge ──

    @Test
    fun E6_动开关_不丢忌口与节奏与写作口径三字段() {
        vm(
            CustomStoryPrompts(
                genreTechniques = "技法", writerIdentity = "身份", writingRules = "规则",
                pacingPreference = "慢热，多写日常", bannedExpressions = "少写雨",
            ),
        ).setChapterChoicesEnabled(true)
        awaitWrite()

        val w = written()!!
        assertEquals("技法", w.genreTechniques)
        assertEquals("身份", w.writerIdentity)
        assertEquals("规则", w.writingRules)
        assertEquals("慢热，多写日常", w.pacingPreference)
        assertEquals("少写雨", w.bannedExpressions)
        assertEquals(true, w.chapterChoicesEnabled)
    }

    @Test
    fun E6_动自定义提示词_不丢两个开关() {
        vm(CustomStoryPrompts(chapterChoicesEnabled = false, sceneSnapshotEnabled = false))
            .saveCustomPrompts("新技法", "新身份", "", "")
        awaitWrite()

        val w = written()!!
        assertEquals("开关必须被 copy 带过来", false, w.chapterChoicesEnabled)
        assertEquals(false, w.sceneSnapshotEnabled)
        assertEquals("新技法", w.genreTechniques)
    }

    @Test
    fun E6_动节奏偏好_不丢两个开关() {
        vm(CustomStoryPrompts(chapterChoicesEnabled = false, sceneSnapshotEnabled = false))
            .savePacing("慢热，多写日常")
        awaitWrite()

        val w = written()!!
        assertEquals(false, w.chapterChoicesEnabled)
        assertEquals(false, w.sceneSnapshotEnabled)
        assertEquals("慢热，多写日常", w.pacingPreference)
    }

    @Test
    fun E6_清空节奏偏好但开关关着_JSON不许被判空清掉() {
        // 反向：hasAnyValue 若没并入两个开关，这里会把「本书关了选项」一起丢掉。
        vm(CustomStoryPrompts(chapterChoicesEnabled = false, pacingPreference = "旧值")).savePacing("   ")
        awaitWrite()

        assertEquals(false, written()!!.chapterChoicesEnabled)
        assertNull(written()!!.pacingPreference)
    }

    @Test
    fun 写库失败_置错误不崩() {
        val vm = vm()
        coEvery { repo.updateCustomPrompts(any(), any()) } throws IllegalStateException("磁盘满了")
        vm.setSceneSnapshotEnabled(false)

        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (vm.error.value == "磁盘满了") return
            Thread.sleep(5)
        }
        error("等待超时：错误落定")
    }

    private companion object {
        const val SENTINEL = "__uncaptured__"
    }
}
