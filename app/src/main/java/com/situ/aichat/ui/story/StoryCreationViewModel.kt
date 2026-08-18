package com.situ.aichat.ui.story

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.dao.UserStoryTemplateDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.local.entity.UserStoryTemplateEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.model.UserStoryTemplatePayload
import com.situ.aichat.data.model.overlaidOnTemplate
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryChapterLength
import com.situ.aichat.story.StoryChatInfluenceWeight
import com.situ.aichat.story.StoryCreationCatalog
import com.situ.aichat.story.StoryCreationLogic
import com.situ.aichat.story.StoryGenerationTaskManager
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.story.StoryRoleType
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.story.StoryTemplate
import com.situ.aichat.story.StoryTemplates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 用户人设来源（1:1 iOS UserPersonaSource）。 */
enum class UserPersonaSource { PROFILE, CUSTOM }

/**
 * 「本书专属角色」草稿（图纸二 D1·创建屏攒在表单里，随开书一次性落库）：
 * 外貌/口癖/称呼/关系全写进一段自由 [description]（2026-08-01 用户拍板：不分字段、零迁移）。
 */
data class CustomRoleDraft(
    val name: String,
    /** [StoryRoleType] raw。 */
    val type: String = StoryRoleType.SUPPORTING,
    val description: String = "",
)

/** 替换第 [index] 个专属角色草稿；越界原样返回（表单的唯一改法，UI 与测试共用同一处逻辑）。 */
internal fun StoryCreationForm.withCustomRoleAt(index: Int, draft: CustomRoleDraft): StoryCreationForm =
    if (index !in customRoles.indices) this else copy(customRoles = customRoles.toMutableList().also { it[index] = draft })

/** 删掉第 [index] 个专属角色草稿；越界原样返回。 */
internal fun StoryCreationForm.withoutCustomRoleAt(index: Int): StoryCreationForm =
    if (index !in customRoles.indices) this else copy(customRoles = customRoles.filterIndexed { i, _ -> i != index })

/** 故事创建表单状态（1:1 iOS StoryCreationView @State 集合）。 */
data class StoryCreationForm(
    val selectedGenre: String = StoryCreationCatalog.genres.first(),
    val isCustomGenre: Boolean = false,
    val customGenreName: String = "",
    val referenceGenre: String? = null,
    val customGenreTechniques: String = "",
    val customWriterIdentity: String = "",
    val customWritingRules: String = "",
    /** charId → roleType。 */
    val selectedRoles: Map<String, String> = emptyMap(),
    /** charId → 额外人设草稿。 */
    val roleDescriptions: Map<String, String> = emptyMap(),
    /** 本书专属角色（图纸二 D1）：不关联任何聊天角色，开书时作第三族一并落库。 */
    val customRoles: List<CustomRoleDraft> = emptyList(),
    val includeUserRole: Boolean = false,
    val userRoleName: String = "我",
    val userRoleType: String = StoryRoleType.PROTAGONIST,
    val userPersonaSource: UserPersonaSource = UserPersonaSource.PROFILE,
    val customUserPersona: String = "",
    val worldSetting: String = "",
    val plotDirection: String = "",
    /** 节奏偏好（卷三 V2·选填一句话）：与题材无关，预设题材也能填（写进 customPromptsJson 第四字段）。 */
    val pacingPreference: String = "",
    val writingStyle: String = StoryCreationCatalog.writingStyles.first(),
    val chapterLength: StoryChapterLength = StoryChapterLength.MEDIUM,
    // 卷二·单模式化：serialMode / customMaxChaptersText 两字段随「连载模式」选择行整体退役（故事恒无限连载）。
    val chatInfluenceWeight: String = StoryCreationCatalog.DEFAULT_CHAT_INFLUENCE,
    val narrativePerson: String = StoryNarrativePerson.SECOND,
    /** 模板开书预填的书名（非空则作最终标题，见 [StoryCreationLogic.resolvedTitle]）；自定义流为 null。 */
    val presetTitle: String? = null,
    /**
     * 「我的模板」套用（图纸四 §3.4）：模板存下的整串 `customPromptsJson`。**无 UI 控件**——
     * 这些项开书后在设定页都能改，创建表单不为它们加控件。装配时作**底**、表单里能编辑的字段覆盖在上
     * （[com.situ.aichat.data.model.overlaidOnTemplate]），忌口与两个格式开关只住在这一串里。
     * null = 非模板路 → 装配恒等，`createStory` 产物逐字节同现状（E5 回归钉）。
     */
    val templatePromptsJson: String? = null,
    /** 「我的模板」套用的追更三值（同为无 UI 隐藏字段）。null = 非模板路 → 吃 [StoryEntity] 默认。 */
    val templateUpdateMode: String? = null,
    val templateUnlockHour: Int? = null,
    val templateUnlockMinute: Int? = null,
    /**
     * 「我的模板」套用的章节长度**原值**（同为无 UI 隐藏字段）。
     * [chapterLength] 是四档枚举，只装得下 500/1500/3000/5000；模板存的是 Int 列，
     * 落库时以本字段为准，四档之外的存量数值（老书 / 将来放开自定义字数）才不会被悄悄四舍五入。
     */
    val templateChapterLength: Int? = null,
)

