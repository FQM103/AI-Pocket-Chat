package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.UserStoryTemplateDao
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.local.entity.UserStoryTemplateEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.UserStoryTemplatePayload
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryChatInfluenceWeight
import com.situ.aichat.story.StoryGenerationService
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.story.StoryReadingProgressStore
import com.situ.aichat.story.StoryUpdateMode
import com.situ.aichat.story.StoryWorldInfoService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
 * 「存为我的模板」T2（图纸四 §3.3 / §5 E8 / §7 T2-3）。
 *
 * 钉三件命门：
 * 1. **先落草稿再抽取**——用户刚在本页改的创作设定必须进模板（图纸 §11 D-3）：`persist` 的列级写
 *    必须发生在 `insert` 之前，且抽取读的是**落库后重读**的那本书。
 * 2. **上限 20 在落库瞬间复核**——到顶不插入、发上限提示（E8）。
 * 3. **抽取内容**——13 个字段逐个落位；书名不进 payload（只当默认名用）。
 *
 * MockK 假仓库 + 假 DAO；viewModelScope 由 Robolectric 主循环驱动（照 [StorySettingsViewModelCreativeTest] 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorySettingsViewModelSaveTemplateTest {

    private val repo = mockk<StoryRepository>()
    private val templateDao = mockk<UserStoryTemplateDao>()
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val functionRouter = mockk<ApiFunctionRouter>()
    private val apiConfigs = mockk<ApiConfigRepository>()

    private val scope = CoroutineScope(Dispatchers.Main)

    @After fun tearDown() = scope.cancel()

    /** 一本设定齐全的书（每个字段都非默认值，漏抄一个就红）。 */
    private fun story() = StoryEntity(
        id = "s1",
        title = "对面楼的灯",
        genre = "赛博修真",
        writingStyle = "哥特暗黑",
        narrativePerson = StoryNarrativePerson.FIRST,
        chapterLengthPreference = 2600,
        chatInfluenceWeight = StoryChatInfluenceWeight.HEAVY,
        worldSetting = "凌晨一点的写字楼",
        plotDirection = "先冷后热",
        updateMode = StoryUpdateMode.CHASE,
        unlockHour = 7,
        unlockMinute = 45,
        customPromptsJson = """{"bannedExpressions":"忌口","chapterChoicesEnabled":false}""",
    )

    private fun vm(
        entity: StoryEntity = story(),
        freshStory: StoryEntity = entity,
        existingCount: Int = 0,
    ): StorySettingsViewModel {
        every { repo.observeStory(any()) } returns flowOf(entity)
        coEvery { repo.getRoles(any()) } returns emptyList()
        coEvery { repo.getStory(any()) } returns freshStory
        every { settingsRepository.appSettings } returns flowOf(AppSettings())
        every { functionRouter.assignments } returns flowOf(emptyMap())
        every { apiConfigs.observeAll() } returns flowOf(emptyList())
        every { apiConfigs.observeActive() } returns flowOf(null)
        coEvery { repo.updateStorySettings(any(), any(), any(), any()) } just Runs
        coEvery { repo.updateCreativeSettings(any(), any(), any(), any(), any(), any(), any(), any()) } just Runs
        every { templateDao.observeAll() } returns flowOf(emptyList())
        coEvery { templateDao.count() } returns existingCount
        coEvery { templateDao.insert(any()) } just Runs
        val vm = StorySettingsViewModel(
            SavedStateHandle(mapOf("storyId" to "s1")),
            repo,
            mockk<StoryGenerationService>(),
            mockk<StoryGenerationTaskManager>(relaxed = true),
            mockk<StoryWorldInfoService>(relaxed = true),
            mockk<StoryReadingProgressStore>(relaxed = true),
            mockk(relaxed = true), // StoryArchiver（本组用例不碰归档）
            mockk(relaxed = true), // StoryDeleter（本组用例不碰删除）
            mockk(relaxed = true), // StoryUnlockNotificationScheduler（本组用例不碰闹钟）
            mockk(relaxed = true), // StoryPersonaDrafter（本组用例不碰起草）
            mockk(relaxed = true), // StoryOutlineOrchestrator（本组用例不碰重排）
            functionRouter,
            apiConfigs,
            settingsRepository,
            templateDao,
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

    /** 跑一次存模板，返回真正落库的那一行（没落库则 null）+ 收到的提示 res id。 */
    private fun saveAndCapture(vm: StorySettingsViewModel, name: String): Pair<UserStoryTemplateEntity?, Int?> {
        val rowSlot = slot<UserStoryTemplateEntity>()
        coEvery { templateDao.insert(capture(rowSlot)) } just Runs
        var toast: Int? = null
        val job = scope.launch { vm.toastEvents.collect { toast = it } }
        shadowOf(Looper.getMainLooper()).idle()
        vm.saveAsTemplate(name)
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (toast != null) return@repeat
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
        job.cancel()
        return (if (rowSlot.isCaptured) rowSlot.captured else null) to toast
    }

    @Test
    fun 存模板_先落草稿再抽取() {
        val vm = vm()
        vm.updateDraft { it.copy(genre = "悬疑") }
        saveAndCapture(vm, "深夜都市线")
        // 顺序钉：草稿的两条列级写必须都发生在 insert 之前——否则用户刚改的设定进不了模板。
        // （记忆四字段那条写卷二 J3 起已退役：档案字段改为编辑页即改即存，不再进草稿。）
        coVerifyOrder {
            repo.updateStorySettings(any(), any(), any(), any())
            repo.updateCreativeSettings("s1", "悬疑", any(), any(), any(), any(), any(), any())
            repo.getStory("s1")
            templateDao.insert(any())
        }
    }

    @Test
    fun 存模板_抽取的是落库后重读的那本书() {
        // 落库后的书（题材已改）与进屏快照（旧题材）不同 → 模板必须存新的那个。
        val vm = vm(freshStory = story().copy(genre = "悬疑"))
        val (row, _) = saveAndCapture(vm, "深夜都市线")
        val payload = UserStoryTemplatePayload.decode(row!!.payloadJson)!!
        assertEquals("必须来自 fresh 读的那本书", "悬疑", payload.genre)
    }

    @Test
    fun 存模板_十三字段逐个落位_且不含书名() {
        val vm = vm()
        val (row, toast) = saveAndCapture(vm, "深夜都市线")
        assertEquals("深夜都市线", row!!.name)
        assertTrue("存入时刻应写入", row.createdAt > 0)
        assertEquals(R.string.story_template_saved_toast, toast)

        val p = UserStoryTemplatePayload.decode(row.payloadJson)!!
        assertEquals("赛博修真", p.genre)
        assertTrue(p.isCustomGenre)
        assertEquals("哥特暗黑", p.writingStyle)
        assertEquals(StoryNarrativePerson.FIRST, p.narrativePerson)
        assertEquals(2600, p.chapterLengthPreference)
        assertEquals(StoryChatInfluenceWeight.HEAVY, p.chatInfluenceWeight)
        assertEquals("凌晨一点的写字楼", p.worldSetting)
        assertEquals("先冷后热", p.plotDirection)
        assertEquals(StoryUpdateMode.CHASE, p.updateMode)
        assertEquals(7, p.unlockHour)
        assertEquals(45, p.unlockMinute)
        assertEquals("""{"bannedExpressions":"忌口","chapterChoicesEnabled":false}""", p.customPromptsJson)
        assertNull(p.referenceGenre)
        assertFalse("书名不进模板（只当命名弹窗默认值）", row.payloadJson.contains("对面楼的灯"))
    }

    @Test
    fun 存模板_名字两端空白被去掉() {
        val vm = vm()
        val (row, _) = saveAndCapture(vm, "  深夜都市线  ")
        assertEquals("深夜都市线", row!!.name)
    }

    @Test
    fun 存模板_到二十上限时不落库并给上限提示() {
        val vm = vm(existingCount = UserStoryTemplatePayload.MAX_USER_TEMPLATES)
        val (row, toast) = saveAndCapture(vm, "第二十一个")
        assertNull("到顶不许再落库", row)
        assertEquals(R.string.story_save_template_limit, toast)
        coVerify(exactly = 0) { templateDao.insert(any()) }
    }

    @Test
    fun 存模板_差一个到顶仍可存() {
        val vm = vm(existingCount = UserStoryTemplatePayload.MAX_USER_TEMPLATES - 1)
        val (row, toast) = saveAndCapture(vm, "第二十个")
        assertEquals("第二十个", row!!.name)
        assertEquals(R.string.story_template_saved_toast, toast)
    }
}
