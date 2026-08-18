package com.situ.aichat.ui.character

import android.content.Context
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.personalitySpectrum
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.economy.CharacterEconomyMaintenanceService
import com.situ.aichat.economy.CurrencyService
import com.situ.aichat.prompt.PromptModuleService
import com.situ.aichat.tts.SystemVoiceOption
import com.situ.aichat.tts.TtsConfigurationRepository
import com.situ.aichat.tts.TtsPreviewer
import com.situ.aichat.tts.TtsProviderType
import com.situ.aichat.tts.TtsService
import com.situ.aichat.tts.provider.TtsRemoteConfigValues
import com.situ.aichat.util.WallpaperStore
import com.situ.aichat.work.NotificationTemplateWorker
import com.situ.aichat.world.member.WorldMembershipService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

/** Age mode raw values, mirroring iOS `CharacterAgeMode`. */
object AgeMode {
    const val GROWING = "growing"   // 随时间增长
    const val FIXED = "fixed"       // 固定年龄
}

/**
 * Editable identity fields for the create/edit form. A deliberate subset of [CharacterEntity]:
 * memory/growth/mood/economy/location columns are NOT exposed here and are preserved on edit
 * (see [applyTo]). City (P5), relationship & growth dimensions (P4), offline theme (P10) and
 * prompt-module overrides (P2.5) are intentionally out of scope for this chunk.
 *
 * `@Serializable`：E1#0 进程死亡草稿整包经 [CharacterEditDraftCodec] 进 SavedStateHandle（见 VM init）。
 */