/**
 * 故事创建 VM（1:1 iOS `StoryCreationView`+`+CreationLogic`）。
 *
 * 持表单状态、加载可选 AI 角色 + 用户资料；createStory 建 StoryEntity + 角色定向插入，随即起首章生成
 * （= iOS「保存设定后立刻生成第一章」，统一走 [StoryGenerationTaskManager]）。**有意简化**：iOS 自定义类型读
 * ScenePromptOverride 全局默认作初值，安卓未移植该 scene-override，故 override 传空、仅用用户输入。
 */
@HiltViewModel
class StoryCreationViewModel @Inject constructor(
    private val storyRepository: StoryRepository,
    private val characterRepository: CharacterRepository,
    private val userProfileDao: UserProfileDao,
    private val userStoryTemplateDao: UserStoryTemplateDao,
    private val taskManager: StoryGenerationTaskManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _form = MutableStateFlow(StoryCreationForm())
    val form: StateFlow<StoryCreationForm> = _form.asStateFlow()

    init {
        // 「改一改再开」（ST7b·契约 §3.1）：模板墙 sheet 带 templateId 跳进高级自定义 → 用模板预填值起底
        // （世界观/剧情/题材/文风/人称/剧名·选角在高级表单自选 → 空 roles + 默认入场）；无 arg（模板墙自身/
        // 直开自定义）时不预填、留默认空表单。
        savedStateHandle.get<String>("templateId")?.takeIf { it.isNotBlank() }?.let { id ->
            // 图纸四：同一个路由参数承载两种模板，按 `user:` 前缀分派（内置 id 无冒号·E15）。
            val userTemplateUuid = id.userTemplateUuidOrNull()
            if (userTemplateUuid != null) {
                viewModelScope.launch {
                    loadUserTemplate(userTemplateUuid)?.let { payload ->
                        _form.value = StoryTemplateAssembly.toCreationFormFromUserTemplate(payload, emptyMap(), includeUserRole = true)
                    }
                }
            } else {
                StoryTemplates.all.firstOrNull { it.id == id }?.let { template ->
                    _form.value = StoryTemplateAssembly.toCreationForm(template, emptyMap(), includeUserRole = true)
                }
            }
        }
    }

    /** `"user:<uuid>"` → uuid；内置模板 id（无前缀）→ null。 */
    private fun String.userTemplateUuidOrNull(): String? =
        removePrefix(UserStoryTemplatePayload.USER_TEMPLATE_ID_PREFIX)
            .takeIf { startsWith(UserStoryTemplatePayload.USER_TEMPLATE_ID_PREFIX) && it.isNotBlank() }

    /** 读一个用户模板的整套设定；行没了 / payload 损坏 → null（E7：不崩，由调用方给提示）。 */
    private suspend fun loadUserTemplate(uuid: String): UserStoryTemplatePayload? =
        UserStoryTemplatePayload.decode(userStoryTemplateDao.byUuid(uuid)?.payloadJson)

    val characters: StateFlow<List<CharacterEntity>> =
        flow { emit(characterRepository.getAll()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val userProfile: StateFlow<UserProfileEntity?> =
        userProfileDao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _toastEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    /** 一次性提示事件（string res id），屏幕 collect 转 Toast（照 `StoryBookshelfViewModel` 先例）。 */
    val toastEvents = _toastEvents.asSharedFlow()

    // ── 我的模板（图纸四 §3.4）──

    /** 模板墙「我的模板」区数据源（新存的排前面；空 = 整个区不渲染，模板墙与现状零差异）。 */
    val userTemplates: StateFlow<List<UserStoryTemplateEntity>> = userStoryTemplateDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 重命名（只动名字·空白由 UI 侧禁用保存键把关·E9）。 */
    fun renameUserTemplate(uuid: String, name: String) {
        viewModelScope.launch {
            runCatching { userStoryTemplateDao.rename(uuid, name.trim()) }
                .onFailure { _error.value = it.message; Log.e(TAG, "重命名模板失败", it) }
        }
    }

    /** 删除模板——**已开的书零影响**（模板与故事无任何外键，设定早已落进各自的书里·E10）。 */
    fun deleteUserTemplate(uuid: String) {
        viewModelScope.launch {
            runCatching { userStoryTemplateDao.delete(uuid) }
                .onFailure { _error.value = it.message; Log.e(TAG, "删除模板失败", it) }
        }
    }

    fun update(transform: (StoryCreationForm) -> StoryCreationForm) = _form.update(transform)

    fun dismissError() { _error.value = null }

    /**
     * 开书 sheet「开始连载」（ST7b·契约 §3.1）：模板 + sheet 选角（主演单选 + 配角多选）+「我也入场」→ 用
     * [StoryTemplateAssembly] 装配成表单，随即走同一条 [createStory] 管线（生成管线零改动·J3 权重/连载/章长吃默认）。
     */
    fun createFromTemplate(
        template: StoryTemplate,
        selectedRoles: Map<String, String>,
        includeUserRole: Boolean,
        onCreated: (String) -> Unit,
    ) {
        if (_creating.value) return
        val userTemplateUuid = template.id.userTemplateUuidOrNull()
        if (userTemplateUuid == null) {
            _form.value = StoryTemplateAssembly.toCreationForm(template, selectedRoles, includeUserRole)
            createStory(onCreated)
            return
        }
        // 图纸四「我的模板」：设定住在库里，先取件再装配。抢闸防连点——放闸与 createStory 抢闸之间
        // 没有挂起点（同在 Main 上），中间插不进第二次点击。
        _creating.value = true
        viewModelScope.launch {
            val payload = loadUserTemplate(userTemplateUuid)
            _creating.value = false
            if (payload == null) {
                _toastEvents.tryEmit(R.string.story_template_broken) // E7：不崩、给一句提示，卡还能长按删掉
                Log.e(TAG, "用户模板已损坏或已删除 id=${template.id}")
                return@launch
            }
            _form.value = StoryTemplateAssembly.toCreationFormFromUserTemplate(payload, selectedRoles, includeUserRole)
            createStory(onCreated)
        }
    }

    /** 创建并立刻生成第一章；成功回调返回新故事 id（供导航）。 */
    fun createStory(onCreated: (String) -> Unit) {
        if (_creating.value) return
        _creating.value = true
        val f = _form.value
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                val genre = StoryCreationLogic.resolvedGenre(f.isCustomGenre, f.customGenreName, f.selectedGenre)
                val chars = characters.value
                // 标题一律「{类型}故事」（用户拍板 2026-07-13·去角色名）；模板 presetTitle 仍优先。
                val title = StoryCreationLogic.resolvedTitle(presetTitle = f.presetTitle, genre = genre)
                // 卷三 V2：写作口径三字段仍只在自定义题材下合成；节奏偏好与题材正交，任何故事都可带
                // （预设题材故事因此可能持有仅含 pacingPreference 的 JSON）。全空 → 仍写 null。
                val basePrompts = if (f.isCustomGenre) {
                    CustomStoryPrompts.composeForCreation(
                        userWriterIdentity = StoryCreationLogic.normalizedText(f.customWriterIdentity),
                        userGenreTechniques = StoryCreationLogic.normalizedText(f.customGenreTechniques),
                        userWritingRules = StoryCreationLogic.normalizedText(f.customWritingRules),
                        overrideWriterIdentity = "",
                        overrideGenreTechniques = "",
                        overrideWritingRules = "",
                    )
                } else {
                    CustomStoryPrompts()
                }
                // 图纸四：套用「我的模板」时，模板那串作底、表单值覆盖在上（忌口与两个格式开关只住模板串里）。
                // 非模板路 templatePromptsJson=null → overlaidOnTemplate 恒等 → 产物逐字节同现状（E5）。
                val prompts = basePrompts
                    .copy(pacingPreference = CustomStoryPrompts.normalizedPacing(f.pacingPreference))
                    .overlaidOnTemplate(CustomStoryPrompts.decode(f.templatePromptsJson))
                val customPromptsJson = if (prompts.hasAnyValue) CustomStoryPrompts.encode(prompts) else null
                val story = StoryEntity(
                    title = title,
                    genre = genre,
                    coverColorScheme = StoryCreationCatalog.coverColorScheme(genre),
                    createdAt = now,
                    updatedAt = now,
                    worldSetting = StoryCreationLogic.normalizedText(f.worldSetting),
                    plotDirection = StoryCreationLogic.normalizedText(f.plotDirection),
                    writingStyle = f.writingStyle,
                    chapterLengthPreference = f.chapterLength.words,
                    // 卷二·单模式化：新书一律无限连载（maxChapters 恒 null=实体默认），收尾走终章弧。
                    chatInfluenceWeight = f.chatInfluenceWeight,
                    narrativePerson = f.narrativePerson,
                    customPromptsJson = customPromptsJson,
                    status = StoryStatus.SERIALIZING,
                ).let { s ->
                    // 图纸四：模板带来的追更设置与章长原值（非模板路恒 null → 原样返回，实体默认值不受影响）。
                    if (f.templateUpdateMode == null) {
                        s
                    } else {
                        s.copy(
                            updateMode = f.templateUpdateMode,
                            unlockHour = f.templateUnlockHour ?: s.unlockHour,
                            unlockMinute = f.templateUnlockMinute ?: s.unlockMinute,
                            chapterLengthPreference = f.templateChapterLength ?: s.chapterLengthPreference,
                        )
                    }
                }
                storyRepository.insertStory(story)

                val roles = buildList {
                    chars.filter { f.selectedRoles.containsKey(it.uuid) }.forEach { ch ->
                        add(
                            StoryCharacterRoleEntity(
                                storyId = story.id,
                                roleName = ch.name,
                                roleType = f.selectedRoles.getValue(ch.uuid),
                                roleDescription = StoryCreationLogic.normalizedText(f.roleDescriptions[ch.uuid] ?: ""),
                                isUserRole = false,
                                characterId = ch.uuid,
                            ),
                        )
                    }
                    // 第三族：本书专属角色（图纸二 D1）——不关联聊天角色也不是用户角色，
                    // 信息全在自由描述里（提示词侧对 characterId=null 的行本就只读 roleDescription，零改动）。
                    // 空名字的草稿不落库：没名字的角色无法被提示词引用。
                    f.customRoles.filter { it.name.isNotBlank() }.forEach { draft ->
                        add(
                            StoryCharacterRoleEntity(
                                storyId = story.id,
                                roleName = draft.name.trim(),
                                roleType = draft.type,
                                roleDescription = StoryCreationLogic.normalizedText(draft.description),
                                isUserRole = false,
                                characterId = null,
                            ),
                        )
                    }
                    if (f.includeUserRole) {
                        val persona = when (f.userPersonaSource) {
                            UserPersonaSource.PROFILE -> StoryCreationLogic.normalizedText(userProfile.value?.bio ?: "")
                            UserPersonaSource.CUSTOM -> StoryCreationLogic.normalizedText(f.customUserPersona)
                        }
                        add(
                            StoryCharacterRoleEntity(
                                storyId = story.id,
                                roleName = StoryCreationLogic.normalizedText(f.userRoleName) ?: "我",
                                roleType = f.userRoleType,
                                roleDescription = persona,
                                isUserRole = true,
                                characterId = null,
                            ),
                        )
                    }
                }
                storyRepository.insertRoles(roles)
                taskManager.startGeneration(story)
                Log.i(TAG, "创建故事 ${story.id} genre=$genre roles=${roles.size}")
                story.id
            }.onSuccess { id ->
                _creating.value = false
                onCreated(id)
            }.onFailure { e ->
                _creating.value = false
                _error.value = e.message
                Log.e(TAG, "创建故事失败", e)
            }
        }
    }

    private companion object {
        const val TAG = "StoryCreation"
    }
}
