package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryCraftSections
import com.situ.aichat.story.StoryEditableField
import com.situ.aichat.story.StoryWritingTechniques
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
 * 统一编辑页 VM 的 T2（卷二 §7 T2-3）：三态反推装载 / 切段保草稿（E5）/ 保存分派逐 kind（copy-merge 只换本字段）/
 * 防重入（E3）/ 节奏偏好拒收超限（E6）/ 全局忌口变体三态原样存。
 *
 * 期望值从图纸 §3.3 的状态机独立反推；MockK 假仓库，viewModelScope 由 Robolectric 主循环驱动
 * （照 [StorySettingsViewModelTest] 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryFieldEditorViewModelTest {

    private val repo = mockk<StoryRepository>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)

    private fun story(prompts: CustomStoryPrompts? = null, summary: String? = null) = StoryEntity(
        id = "s1",
        title = "晚风与她",
        genre = "言情",
        writingStyle = "轻松幽默",
        storySummary = summary,
        customPromptsJson = prompts?.let { CustomStoryPrompts.encode(it) },
    )

    private fun vm(
        field: String,
        entity: StoryEntity? = story(),
        appSettings: AppSettings = AppSettings(),
    ): StoryFieldEditorViewModel {
        coEvery { repo.getStory("s1") } returns entity
        every { settings.appSettings } returns flowOf(appSettings)
        val vm = StoryFieldEditorViewModel(
            SavedStateHandle(mapOf("storyId" to "s1", "fieldKey" to field)),
            repo,
            settings,
        )
        if (!vm.invalid) await("装载完成") { vm.state.value != null }
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

    private fun captureCustomPrompts(): () -> String? {
        var captured: String? = SENTINEL
        coEvery { repo.updateCustomPrompts(any(), any()) } answers { captured = secondArg(); Unit }
        return { captured.takeIf { it != SENTINEL } }
    }

    // ── 装载：三态按存储值反推 ──

    @Test fun 装载_三态字段_未覆盖为跟随全局_并给出继承层预览() {
        val vm = vm(StoryEditableField.SCENE_BEATS.key)
        val s = vm.state.value!!
        assertEquals(StoryFieldMode.FOLLOW, s.mode)
        assertEquals("跟随态草稿为空，正文由继承层预览承担", "", s.text)
        assertEquals(StoryCraftSections.SCENE_BEATS_DEFAULT, s.inheritedText)
        assertTrue(s.showModeSegment)
        assertFalse(s.dirty)
    }

    @Test fun 装载_三态字段_本书空串为本书关闭() {
        val vm = vm(StoryEditableField.SCENE_BEATS.key, story(CustomStoryPrompts(sceneBeats = "")))
        assertEquals(StoryFieldMode.OFF, vm.state.value!!.mode)
        assertEquals("", vm.state.value!!.text)
    }

    @Test fun 装载_三态字段_本书有文本为自定义() {
        val vm = vm(StoryEditableField.SCENE_BEATS.key, story(CustomStoryPrompts(sceneBeats = "本书节拍")))
        assertEquals(StoryFieldMode.CUSTOM, vm.state.value!!.mode)
        assertEquals("本书节拍", vm.state.value!!.text)
    }

    @Test fun 装载_二态字段与档案字段_恒自定义态无三态段() {
        val plain = vm(StoryEditableField.WRITER_IDENTITY.key, story(CustomStoryPrompts(writerIdentity = "我的身份")))
        assertEquals(StoryFieldMode.CUSTOM, plain.state.value!!.mode)
        assertFalse(plain.state.value!!.showModeSegment)
        assertEquals("我的身份", plain.state.value!!.text)

        val archive = vm(StoryEditableField.SUMMARY.key, story(summary = "前情"))
        assertEquals(StoryFieldMode.CUSTOM, archive.state.value!!.mode)
        assertFalse(archive.state.value!!.showModeSegment)
        assertTrue(archive.state.value!!.isArchive)
        assertEquals("前情", archive.state.value!!.text)
        assertEquals("档案族副题走书名", "晚风与她", archive.state.value!!.bookTitle)
    }

    @Test fun 装载_出厂默认只在有默认的字段给出() {
        assertEquals(
            StoryCraftSections.SCENE_BEATS_DEFAULT,
            vm(StoryEditableField.SCENE_BEATS.key).state.value!!.factoryDefault,
        )
        assertNull(vm(StoryEditableField.SUMMARY.key).state.value!!.factoryDefault)
        assertNull(vm(StoryEditableField.TASTE_PROFILE.key).state.value!!.factoryDefault)
    }

    @Test fun 装载_非法路由键_标记失效不渲染() {
        val vm = vm("不存在的字段")
        assertTrue(vm.invalid)
        assertNull(vm.state.value)
    }

    // ── E5：切段不丢草稿 ──

    @Test fun e5_跟随切自定义灌继承层文本_来回切草稿不丢() {
        val vm = vm(StoryEditableField.SCENE_BEATS.key)
        vm.setMode(StoryFieldMode.CUSTOM)
        assertEquals("首次进自定义 = 一进来就能删改", StoryCraftSections.SCENE_BEATS_DEFAULT, vm.state.value!!.text)

        vm.setText("我改过的节拍")
        vm.setMode(StoryFieldMode.FOLLOW)
        assertEquals("离开自定义草稿留在内存", "我改过的节拍", vm.state.value!!.text)
        vm.setMode(StoryFieldMode.CUSTOM)
        assertEquals("切回来不被继承层覆盖", "我改过的节拍", vm.state.value!!.text)
        assertTrue(vm.state.value!!.dirty)
    }

    @Test fun e5_自定义清空后切走再切回_不再自动灌默认() {
        val vm = vm(StoryEditableField.SCENE_BEATS.key, story(CustomStoryPrompts(sceneBeats = "本书节拍")))
        vm.setText("")
        vm.setMode(StoryFieldMode.OFF)
        vm.setMode(StoryFieldMode.CUSTOM)
        assertEquals("用户亲手删空的草稿不许被系统填回来", "", vm.state.value!!.text)
    }

    @Test fun dirty_只改段位也算脏_改回原样不算脏() {
        val vm = vm(StoryEditableField.SCENE_BEATS.key, story(CustomStoryPrompts(sceneBeats = "本书节拍")))
        assertFalse(vm.state.value!!.dirty)
        vm.setMode(StoryFieldMode.OFF)
        assertTrue(vm.state.value!!.dirty)
        vm.setMode(StoryFieldMode.CUSTOM)
        assertFalse("段位与文本都回到初值 → 不脏", vm.state.value!!.dirty)
    }

    // ── E6：节奏偏好 300 字硬拦（绝不静默截）──

    @Test fun e6_节奏偏好_越界输入整笔拒收_原值不动() {
        val vm = vm(StoryEditableField.PACING.key, story(CustomStoryPrompts(pacingPreference = "慢热")))
        assertEquals(300, vm.state.value!!.maxChars)

        vm.setText("节".repeat(300))
        assertEquals(300, vm.state.value!!.text.length)

        vm.setText("节".repeat(301))
        assertEquals("到顶再敲一个字：拒收，不静默截成 300", 300, vm.state.value!!.text.length)

        vm.setText("粘".repeat(400))
        assertEquals("粘贴 400 字整笔拒收", "节".repeat(300), vm.state.value!!.text)
    }

    // ── 保存分派：写法族走 copy-merge（只换本字段）──

    @Test fun 保存_三态字段_跟随全局落null_本书关闭落空串_自定义落trim文本() {
        val existing = CustomStoryPrompts(writerIdentity = "旧身份", pacingPreference = "旧节奏", sceneBeats = "旧节拍")
        val readBack = captureCustomPrompts()
        val vm = vm(StoryEditableField.SCENE_BEATS.key, story(existing))

        vm.setMode(StoryFieldMode.FOLLOW)
        assertTrue(runBlocking { vm.save() })
        CustomStoryPrompts.decode(readBack())!!.let {
            assertNull("跟随全局 = JSON 里不留这个键", it.sceneBeats)
            assertEquals("同一份 JSON 的别的字段必须原样保留", "旧身份", it.writerIdentity)
            assertEquals("旧节奏", it.pacingPreference)
        }

        vm.setMode(StoryFieldMode.OFF)
        assertTrue(runBlocking { vm.save() })
        assertEquals("", CustomStoryPrompts.decode(readBack())!!.sceneBeats)

        vm.setMode(StoryFieldMode.CUSTOM)
        vm.setText("  新节拍  ")
        assertTrue(runBlocking { vm.save() })
        assertEquals("新节拍", CustomStoryPrompts.decode(readBack())!!.sceneBeats)
    }

    @Test fun 保存_二态字段_清空归null回出厂默认_其余字段原样() {
        val readBack = captureCustomPrompts()
        val vm = vm(
            StoryEditableField.WRITER_IDENTITY.key,
            story(CustomStoryPrompts(writerIdentity = "旧身份", bannedExpressions = "本书忌口")),
        )
        vm.setText("   ")
        assertTrue(runBlocking { vm.save() })
        val decoded = CustomStoryPrompts.decode(readBack())!!
        assertNull(decoded.writerIdentity)
        assertEquals("本书忌口", decoded.bannedExpressions)
    }

    @Test fun 保存_最后一个字段也清空_整份JSON清为null() {
        val readBack = captureCustomPrompts()
        val vm = vm(StoryEditableField.WRITER_IDENTITY.key, story(CustomStoryPrompts(writerIdentity = "旧身份")))
        vm.setText("")
        assertTrue(runBlocking { vm.save() })
        assertNull("再无任何字段 → 清 JSON 走预设默认", readBack())
    }

    @Test fun 保存_节奏偏好走既有归一化单源() {
        val readBack = captureCustomPrompts()
        val vm = vm(StoryEditableField.PACING.key)
        vm.setText("  慢炖  ")
        assertTrue(runBlocking { vm.save() })
        assertEquals("慢炖", CustomStoryPrompts.decode(readBack())!!.pacingPreference)
    }

    // ── 保存分派：档案族走单列定向写 ──

    @Test fun 保存_档案八节_逐个走各自的单列定向写() {
        val cases = listOf(
            StoryEditableField.OUTLINE to { coVerify(exactly = 1) { repo.updateStoryOutlineUserEdit("s1", "文", any()) } },
            StoryEditableField.CURRENT_ARC to { coVerify(exactly = 1) { repo.updateCurrentArcUserEdit("s1", "文", any()) } },
            StoryEditableField.INTIMACY to { coVerify(exactly = 1) { repo.updateIntimacyLedger("s1", "文", any()) } },
            StoryEditableField.SCENE_LEDGER to { coVerify(exactly = 1) { repo.updateSceneLedger("s1", "文", any()) } },
            StoryEditableField.SCENE_STATE to { coVerify(exactly = 1) { repo.updateSceneState("s1", "文", any()) } },
            StoryEditableField.CHARACTER_STATES to { coVerify(exactly = 1) { repo.updateCharacterStates("s1", "文", any()) } },
            StoryEditableField.OPEN_THREADS to { coVerify(exactly = 1) { repo.updateOpenThreads("s1", "文", any()) } },
            StoryEditableField.SUMMARY to { coVerify(exactly = 1) { repo.updateStorySummaryUserEdit("s1", "文", any()) } },
            StoryEditableField.BIBLE to { coVerify(exactly = 1) { repo.updateStoryBible("s1", "文") } },
        )
        for ((field, verify) in cases) {
            val vm = vm(field.key)
            vm.setText("文")
            assertTrue("$field 保存应成功", runBlocking { vm.save() })
            verify()
        }
        // 档案族绝不走 customPrompts 那条写路
        coVerify(exactly = 0) { repo.updateCustomPrompts(any(), any()) }
    }

    @Test fun 保存_档案字段清空_归null不落空串() {
        val vm = vm(StoryEditableField.SUMMARY.key, story(summary = "旧摘要"))
        vm.setText("   ")
        assertTrue(runBlocking { vm.save() })
        coVerify(exactly = 1) { repo.updateStorySummaryUserEdit("s1", null, any()) }
    }

    // ── E3：防重入 + 失败不返回 ──

    @Test fun e3_保存失败_返回false并置错误() {
        coEvery { repo.updateStorySummaryUserEdit(any(), any(), any()) } throws IllegalStateException("磁盘满了")
        val vm = vm(StoryEditableField.SUMMARY.key)
        vm.setText("文")
        assertFalse(runBlocking { vm.save() })
        assertEquals("磁盘满了", vm.error.value)
        assertFalse("失败后 saving 必须复位，否则保存钮永久禁用", vm.saving.value)
    }

    @Test fun e3_保存期间重复点_第二次直接短路() {
        val vm = vm(StoryEditableField.SUMMARY.key)
        vm.setText("文")
        // 第一次写库时在仓库内部再点一次保存：saving 为真 → 必须短路，不产生第二次写
        var reentered: Boolean? = null
        coEvery { repo.updateStorySummaryUserEdit(any(), any(), any()) } answers {
            assertTrue("写库期间 saving 应为真", vm.saving.value)
            reentered = runBlocking { vm.save() }
            Unit
        }
        assertTrue(runBlocking { vm.save() })
        assertEquals(false, reentered)
        coVerify(exactly = 1) { repo.updateStorySummaryUserEdit("s1", "文", any()) }
    }

    // ── 全局忌口变体（不是本书字段：落 DataStore·三态原样存）──

    @Test fun 全局忌口变体_从未设置时灌内置默认全文() {
        val vm = vm(StoryEditableField.GLOBAL_BANNED_KEY)
        val s = vm.state.value!!
        assertNull("不是本书字段", s.field)
        assertFalse(s.showModeSegment)
        assertEquals(StoryWritingTechniques.bannedExpressionsBaseline, s.text)
        assertEquals(StoryWritingTechniques.bannedExpressionsBaseline, s.factoryDefault)
    }

    @Test fun 全局忌口变体_空串原样存_绝不trim绝不判空回退() {
        // 该 setter 返回 DataStore 的 Preferences（表达式体），返回值交给 relaxed mock 兜
        val vm = vm(StoryEditableField.GLOBAL_BANNED_KEY, appSettings = AppSettings(storyBannedExpressions = "旧全局"))
        assertEquals("旧全局", vm.state.value!!.text)

        vm.setText("")
        assertTrue(runBlocking { vm.save() })
        coVerify(exactly = 1) { settings.setStoryBannedExpressions("") }
        coVerify(exactly = 0) { repo.updateCustomPrompts(any(), any()) }
    }

    // ── 卷四 T2-1：另两个全局哨兵（场面节拍 / 口味画像）──

    @Test fun 全局场面节拍_从未设置时灌出厂默认全文_并给恢复默认钮() {
        val vm = vm(StoryEditableField.GLOBAL_SCENE_BEATS_KEY)
        val s = vm.state.value!!
        assertNull("不是本书字段", s.field)
        assertFalse("全局项没有三态段", s.showModeSegment)
        assertEquals(StoryCraftSections.SCENE_BEATS_DEFAULT, s.text)
        assertEquals(StoryCraftSections.SCENE_BEATS_DEFAULT, s.factoryDefault)
        assertEquals("全局无书名", "", s.bookTitle)
        assertEquals(R.string.story_global_beats_title, vm.titleRes)
    }

    @Test fun 全局场面节拍_已设置时读原值_保存原样存不trim() {
        val vm = vm(StoryEditableField.GLOBAL_SCENE_BEATS_KEY, appSettings = AppSettings(storySceneBeats = "  我的节拍  "))
        assertEquals("  我的节拍  ", vm.state.value!!.text)
        vm.setText("")
        assertTrue(runBlocking { vm.save() })
        coVerify(exactly = 1) { settings.setStorySceneBeats("") }
        // 反向：全局项绝不碰本书的两条写路
        coVerify(exactly = 0) { repo.updateCustomPrompts(any(), any()) }
        coVerify(exactly = 0) { repo.updateStorySummaryUserEdit(any(), any(), any()) }
    }

    @Test fun 全局口味画像_无出厂默认_空白起步且不给恢复默认钮() {
        val vm = vm(StoryEditableField.GLOBAL_TASTE_PROFILE_KEY)
        val s = vm.state.value!!
        assertEquals("", s.text)
        assertNull("画像没有出厂默认 → 不给按钮", s.factoryDefault)
        assertEquals(R.string.story_global_taste_title, vm.titleRes)
    }

    @Test fun 全局口味画像_保存走自己的setter_不串到忌口或节拍() {
        val vm = vm(StoryEditableField.GLOBAL_TASTE_PROFILE_KEY, appSettings = AppSettings(storyTasteProfile = "旧画像"))
        assertEquals("旧画像", vm.state.value!!.text)
        vm.setText("爱看强强对峙")
        assertTrue(runBlocking { vm.save() })
        coVerify(exactly = 1) { settings.setStoryTasteProfile("爱看强强对峙") }
        coVerify(exactly = 0) { settings.setStorySceneBeats(any()) }
        coVerify(exactly = 0) { settings.setStoryBannedExpressions(any()) }
        coVerify(exactly = 0) { repo.updateCustomPrompts(any(), any()) }
    }

    @Test fun e4_全局项的storyId段传占位符_照常装载与保存() {
        // 全局分支根本不读 storyId：仓库连一次都不该被问到这本「书」
        coEvery { repo.getStory("-") } returns null
        every { settings.appSettings } returns flowOf(AppSettings(storySceneBeats = "全局节拍"))
        val vm = StoryFieldEditorViewModel(
            SavedStateHandle(mapOf("storyId" to "-", "fieldKey" to StoryEditableField.GLOBAL_SCENE_BEATS_KEY)),
            repo,
            settings,
        )
        assertFalse(vm.invalid)
        await("装载完成") { vm.state.value != null }
        assertEquals("全局节拍", vm.state.value!!.text)
        vm.setText("改过的节拍")
        assertTrue(runBlocking { vm.save() })
        coVerify(exactly = 1) { settings.setStorySceneBeats("改过的节拍") }
    }

    @Test fun e5_非法路由键_三个哨兵之外一律失效() {
        for (key in listOf("globalScenebeats", "globalBanned", "", "storyBible2")) {
            val vm = StoryFieldEditorViewModel(
                SavedStateHandle(mapOf("storyId" to "s1", "fieldKey" to key)),
                repo,
                settings,
            )
            assertTrue("脏参数「$key」必须判失效", vm.invalid)
            assertNull("失效时不许渲染半截页", vm.state.value)
        }
        // 三个哨兵本身一律有效
        for (key in StoryEditableField.GLOBAL_KEYS) {
            every { settings.appSettings } returns flowOf(AppSettings())
            coEvery { repo.getStory(any()) } returns story()
            val vm = StoryFieldEditorViewModel(SavedStateHandle(mapOf("storyId" to "s1", "fieldKey" to key)), repo, settings)
            assertFalse("哨兵键「$key」不该被判失效", vm.invalid)
        }
    }

    @Test fun 恢复默认_灌出厂全文并置自定义态_还没落库() {
        val vm = vm(StoryEditableField.SCENE_BEATS.key)
        vm.restoreDefault()
        assertEquals(StoryCraftSections.SCENE_BEATS_DEFAULT, vm.state.value!!.text)
        assertEquals(StoryFieldMode.CUSTOM, vm.state.value!!.mode)
        coVerify(exactly = 0) { repo.updateCustomPrompts(any(), any()) }
    }

    private companion object {
        /** 「尚未捕获」哨兵（真值可能是 null）。 */
        const val SENTINEL = "__uncaptured__"
    }
}