@Serializable
data class CharacterEditState(
    val name: String = "",
    val gender: String = "",
    val birthdayMillis: Long? = null,
    val ageModeRaw: String = AgeMode.GROWING,
    val fixedAge: String = "",          // text field; parsed to Int on save (iOS bound to Int)
    val occupation: String = "",
    val personalityDescription: String = "",
    val appearanceDescription: String = "",
    val backstory: String = "",
    val catchphrases: String = "",
    val speakingStyle: String = "",
    val exampleDialogues: String = "",
    val initialInterests: String = "",
    val systemPrompt: String = "",
    // 语音：voiceIdentifier=系统音色（全局引擎=系统时用）；remoteVoiceID/emotion/speed/pitch=远程/MiniMax 专属。
    val voiceIdentifier: String = "",
    val remoteVoiceID: String = "",
    val ttsEmotionRaw: String = "auto",
    val ttsSpeed: Double = 1.0,
    val ttsPitch: Int = 0,
    val avatarPath: String? = null,
    val chatWallpaperPath: String? = null,
    // 14.1e 成长维度（8 维性格 + 8 维关系）。create 写入 JSON 列；edit 仅在改动时列级写回（见 [save]）。
    val personalitySpectrum: PersonalitySpectrum = PersonalitySpectrum.NEUTRAL,
    val relationshipQuality: RelationshipQuality = RelationshipQuality.INITIAL,
    // 14.1e-2 关系定义（→ 播种/追加里程碑）+ 线下主题色（6 位 hex，空=默认 teal）。
    val relationshipName: String = "",
    val offlineThemeColorHex: String = "",
    // W13：新建模式「加入世界」暂存开关（编辑模式由 CharacterWorldViewModel 直接管，本字段仅 create 用·图纸 §3.3）。
    // save() 成功插入后按此调 membershipService.join，故 toNewEntity/applyTo 都不写 joinedWorld 列。
    val joinWorld: Boolean = false,
) {
    val canSave: Boolean get() = name.isNotBlank()

    /** Build a brand-new character (fresh uuid + creationDate); untouched fields take iOS defaults. */
    fun toNewEntity(): CharacterEntity = CharacterEntity(
        uuid = UUID.randomUUID().toString(),
        name = name.trim(),
        avatarPath = avatarPath,
        chatWallpaperPath = chatWallpaperPath,
        systemPrompt = systemPrompt,
        personalityDescription = personalityDescription,
        creationDate = System.currentTimeMillis(),
        gender = gender,
        birthday = birthdayMillis,
        ageModeRaw = ageModeRaw,
        fixedAge = fixedAge.trim().toIntOrNull() ?: 0,
        appearanceDescription = appearanceDescription,
        occupation = occupation,
        backstory = backstory,
        speakingStyle = speakingStyle,
        catchphrases = catchphrases,
        exampleDialogues = exampleDialogues,
        initialInterests = initialInterests,
        voiceIdentifier = voiceIdentifier,
        remoteVoiceID = remoteVoiceID,
        ttsEmotionRaw = ttsEmotionRaw,
        ttsSpeed = ttsSpeed,
        ttsPitch = ttsPitch,
        // 默认维度（NEUTRAL/INITIAL）写空 JSON（解码即默认）；用户动过滑块才落 JSON。
        personalitySpectrumJSON = if (personalitySpectrum != PersonalitySpectrum.NEUTRAL) GrowthJson.encode(personalitySpectrum) else "",
        relationshipQualityJSON = if (relationshipQuality != RelationshipQuality.INITIAL) GrowthJson.encode(relationshipQuality) else "",
        offlineThemeColorHex = offlineThemeColorHex.ifBlank { null },
    )

    /**
     * Apply the form-managed fields onto [existing] (trim/parse transforms only). Used by [save] purely
     * to derive the editable column values for a targeted column-level write (P12.6 D1c) — the
     * memory/growth/mood/streak/relationship columns it carries over are NOT written back, so a concurrent
     * background analysis write is not resurrected to its open-screen snapshot value.
     */
    fun applyTo(existing: CharacterEntity): CharacterEntity = existing.copy(
        name = name.trim(),
        avatarPath = avatarPath,
        chatWallpaperPath = chatWallpaperPath,
        systemPrompt = systemPrompt,
        personalityDescription = personalityDescription,
        gender = gender,
        birthday = birthdayMillis,
        ageModeRaw = ageModeRaw,
        fixedAge = fixedAge.trim().toIntOrNull() ?: 0,
        appearanceDescription = appearanceDescription,
        occupation = occupation,
        backstory = backstory,
        speakingStyle = speakingStyle,
        catchphrases = catchphrases,
        exampleDialogues = exampleDialogues,
        initialInterests = initialInterests,
        voiceIdentifier = voiceIdentifier,
        remoteVoiceID = remoteVoiceID,
        ttsEmotionRaw = ttsEmotionRaw,
        ttsSpeed = ttsSpeed,
        ttsPitch = ttsPitch,
        offlineThemeColorHex = offlineThemeColorHex.ifBlank { null },
    )

    companion object {
        fun from(c: CharacterEntity): CharacterEditState = CharacterEditState(
            name = c.name,
            gender = c.gender,
            birthdayMillis = c.birthday,
            ageModeRaw = c.ageModeRaw,
            fixedAge = if (c.fixedAge > 0) c.fixedAge.toString() else "",
            occupation = c.occupation,
            personalityDescription = c.personalityDescription,
            appearanceDescription = c.appearanceDescription,
            backstory = c.backstory,
            catchphrases = c.catchphrases,
            speakingStyle = c.speakingStyle,
            exampleDialogues = c.exampleDialogues,
            initialInterests = c.initialInterests,
            systemPrompt = c.systemPrompt,
            voiceIdentifier = c.voiceIdentifier,
            remoteVoiceID = c.remoteVoiceID,
            ttsEmotionRaw = c.ttsEmotionRaw,
            ttsSpeed = c.ttsSpeed,
            ttsPitch = c.ttsPitch,
            avatarPath = c.avatarPath,
            chatWallpaperPath = c.chatWallpaperPath,
            personalitySpectrum = c.personalitySpectrum,
            relationshipQuality = c.relationshipQuality,
            offlineThemeColorHex = c.offlineThemeColorHex ?: "",
            // relationshipName 从当前里程碑单独加载（不在实体上），见 VM init。
        )
    }
}

