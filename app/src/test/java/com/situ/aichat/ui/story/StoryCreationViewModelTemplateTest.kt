package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.dao.UserStoryTemplateDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.local.entity.UserStoryTemplateEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.model.UserStoryTemplatePayload
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryChatInfluenceWeight
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.story.StoryRoleType
import com.situ.aichat.story.StoryTemplate
import com.situ.aichat.story.StoryTemplates
import com.situ.aichat.story.StoryUpdateMode
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「我的模板」开书 T2（图纸四 §3.4 / §5 E5-E7 / §7 T2-2）。
 *
 * 四件命门：
 * 1. **E5 回归钉**——普通创建（无模板）建出的 StoryEntity 与 customPromptsJson 逐字段同现状：
 *    四个隐藏字段恒默认时，装配必须是恒等变换（不许因为新加了 copy-merge 就悄悄改了产物）。
 * 2. **E6 全量灌**——模板 13 个字段逐个落到实体 / customPromptsJson，忌口与章末选项开关经 copy-merge 不丢不串。
 *    另钉 2026-08-03 图纸 §5-E9：**存于 2026-08-03 之前、payload 里还带着已删字段 `immersiveMarkupEnabled`
 *    的老模板照样能套用**（未知键被忽略，其余字段一个不少）。
 * 3. **`user:` 前缀分派**——同一个路由参数承载两种模板，内置那条路一个字没变。
 * 4. **E7 损坏**——payload 坏了不崩、不建书，给一句提示。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryCreationViewModelTemplateTest {

    private val storyRepo = mockk<StoryRepository>()
    private val characterRepo = mockk<CharacterRepository>()
    private val userProfileDao = mockk<UserProfileDao>()
    private val templateDao = mockk<UserStoryTemplateDao>()
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)

    private val storySlot = slot<StoryEntity>()
    private val rolesSlot = slot<List<StoryCharacterRoleEntity>>()
    private val shen = CharacterEntity(uuid = "c-shen", name = "沈之衡", creationDate = 0L)

    private val scope = CoroutineScope(Dispatchers.Main)
    private val jobs = mutableListOf<Job>()

    @Before
    fun setUp() {
        coEvery { characterRepo.getAll() } returns listOf(shen)
        every { userProfileDao.observe() } returns flowOf(null)
        every { templateDao.observeAll() } returns flowOf(emptyList())
        coEvery { storyRepo.insertStory(capture(storySlot)) } just Runs
        coEvery { storyRepo.insertRoles(capture(rolesSlot)) } just Runs
    }

    @After fun tearDown() {
        jobs.forEach { it.cancel() }
        scope.cancel()
    }

    private fun vm(vararg args: Pair<String, Any?>) =
        StoryCreationViewModel(storyRepo, characterRepo, userProfileDao, templateDao, taskManager, SavedStateHandle(mapOf(*args)))

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** 订阅 characters（WhileSubscribed 冷流·无订阅者 .value 恒空）→ 等其吐出角色，模拟开书 sheet 的订阅。 */
    private fun StoryCreationViewModel.withCharacters(): StoryCreationViewModel {
        jobs += scope.launch { characters.collect {} }
        await("角色加载") { characters.value.size == 1 }
        return this
    }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    /** 一套字段全非默认的模板（漏抄一个就红）。 */
    private val payload = UserStoryTemplatePayload(
        genre = "赛博修真",
        isCustomGenre = true,
        writingStyle = "哥特暗黑",
        narrativePerson = StoryNarrativePerson.FIRST,
        chapterLengthPreference = 2600,
        chatInfluenceWeight = StoryChatInfluenceWeight.HEAVY,
        worldSetting = "民国上海",
        plotDirection = "重逢",
        updateMode = StoryUpdateMode.CHASE,
        unlockHour = 7,
        unlockMinute = 45,
        customPromptsJson = CustomStoryPrompts.encode(
            CustomStoryPrompts(
                genreTechniques = "技法",
                writerIdentity = "身份",
                writingRules = "铁律",
                pacingPreference = "慢热",
                bannedExpressions = "忌口",
                chapterChoicesEnabled = false,
            ),
        ),
    )

    private fun row(uuid: String = "t1", json: String = UserStoryTemplatePayload.encode(payload)) =
        UserStoryTemplateEntity(uuid = uuid, name = "深夜都市线", createdAt = 1_700L, payloadJson = json)

    private fun userTemplate(uuid: String = "t1") = StoryTemplate(
        id = UserStoryTemplatePayload.USER_TEMPLATE_ID_PREFIX + uuid,
        title = "深夜都市线", tagline = "", genre = "赛博修真", writingStyle = "哥特暗黑",
        narrativePerson = StoryNarrativePerson.FIRST, worldSetting = "民国上海", plotDirection = "重逢",
        roleHint = "", coverMotif = "",
    )

    // ── E5：普通创建路逐字段同现状 ──

    @Test
    fun 普通创建_隐藏字段全默认_产物同现状() {
        val vm = vm().withCharacters()
        vm.update { it.copy(selectedRoles = mapOf("c-shen" to StoryRoleType.PROTAGONIST)) }
        vm.createStory {}
        await("建书") { storySlot.isCaptured }
        val s = storySlot.captured
        assertEquals("题材走选择器默认", StoryCreationCatalogFirstGenre, s.genre)
        assertEquals("追更设置吃实体默认", StoryUpdateMode.FREE, s.updateMode)
        assertEquals(20, s.unlockHour)
        assertEquals(0, s.unlockMinute)
        assertNull("没填任何自定义提示词 → JSON 仍为 null（copy-merge 必须是恒等）", s.customPromptsJson)
    }

    @Test
    fun 普通创建_只填节奏偏好_JSON里只有那一项() {
        val vm = vm()
        vm.update { it.copy(pacingPreference = "慢热，多写日常") }
        vm.createStory {}
        await("建书") { storySlot.isCaptured }
        val p = CustomStoryPrompts.decode(storySlot.captured.customPromptsJson)!!
        assertEquals("慢热，多写日常", p.pacingPreference)
        assertNull(p.bannedExpressions)
        assertNull(p.chapterChoicesEnabled)
        assertNull(p.writerIdentity)
    }

    // ── E6：模板套用全量落位 ──

    @Test
    fun 用户模板开书_十三字段逐个落到实体与JSON() {
        coEvery { templateDao.byUuid("t1") } returns row()
        val vm = vm().withCharacters()
        vm.createFromTemplate(userTemplate(), mapOf("c-shen" to StoryRoleType.PROTAGONIST), includeUserRole = true) {}
        await("建书") { storySlot.isCaptured }
        val s = storySlot.captured
        assertEquals("赛博修真", s.genre)
        assertEquals("哥特暗黑", s.writingStyle)
        assertEquals(StoryNarrativePerson.FIRST, s.narrativePerson)
        assertEquals(2600, s.chapterLengthPreference)
        assertEquals(StoryChatInfluenceWeight.HEAVY, s.chatInfluenceWeight)
        assertEquals("民国上海", s.worldSetting)
        assertEquals("重逢", s.plotDirection)
        assertEquals("追更设置随模板", StoryUpdateMode.CHASE, s.updateMode)
        assertEquals(7, s.unlockHour)
        assertEquals(45, s.unlockMinute)
        assertEquals("模板不存书名 → 标题回落「{题材}故事」", "赛博修真故事", s.title)

        val p = CustomStoryPrompts.decode(s.customPromptsJson)!!
        assertEquals("技法", p.genreTechniques)
        assertEquals("身份", p.writerIdentity)
        assertEquals("铁律", p.writingRules)
        assertEquals("慢热", p.pacingPreference)
        assertEquals("忌口只住模板串里，copy-merge 丢了就红", "忌口", p.bannedExpressions)
        assertEquals(false, p.chapterChoicesEnabled)
    }

    @Test
    fun E9_老模板payload含已删的沉浸标记键_照样套用且其余字段不丢() {
        // 2026-08-03 之前存下的模板：customPromptsJson 里还带着 immersiveMarkupEnabled。
        val legacyPrompts =
            """{"genreTechniques":"技法","bannedExpressions":"忌口","chapterChoicesEnabled":false,"immersiveMarkupEnabled":false}"""
        val legacyPayload = payload.copy(customPromptsJson = legacyPrompts)
        coEvery { templateDao.byUuid("t1") } returns row(json = UserStoryTemplatePayload.encode(legacyPayload))

        val vm = vm().withCharacters()
        vm.createFromTemplate(userTemplate(), mapOf("c-shen" to StoryRoleType.PROTAGONIST), includeUserRole = true) {}
        await("建书") { storySlot.isCaptured }

        val s = storySlot.captured
        assertEquals("模板其余字段照常落位", "赛博修真", s.genre)
        val p = CustomStoryPrompts.decode(s.customPromptsJson)!!
        assertEquals("技法", p.genreTechniques)
        assertEquals("忌口", p.bannedExpressions)
        assertEquals(false, p.chapterChoicesEnabled)
        assertFalse("已删字段不许被写回新书", s.customPromptsJson!!.contains("immersiveMarkupEnabled"))
    }

    @Test
    fun 用户模板开书_预设题材模板也原样带回题材() {
        val preset = payload.copy(genre = "言情", isCustomGenre = false)
        coEvery { templateDao.byUuid("t1") } returns row(json = UserStoryTemplatePayload.encode(preset))
        val vm = vm()
        vm.createFromTemplate(userTemplate().copy(genre = "言情"), emptyMap(), includeUserRole = true) {}
        await("建书") { storySlot.isCaptured }
        assertEquals("言情", storySlot.captured.genre)
    }

    @Test
    fun 用户模板开书_我也入场关掉则改旁观第三人称() {
        coEvery { templateDao.byUuid("t1") } returns row()
        val vm = vm()
        vm.createFromTemplate(userTemplate(), emptyMap(), includeUserRole = false) {}
        await("建书") { storySlot.isCaptured }
        assertEquals(StoryNarrativePerson.THIRD, storySlot.captured.narrativePerson)
        assertTrue("不入场就没有用户角色行", rolesSlot.captured.none { it.isUserRole })
    }

    @Test
    fun 用户模板开书_模板不带角色_选角仍来自开书sheet() {
        coEvery { templateDao.byUuid("t1") } returns row()
        val vm = vm().withCharacters()
        vm.createFromTemplate(userTemplate(), mapOf("c-shen" to StoryRoleType.PROTAGONIST), includeUserRole = true) {}
        await("建书") { storySlot.isCaptured }
        assertEquals(listOf("沈之衡", "我"), rolesSlot.captured.map { it.roleName })
    }

    // ── E7：损坏模板 ──

    @Test
    fun 损坏模板_不建书不崩() {
        coEvery { templateDao.byUuid("t1") } returns row(json = "{不是 JSON")
        val vm = vm()
        vm.createFromTemplate(userTemplate(), emptyMap(), includeUserRole = true) {}
        repeat(40) { idle(); Thread.sleep(5) }
        assertFalse("坏模板绝不能建出半套设定的书", storySlot.isCaptured)
        assertFalse("也不该卡在建书中", vm.creating.value)
    }

    @Test
    fun 模板行已被删_不建书不崩() {
        coEvery { templateDao.byUuid("t1") } returns null
        val vm = vm()
        vm.createFromTemplate(userTemplate(), emptyMap(), includeUserRole = true) {}
        repeat(40) { idle(); Thread.sleep(5) }
        assertFalse(storySlot.isCaptured)
        assertFalse(vm.creating.value)
    }

    // ── `user:` 前缀分派 ──

    @Test
    fun 内置模板开书_不查模板表_那条路零变() {
        val builtIn = StoryTemplates.all.first()
        val vm = vm()
        vm.createFromTemplate(builtIn, emptyMap(), includeUserRole = true) {}
        await("建书") { storySlot.isCaptured }
        coVerify(exactly = 0) { templateDao.byUuid(any()) }
        assertEquals("内置模板照旧预填书名", builtIn.title, storySlot.captured.title)
        assertEquals(builtIn.genre, storySlot.captured.genre)
    }

    @Test
    fun 改一改再开_user前缀路由预填表单() {
        coEvery { templateDao.byUuid("t1") } returns row()
        val vm = vm("templateId" to (UserStoryTemplatePayload.USER_TEMPLATE_ID_PREFIX + "t1"))
        await("表单预填") { vm.form.value.worldSetting == "民国上海" }
        val f = vm.form.value
        assertEquals("赛博修真", f.customGenreName)
        assertTrue(f.isCustomGenre)
        assertEquals("哥特暗黑", f.writingStyle)
        assertEquals("重逢", f.plotDirection)
        assertEquals("身份", f.customWriterIdentity)
        assertEquals("慢热", f.pacingPreference)
        assertNull("模板不带书名", f.presetTitle)
        assertEquals("隐藏字段带着整串模板设定", payload.customPromptsJson, f.templatePromptsJson)
        assertEquals(StoryUpdateMode.CHASE, f.templateUpdateMode)
    }

    @Test
    fun 改一改再开_内置模板id照旧走内置目录() {
        val builtIn = StoryTemplates.all.first()
        val vm = vm("templateId" to builtIn.id)
        idle()
        assertEquals(builtIn.title, vm.form.value.presetTitle)
        assertEquals(builtIn.worldSetting, vm.form.value.worldSetting)
        assertNull("内置路不该带模板串", vm.form.value.templatePromptsJson)
    }

    private companion object {
        /** 空表单的默认题材 = 目录第一项（在测试里重打一次，避免跟着实现漂移）。 */
        const val StoryCreationCatalogFirstGenre = "言情"
    }
}
