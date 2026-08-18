package com.situ.aichat.ui.story

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.dao.UserStoryTemplateDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.PersonaPresets
import com.situ.aichat.story.StoryCreationCatalog
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryRoleType
import com.situ.aichat.story.StoryTemplates
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
 * 创建 VM T2（ST7b·契约 §11「开书装配·模板→实体字段对表」+ 生成起管线）：
 * ① 开书 sheet「开始连载」→ 用模板值落库 StoryEntity + 定向角色 + 触发生成（createFromTemplate 全链）；
 * ② 「改一改再开」templateId 预填 → VM init 按 arg 起底表单（无 arg = 空默认）。
 * 依赖 MockK 假掉（仓库/DAO/TaskManager）；viewModelScope 由 Robolectric 主循环驱动（照 WorldBookEntryEditViewModelTest 先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoryCreationViewModelTest {

    private val storyRepo = mockk<StoryRepository>()
    private val characterRepo = mockk<CharacterRepository>()
    private val userProfileDao = mockk<UserProfileDao>()
    private val templateDao = mockk<UserStoryTemplateDao>(relaxed = true)
    private val taskManager = mockk<StoryGenerationTaskManager>(relaxed = true)

    private val storySlot = slot<StoryEntity>()
    private val rolesSlot = slot<List<StoryCharacterRoleEntity>>()

    private val shen = CharacterEntity(uuid = "c-shen", name = "沈之衡", creationDate = 0L)
    private val lin = CharacterEntity(uuid = "c-lin", name = "林晚照", creationDate = 0L)

    private val scope = CoroutineScope(Dispatchers.Main)
    private val jobs = mutableListOf<Job>()

    @Before
    fun setUp() {
        coEvery { characterRepo.getAll() } returns listOf(shen, lin)
        every { userProfileDao.observe() } returns flowOf(null)
        coEvery { storyRepo.insertStory(capture(storySlot)) } just Runs
        coEvery { storyRepo.insertRoles(capture(rolesSlot)) } just Runs
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun vm(vararg args: Pair<String, Any?>): StoryCreationViewModel =
        StoryCreationViewModel(storyRepo, characterRepo, userProfileDao, templateDao, taskManager, SavedStateHandle(mapOf(*args)))

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun await(message: String, condition: () -> Boolean) {
        repeat(200) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    /** 订阅 characters（WhileSubscribed 冷流·无订阅者 .value 恒空）→ 等其吐出角色，模拟屏幕/开书 sheet 的订阅。 */
    private fun StoryCreationViewModel.loadCharacters() = also {
        jobs += scope.launch { characters.collect {} }
        await("角色加载") { characters.value.size == 2 }
    }

    private val template = StoryTemplates.all.first { it.genre == "言情" } // 结婚第一年·言情·网文爽文·second

    @Test
    fun 模板开书_落库带模板题材书名文风人称_并起生成() {
        val vm = vm().loadCharacters()
        var createdId: String? = null

        vm.createFromTemplate(template, mapOf(shen.uuid to StoryRoleType.PROTAGONIST), includeUserRole = false) { createdId = it }
        await("开书完成") { createdId != null }

        val story = storySlot.captured
        assertEquals("题材原样落库", template.genre, story.genre)
        assertEquals("书名 = 模板剧名（presetTitle 优先）", template.title, story.title)
        assertEquals("文风原样落库", template.writingStyle, story.writingStyle)
        assertEquals("不入场 → 旁观第三人称", "third", story.narrativePerson)
        assertEquals("封面配色按题材映射", StoryCreationCatalog.coverColorScheme(template.genre), story.coverColorScheme)

        val roles = rolesSlot.captured
        assertEquals("仅主演一角（未入场无用户角色）", 1, roles.size)
        assertEquals(StoryRoleType.PROTAGONIST, roles[0].roleType)
        assertEquals(shen.uuid, roles[0].characterId)
        assertFalse(roles[0].isUserRole)

        verify(exactly = 1) { taskManager.startGeneration(any()) }
        assertEquals(story.id, createdId)
    }

    /**
     * 卷二·单模式化（用户拍板①）看门狗：**新书一律无限连载**——创建流不再写 maxChapters/autoExtendCount，
     * 两列恒 null/0。原「连载模式」选择行与 `resolvedMaxChapters` 已整体退役，此例锁住那条不变量还在。
     */
    @Test
    fun 创建故事恒无连载上限() {
        val vm = vm().loadCharacters()
        var createdId: String? = null

        vm.createFromTemplate(template, mapOf(shen.uuid to StoryRoleType.PROTAGONIST), includeUserRole = false) { createdId = it }
        await("开书完成") { createdId != null }

        val story = storySlot.captured
        assertNull("新书无连载上限（无限连载·收尾交给终章弧）", story.maxChapters)
        assertEquals("新书自动扩展次数恒 0（机制已退役）", 0, story.autoExtendCount)
        assertNull("新书没有预约的收尾计划", story.finaleEndingType)
        assertNull("新书没有弧线简史", story.arcHistory)
    }

    @Test
    fun 模板开书_我也入场_追加用户主角且人称吃模板() {
        val vm = vm().loadCharacters()
        var done = false

        vm.createFromTemplate(template, mapOf(shen.uuid to StoryRoleType.PROTAGONIST), includeUserRole = true) { done = true }
        await("开书完成") { done }

        assertEquals("入场 = 吃模板人称（此模板 second）", "second", storySlot.captured.narrativePerson)
        val roles = rolesSlot.captured
        assertEquals("主演 + 我 两角", 2, roles.size)
        assertTrue("含用户角色", roles.any { it.isUserRole && it.characterId == null })
        assertTrue("含 AI 主演", roles.any { !it.isUserRole && it.characterId == shen.uuid })
    }

    @Test
    fun templateId预填_表单起底为模板值() {
        val f = vm("templateId" to template.id).form.value
        assertEquals(template.genre, f.selectedGenre)
        assertFalse(f.isCustomGenre)
        assertEquals(template.worldSetting, f.worldSetting)
        assertEquals(template.plotDirection, f.plotDirection)
        assertEquals(template.title, f.presetTitle)
        assertTrue("改一改默认入场", f.includeUserRole)
    }

    @Test
    fun 无templateId_表单为空默认() {
        val f = vm().form.value
        assertEquals(StoryCreationCatalog.genres.first(), f.selectedGenre)
        assertEquals("", f.worldSetting)
        assertNull(f.presetTitle)
        assertFalse(f.includeUserRole)
    }

    @Test
    fun 空templateId串_不预填() {
        val f = vm("templateId" to "").form.value
        assertNull("空串视同无模板", f.presetTitle)
        assertEquals("", f.worldSetting)
    }

    // ── 卷三 V2：创建流的节奏偏好组装（图纸 §3 数据流）──

    /** 走高级自定义表单开书（非模板路），跑完 createStory 全链后返回落库实体。 */
    private fun createWith(transform: (StoryCreationForm) -> StoryCreationForm): StoryEntity {
        val vm = vm().loadCharacters()
        vm.update(transform)
        var done = false
        vm.createStory { done = true }
        await("开书完成") { done }
        return storySlot.captured
    }

    @Test
    fun 预设题材只填节奏_也落一份仅含节奏的JSON() {
        // 写作口径三字段是自定义题材专属，节奏偏好与题材正交——预设题材照样能带。
        val story = createWith { it.copy(isCustomGenre = false, pacingPreference = "  慢热，多写日常  ") }
        val prompts = CustomStoryPrompts.decode(story.customPromptsJson)!!
        assertEquals("trim 后落库", "慢热，多写日常", prompts.pacingPreference)
        assertNull("预设题材不合成写作口径", prompts.genreTechniques)
        assertNull(prompts.writerIdentity)
        assertNull(prompts.writingRules)
    }

    @Test
    fun 自定义题材_节奏与三写作字段并存不互相顶掉() {
        val story = createWith {
            it.copy(
                isCustomGenre = true,
                customGenreName = "赛博武侠",
                customGenreTechniques = "技法",
                customWriterIdentity = "身份",
                customWritingRules = "规则",
                pacingPreference = "快节奏，别拖",
            )
        }
        val prompts = CustomStoryPrompts.decode(story.customPromptsJson)!!
        assertEquals("技法", prompts.genreTechniques)
        assertEquals("身份", prompts.writerIdentity)
        assertEquals("规则", prompts.writingRules)
        assertEquals("快节奏，别拖", prompts.pacingPreference)
    }

    @Test
    fun 预设题材且节奏留空_JSON列仍写null() {
        val story = createWith { it.copy(isCustomGenre = false, pacingPreference = "   ") }
        assertNull("四字段全空 → 不写 JSON（走预设默认）", story.customPromptsJson)
    }

    @Test
    fun 节奏超300字_创建时即钳位() {
        // 故事二期 D-8：上限 100 → 300。老上限那一档（150 字）现在必须原样保留，超 300 才截。
        val kept = createWith { it.copy(pacingPreference = "节".repeat(150)) }
        assertEquals(150, CustomStoryPrompts.decode(kept.customPromptsJson)!!.pacingPreference!!.length)

        val story = createWith { it.copy(pacingPreference = "节".repeat(350)) }
        val prompts = CustomStoryPrompts.decode(story.customPromptsJson)!!
        assertEquals(300, prompts.pacingPreference!!.length)
    }

    // ── 卷四 T2-3：身份预设 chips（E7）+ 节奏栏拒收超限（E6）──

    @Test
    fun e7_身份预设chips_整段替换不追加_开书后落最后一次填入的全文() {
        val vm = vm().loadCharacters()
        vm.update { it.copy(isCustomGenre = true, customGenreName = "赛博武侠") }

        // 点第一档 → 整段填入
        vm.update { it.copy(customWriterIdentity = PersonaPresets.DIRECT) }
        assertEquals(PersonaPresets.DIRECT, vm.form.value.customWriterIdentity)

        // 用户在此基础上改了两笔，再点第三档 → **整段替换**，绝不在旧文后面追加
        vm.update { it.copy(customWriterIdentity = vm.form.value.customWriterIdentity + "（我加的一句）") }
        vm.update { it.copy(customWriterIdentity = PersonaPresets.MIXED) }
        assertEquals(PersonaPresets.MIXED, vm.form.value.customWriterIdentity)
        assertFalse("整段替换 = 旧内容一个字都不该留下", vm.form.value.customWriterIdentity.contains("我加的一句"))

        var done = false
        vm.createStory { done = true }
        await("开书完成") { done }
        val prompts = CustomStoryPrompts.decode(storySlot.captured.customPromptsJson)!!
        assertEquals("落库的是最后填入的那一档全文", PersonaPresets.MIXED, prompts.writerIdentity)
    }

    @Test
    fun e7_三档预设文案取自单源常量_创建屏不另存一份() {
        // chips 的三档标签与全文一律来自 PersonaPresets.all（物料 M 单源），创建屏只消费不复制
        assertEquals(3, PersonaPresets.all.size)
        assertEquals(
            listOf(PersonaPresets.DIRECT, PersonaPresets.LITERARY, PersonaPresets.MIXED),
            PersonaPresets.all.map { it.second },
        )
    }

    @Test
    fun e6_节奏栏_到顶前照收_越界整笔拒收而不是截一半() {
        val max = CustomStoryPrompts.PACING_MAX_CHARS
        assertTrue("恰好到顶必须收下", acceptsPacingInput("节".repeat(max)))
        assertTrue(acceptsPacingInput("节".repeat(max - 1)))
        assertTrue("空输入（全删）当然收", acceptsPacingInput(""))
        assertFalse("超一个字就整笔拒收", acceptsPacingInput("节".repeat(max + 1)))
        assertFalse("粘贴 400 字同样拒收", acceptsPacingInput("节".repeat(400)))
    }

    @Test
    fun e6_节奏栏拒收后_表单原值一个字不动() {
        val vm = vm().loadCharacters()
        val max = CustomStoryPrompts.PACING_MAX_CHARS
        val filled = "节".repeat(max)
        if (acceptsPacingInput(filled)) vm.update { it.copy(pacingPreference = filled) }
        assertEquals(max, vm.form.value.pacingPreference.length)

        // 再敲一个字：闸拒收 → 屏幕不调 update → 原值不动（**不是**被截成 300 的新串）
        val overflow = filled + "拖"
        if (acceptsPacingInput(overflow)) vm.update { it.copy(pacingPreference = overflow) }
        assertEquals("原值必须逐字不变", filled, vm.form.value.pacingPreference)
    }

    @Test
    fun 模板开书_不带节奏偏好_JSON列恒null() {
        // B6：模板三步开书流零变化（节奏只在高级表单/设置页出现）。
        val vm = vm().loadCharacters()
        var done = false
        vm.createFromTemplate(template, mapOf(shen.uuid to StoryRoleType.PROTAGONIST), includeUserRole = false) { done = true }
        await("开书完成") { done }
        assertNull(storySlot.captured.customPromptsJson)
    }
}