/**
 * Backs [CharacterEditScreen] for both create (no nav arg) and edit (`characterUuid` nav arg).
 * Mirrors iOS `CharacterDetailView.save()`: create inserts the character and — so it surfaces in the
 * chat list — ensures its conversation exists; edit overwrites the form fields in place.
 */
@HiltViewModel
class CharacterEditViewModel @Inject constructor(
    private val characterRepo: CharacterRepository,
    private val conversationRepo: ConversationRepository,
    private val settingsRepo: SettingsRepository,
    private val currencyService: CurrencyService,
    private val economyMaintenance: CharacterEconomyMaintenanceService,
    private val ttsConfigRepo: TtsConfigurationRepository,
    private val previewer: TtsPreviewer,
    private val membershipService: WorldMembershipService,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val editingUuid: String? = savedStateHandle["characterUuid"]
    val isEditing: Boolean = editingUuid != null

    /** 该角色是否启用了专属提示词模块覆盖（仅编辑模式有意义）。 */
    private val _hasModuleOverride = MutableStateFlow(false)
    val hasModuleOverride: StateFlow<Boolean> = _hasModuleOverride.asStateFlow()

    private val _state = MutableStateFlow(CharacterEditState())
    val state: StateFlow<CharacterEditState> = _state.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** The loaded row in edit mode — kept so non-form fields survive a save. */
    private var loaded: CharacterEntity? = null

    init {
        // E1#0（批 B·Fable-5）：进程死亡丢表单根治——整包草稿挂 SavedStateProvider（**惰性**：仅系统真正
        // 保存实例状态时序列化一次，键入零开销；含「挑头像跳系统相册」经典击杀窗）。恢复基线=**编辑中值**
        // 精确还原（裁定账本「DB 原值 vs 编辑中值」分叉：进程死亡对用户应不可感知，故还原死前所见）。
        // 草稿只活在任务实例状态里：用户真正退出表单即随返回栈消亡，无陈旧草稿提示 UX。
        val restoredDraft = savedStateHandle.get<Bundle>(KEY_FORM_DRAFT)
            ?.getString(KEY_FORM_DRAFT_JSON)
            ?.let { CharacterEditDraftCodec.decode(it) }
        if (restoredDraft != null) _state.value = restoredDraft
        savedStateHandle.setSavedStateProvider(KEY_FORM_DRAFT) {
            Bundle().apply { putString(KEY_FORM_DRAFT_JSON, CharacterEditDraftCodec.encode(_state.value)) }
        }
        val uuid = editingUuid
        if (uuid != null) {
            viewModelScope.launch {
                characterRepo.get(uuid)?.let { c ->
                    loaded = c
                    // 当前关系名来自最新里程碑（不在实体上），随实体一并载入表单。
                    // 恢复了死前草稿则不让 DB 载入覆写（草稿已含 relationshipName）。
                    if (restoredDraft == null) {
                        _state.value = CharacterEditState.from(c).copy(
                            relationshipName = characterRepo.currentRelationship(uuid) ?: "",
                        )
                    }
                }
                _hasModuleOverride.value = PromptModuleService.hasCharacterOverride(
                    uuid,
                    settingsRepo.getAppSettings().characterPromptModulesJSON,
                )
            }
        } else if (restoredDraft == null) {
            // 活人感一期 P1b：新建模式（非进程死亡恢复）默认预填示例对话，给模型 few-shot 口吻锚——
            // 空示例 → 模型无样例可学、按训练习惯写小作文。用户可见、可改可删；编辑既有角色绝不走此分支。
            // 保存链路零改（空/非空照旧写 exampleDialogues 列）；预填值随 savedStateProvider 进草稿，进程死亡恢复走 restoredDraft 分支不重复预填。
            _state.value = _state.value.copy(
                exampleDialogues = appContext.getString(R.string.character_example_dialogues_default),
            )
        }
    }

    /**
     * Toggle a character-specific prompt-module override (iOS promptModuleOverrideSection):
     * enabling seeds it from the current global modules; disabling drops back to global.
     */
    fun setModuleOverride(enabled: Boolean) {
        val uuid = editingUuid ?: return
        viewModelScope.launch {
            val s = settingsRepo.getAppSettings()
            val newJson = if (enabled) {
                val global = PromptModuleService.loadGlobalModules(s.promptModulesJSON)
                PromptModuleService.setCharacterModules(uuid, global, s.characterPromptModulesJSON)
            } else {
                PromptModuleService.removeCharacterOverride(uuid, s.characterPromptModulesJSON)
            }
            settingsRepo.setCharacterPromptModulesJSON(newJson)
            _hasModuleOverride.value = enabled
        }
    }

    fun update(transform: (CharacterEditState) -> CharacterEditState) {
        _state.value = transform(_state.value)
    }

    // MARK: - 语音试听 + 系统音色（P10.1c）

    private val _systemVoices = MutableStateFlow<List<SystemVoiceOption>>(emptyList())
    val systemVoices: StateFlow<List<SystemVoiceOption>> = _systemVoices.asStateFlow()
    private val _previewBusy = MutableStateFlow(false)
    val previewBusy: StateFlow<Boolean> = _previewBusy.asStateFlow()
    private val _previewError = MutableStateFlow<String?>(null)
    val previewError: StateFlow<String?> = _previewError.asStateFlow()

    /** Enumerate installed system voices for the per-character picker. */
    fun loadSystemVoices() {
        if (_systemVoices.value.isNotEmpty()) return
        viewModelScope.launch { _systemVoices.value = previewer.systemVoices() }
    }

    /** 试听该角色的声音：用全局 TTS 引擎配置 + 角色音色/情绪/语速/音调合成一句样本播放。 */
    fun preview() {
        viewModelScope.launch {
            _previewBusy.value = true
            _previewError.value = null
            try {
                val s = _state.value
                val config = ttsConfigRepo.getConfiguration()
                _previewError.value = if (config.providerType == TtsProviderType.SYSTEM) {
                    previewer.preview(TtsProviderType.SYSTEM, s.voiceIdentifier, "", null)
                } else {
                    val overrides = if (config.providerType == TtsProviderType.MINIMAX) {
                        TtsService.buildMiniMaxOverridesFromRawValues(
                            emotionRaw = s.ttsEmotionRaw, speed = s.ttsSpeed, pitch = s.ttsPitch, modelName = config.modelName,
                        )
                    } else {
                        null
                    }
                    val remoteConfig = TtsRemoteConfigValues(
                        providerType = config.providerType,
                        providerName = config.providerName,
                        apiKey = ttsConfigRepo.getApiKey(),
                        baseUrl = config.baseURL.ifEmpty { config.providerType.defaultBaseUrl },
                        modelName = config.modelName,
                        responseFormat = config.responseFormat,
                        miniMaxVoiceOverrides = overrides,
                    )
                    previewer.preview(config.providerType, "", s.remoteVoiceID, remoteConfig)
                }
            } finally {
                _previewBusy.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        previewer.stop()
    }

    /**
     * Persist. In create mode returns the (created/ensured) conversation uuid so the caller can open
     * the chat; in edit mode returns null. No-op if the name is blank or a save is already running.
     */
    fun save(onSaved: (conversationUuid: String?) -> Unit) {
        val s = _state.value
        if (!s.canSave || _saving.value) return
        viewModelScope.launch {
            _saving.value = true
            val existing = loaded
            val conversationUuid: String? = if (existing == null) {
                val entity = s.toNewEntity()
                // 创建：insert 内部按关系名播种「初始设定」里程碑（recordRelationship 守卫空名，1:1 iOS）。
                // 成长原型校准（§3.3 入口①）：slidersTouched=滑块动过→圣旨跳过棘轮抬分（保留狗血剧本意图）。
                characterRepo.insert(entity, initialRelationshipName = s.relationshipName.trim(), relationshipSlidersTouched = s.relationshipQuality != RelationshipQuality.INITIAL)
                // W13：新建模式勾了「加入世界」→ 角色落库后调成员服务加入（单事务·静默建世+休眠恢复+join 事件·图纸 §3.3）。
                // 插入之后、onSaved 回调之前执行；若进程死于两步之间 = 角色存在但未加入（编辑页可再开·E6）。
                if (s.joinWorld) membershipService.join(entity.uuid, System.currentTimeMillis())
                // P6.1c：建角色即为其生成通知文案（后台 worker；离线/无配置回退默认文案）。
                NotificationTemplateWorker.enqueueForCharacter(appContext, entity.uuid)
                // P1-43：创建即时推断月薪+入职储蓄（=iOS Actions.swift:219-232 异步 Task）。fire-and-forget
                // 在服务自有 scope——本 VM save 完即导航清场，不可用 viewModelScope。
                economyMaintenance.runForNewCharacter(entity.uuid)
                conversationRepo.getOrCreateForCharacter(entity.uuid, entity.name.trim())
            } else {
                // P12.6 D1c：列级写回「表单可编辑」字段，不再整行 update 把成长/关系/心情/火花/记忆等并发列从开屏
                // 旧快照复活回旧值（D1 同类丢更新；iOS 是逐属性改 live @Model）。applyTo 只做字段变换（trim/parse），
                // 取其结果中的 20 个 profile 列做定向 UPDATE，其余列保持库内最新值不动。
                val edited = s.applyTo(existing)
                characterRepo.updateEditableProfile(
                    uuid = existing.uuid,
                    name = edited.name,
                    avatarPath = edited.avatarPath,
                    systemPrompt = edited.systemPrompt,
                    personalityDescription = edited.personalityDescription,
                    gender = edited.gender,
                    birthday = edited.birthday,
                    ageModeRaw = edited.ageModeRaw,
                    fixedAge = edited.fixedAge,
                    appearanceDescription = edited.appearanceDescription,
                    occupation = edited.occupation,
                    backstory = edited.backstory,
                    speakingStyle = edited.speakingStyle,
                    catchphrases = edited.catchphrases,
                    exampleDialogues = edited.exampleDialogues,
                    initialInterests = edited.initialInterests,
                    voiceIdentifier = edited.voiceIdentifier,
                    remoteVoiceID = edited.remoteVoiceID,
                    ttsEmotionRaw = edited.ttsEmotionRaw,
                    ttsSpeed = edited.ttsSpeed,
                    ttsPitch = edited.ttsPitch,
                    offlineThemeColorHex = edited.offlineThemeColorHex,
                    chatWallpaperPath = edited.chatWallpaperPath,
                )
                // 换/移除壁纸：DB 落定后删旧壁纸文件（壁纸大·防孤儿；仅删确被替换的旧路径·post-persist 安全，
                // 取消编辑则 existing 不变、不删）。与头像「不清旧」分叉=壁纸体积大值得清。
                val oldWallpaper = existing.chatWallpaperPath
                if (!oldWallpaper.isNullOrEmpty() && oldWallpaper != edited.chatWallpaperPath) {
                    WallpaperStore.delete(oldWallpaper)
                }
                // 关系变更（14.1e-2）：关系名变了则追加一条「关系调整」里程碑（recordRelationship 内部守卫空/未变，
                // 1:1 iOS save() 不覆盖旧记录）；返回 true=确有追加，并入人设变更触发心意文案包失效。
                // 成长原型校准（§3.3 入口①）：slidersTouched=关系滑块变了→圣旨跳过棘轮/回拉（保留手调低分）。
                val relationshipChanged = characterRepo.recordRelationship(existing.uuid, s.relationshipName, reason = "关系调整", relationshipSlidersTouched = s.relationshipQuality != existing.relationshipQuality)
                // iOS save() .edit 的两个额外副作用（核对 CharacterDetailView+Actions.swift:114-189）：
                val effects = characterEditSideEffects(existing, edited)
                // ① 人设字段或关系变更 → 失效心意反馈文案包（列级写 generatedAt=null，不复活并发分析列；D1c 同款）。
                //    下次送礼 AffinitySenseService 检测到 generatedAt=null 即按新人设/新关系重生成一包。
                if (effects.personaChanged || relationshipChanged) {
                    characterRepo.updateAffinitySenseGeneratedAt(existing.uuid, null)
                }
                // ② 职业变更 → 清 salaryInferred（iOS `character.wallet?.salaryInferred = false`），下次经济维护
                //    回前台按新职业重推月薪；无钱包则不动。（iOS 还清 UserDefaults「salaryZeroAlertShown」弹窗 flag——
                //    该弹窗在安卓 wallet 卡片未移植，无对应 flag 可清。）
                if (effects.occupationChanged) {
                    currencyService.clearSalaryInferred(existing.uuid)
                }
                // 14.1e ③ 成长维度（8+8 滑块）：仅在用户实际改了维度时才列级写回，避免覆盖编辑期间后台成长分析
                //    刚写入的新值（updateEditableProfile 契约不碰成长列，故这里独立写）。
                if (s.personalitySpectrum != existing.personalitySpectrum || s.relationshipQuality != existing.relationshipQuality) {
                    characterRepo.updateGrowthDimensions(
                        existing.uuid,
                        GrowthJson.encode(s.personalitySpectrum),
                        GrowthJson.encode(s.relationshipQuality),
                    )
                }
                null
            }
            _saving.value = false
            onSaved(conversationUuid)
        }
    }

    private companion object {
        /** E1#0 草稿在 SavedStateHandle 的键（值=Bundle{[KEY_FORM_DRAFT_JSON]→JSON 串}）。 */
        const val KEY_FORM_DRAFT = "characterEditFormDraft"
        const val KEY_FORM_DRAFT_JSON = "json"
    }
}

/**
 * E1#0 进程死亡草稿编解码（纯函数·单测锁往返无损）：[CharacterEditState] ⇄ JSON。
 * ignoreUnknownKeys + 字段默认值 → 旧草稿对未来新增/删除字段双向兼容；解码失败回 null（丢草稿优于崩溃）。
 */
internal object CharacterEditDraftCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(state: CharacterEditState): String =
        runCatching { json.encodeToString(CharacterEditState.serializer(), state) }.getOrDefault("")

    fun decode(raw: String): CharacterEditState? =
        runCatching { json.decodeFromString(CharacterEditState.serializer(), raw) }.getOrNull()
}

/** 编辑保存两个 iOS 副作用的判定结果（见 [characterEditSideEffects]）。 */
internal data class CharacterEditSideEffects(
    val personaChanged: Boolean,
    val occupationChanged: Boolean,
)

/**
 * 比较编辑前后的角色，得出 iOS `CharacterDetailView.save()` .edit 分支的两个副作用是否触发（纯函数，便于单测）：
 * - [CharacterEditSideEffects.personaChanged]：systemPrompt / 性格 / 说话风格任一变化 → 失效心意反馈文案包。
 *   关系变更（14.1e-2 起编辑表单支持）在 [CharacterEditViewModel.save] 里经 recordRelationship 返回值并入该失效判断。
 * - [CharacterEditSideEffects.occupationChanged]：occupation 变化 → 清 salaryInferred 让下次按新职业重推月薪。
 */
internal fun characterEditSideEffects(old: CharacterEntity, new: CharacterEntity): CharacterEditSideEffects =
    CharacterEditSideEffects(
        personaChanged = old.systemPrompt != new.systemPrompt ||
            old.personalityDescription != new.personalityDescription ||
            old.speakingStyle != new.speakingStyle,
        occupationChanged = old.occupation != new.occupation,
    )
