package com.situ.aichat.ui.story

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.story.StoryCraftSections
import com.situ.aichat.story.StoryEditableField
import com.situ.aichat.story.StoryFieldKind
import com.situ.aichat.story.StoryGlobalCraftValues
import com.situ.aichat.story.StoryWritingTechniques
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 三态段的三个位置（二态字段与全局变体恒 [CUSTOM]，不显示段）。 */
enum class StoryFieldMode { FOLLOW, CUSTOM, OFF }

/**
 * 统一编辑页的一屏状态（全案所有文本设定共用一个长相·提案 §11）。
 *
 * [field] 为 null = **三个全局变体之一**（忌口 / 场面节拍 / 口味画像·落 DataStore，不属于任何一本书）：
 * 无三态段、三态原样存；有出厂默认的给「恢复默认」（口味画像没有 → 不给）。
 */
data class StoryFieldEditorState(
    val field: StoryEditableField?,
    val bookTitle: String,
    val mode: StoryFieldMode,
    val text: String,
    /** 「跟随全局」态下实际会注入的文本（只读预览）；null = 这一态什么都不注入。 */
    val inheritedText: String?,
    /** 「恢复默认」的来源全文；null = 本字段没有出厂默认，不给这个按钮。 */
    val factoryDefault: String?,
    val dirty: Boolean,
) {
    // 注意：自定义 getter 里裸写 `field` 会被解析成 backing-field 关键字，必须 `this.field`。
    val showModeSegment: Boolean get() = this.field?.kind == StoryFieldKind.CRAFT_TRI
    val isArchive: Boolean get() = this.field?.kind == StoryFieldKind.ARCHIVE
    val maxChars: Int? get() = this.field?.maxChars
    val showPresetChips: Boolean get() = this.field?.hasPresetChips == true
    /** 正文区是否可编辑（「跟随全局」态只看不改）。 */
    val editable: Boolean get() = mode == StoryFieldMode.CUSTOM
}

/**
 * 统一编辑页 VM（故事二期卷二·图纸 §3.3；卷四扩为三个全局哨兵）——**一个 VM 服务 19 种入口**：
 * 16 个本书字段 + 全局忌口 / 全局场面节拍 / 全局口味画像三个变体。
 *
 * 三态语义、出厂默认、继承层文本一律读 [StoryEditableField] 注册表单源，这里只做「装载 → 草稿 → 落库分派」。
 * 写路严格照既有 copy-merge 范式（fresh 读 customPromptsJson → copy 只换本字段 → hasAnyValue 判空清 JSON），
 * **绝不新构造 CustomStoryPrompts**（2026-07-30 丢字段真 bug 的根治范式）；档案族走卷二新增的单列定向写。
 *
 * [save] 设计为 suspend，由屏幕在其协程内 await 后再导航返回（避免 viewModelScope 随 pop 取消截断写入·
 * 同 `StorySettingsViewModel`）；保存失败**不返回**。
 */
@HiltViewModel
class StoryFieldEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: StoryRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val storyId: String = savedStateHandle.get<String>("storyId").orEmpty()
    private val fieldKey: String = savedStateHandle.get<String>("fieldKey").orEmpty()

    /** null = 三个全局变体之一或非法路由参数（后者由 [invalid] 区分，屏幕直接退出）。 */
    private val field: StoryEditableField? = StoryEditableField.fromKey(fieldKey)

    /** 路由参数既不是已知字段、也不是全局哨兵 → 屏幕安全退出，不渲染半截页。 */
    val invalid: Boolean = field == null && fieldKey !in StoryEditableField.GLOBAL_KEYS

    /**
     * 顶栏标题词条。构造即可知（只看路由键），**装载中也能正确显示**——不塞进 [state] 是为了
     * 首帧就有正确标题，而不是先闪一个别的字段名。忌口沿用现值，两个新全局哨兵各自的词条见 §3.3。
     */
    @StringRes
    val titleRes: Int = field?.titleRes ?: when (fieldKey) {
        StoryEditableField.GLOBAL_SCENE_BEATS_KEY -> R.string.story_global_beats_title
        StoryEditableField.GLOBAL_TASTE_PROFILE_KEY -> R.string.story_global_taste_title
        else -> R.string.story_banned_row
    }

    private val _state = MutableStateFlow<StoryFieldEditorState?>(null)
    /** null = 装载中。 */
    val state: StateFlow<StoryFieldEditorState?> = _state.asStateFlow()

    private val _saving = MutableStateFlow(false)
    /** 保存进行中：保存钮禁用（防重入·E3）。 */
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var initialMode = StoryFieldMode.CUSTOM
    private var initialText = ""

    /**
     * 草稿是否已经有过内容（首次切到「本书自定义」时灌继承层文本用）。
     * 一旦灌过或用户敲过字就恒真——CUSTOM→FOLLOW→CUSTOM 来回切不会把用户删空的草稿又填回来（E5）。
     */
    private var draftSeeded = false

    init {
        if (!invalid) viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val story = repository.getStory(storyId)
        val settings = settingsRepository.appSettings.first()
        val globals = StoryGlobalCraftValues(
            sceneBeats = settings.storySceneBeats,
            tasteProfile = settings.storyTasteProfile,
            bannedExpressions = settings.storyBannedExpressions,
        )
        val f = field
        if (f == null) {
            // 三个全局变体：从未设置 → 灌出厂默认全文，一进来就能直接删改（照现 sheet 口径）；
            // 口味画像没有出厂默认 → 空白起步且不给「恢复默认」钮。
            val default = globalFactoryDefault()
            initialText = globalStoredValue(settings) ?: default.orEmpty()
            initialMode = StoryFieldMode.CUSTOM
            draftSeeded = true
            _state.value = StoryFieldEditorState(
                field = null,
                bookTitle = "",
                mode = StoryFieldMode.CUSTOM,
                text = initialText,
                inheritedText = null,
                factoryDefault = default,
                dirty = false,
            )
            return
        }
        if (story == null) return
        val stored = f.currentValue(story)
        initialMode = when {
            f.kind != StoryFieldKind.CRAFT_TRI -> StoryFieldMode.CUSTOM
            stored == null -> StoryFieldMode.FOLLOW
            stored.isBlank() -> StoryFieldMode.OFF
            else -> StoryFieldMode.CUSTOM
        }
        initialText = if (initialMode == StoryFieldMode.CUSTOM) stored.orEmpty() else ""
        draftSeeded = initialText.isNotEmpty()
        _state.value = StoryFieldEditorState(
            field = f,
            bookTitle = story.title,
            mode = initialMode,
            text = initialText,
            inheritedText = f.inheritedText(globals),
            factoryDefault = f.factoryDefault(story),
            dirty = false,
        )
    }

    /**
     * 改正文。上限字段（节奏偏好 300）**拒收会越界的这一次输入**（原值不动），绝不静默截掉一半——
     * 计数行同时变警示色，用户看得见自己撞到顶了（图纸 §4.4 / E6）。
     */
    fun setText(value: String) {
        val current = _state.value ?: return
        val max = current.maxChars
        if (max != null && value.length > max) return
        draftSeeded = draftSeeded || value.isNotEmpty()
        _state.value = current.copy(text = value, dirty = dirtyOf(value, current.mode))
    }

    /**
     * 切三态段。首次进「本书自定义」把继承层的有效文本灌进草稿（微信式「一进来就能删改」）；
     * 离开自定义时草稿留在内存，切回来不丢（E5·仅本页生命周期，进程死亡不恢复·J7）。
     */
    fun setMode(mode: StoryFieldMode) {
        val current = _state.value ?: return
        var text = current.text
        if (mode == StoryFieldMode.CUSTOM && !draftSeeded) {
            text = current.inheritedText.orEmpty()
            draftSeeded = text.isNotEmpty()
        }
        _state.value = current.copy(mode = mode, text = text, dirty = dirtyOf(text, mode))
    }

    /** 「恢复默认」确认后：灌出厂默认全文并置本书自定义态（还没落库，仍要点保存）。 */
    fun restoreDefault() {
        val current = _state.value ?: return
        val default = current.factoryDefault ?: return
        draftSeeded = true
        _state.value = current.copy(text = default, mode = StoryFieldMode.CUSTOM, dirty = dirtyOf(default, StoryFieldMode.CUSTOM))
    }

    /** 三档身份预设 chips：把预设全文填进草稿（不落库·用户可继续改）。 */
    fun applyPreset(text: String) {
        val current = _state.value ?: return
        draftSeeded = true
        _state.value = current.copy(text = text, mode = StoryFieldMode.CUSTOM, dirty = dirtyOf(text, StoryFieldMode.CUSTOM))
    }

    private fun dirtyOf(text: String, mode: StoryFieldMode) = text != initialText || mode != initialMode

    fun dismissError() { _error.value = null }

    /**
     * 落库。@return 是否成功（失败置错误并返回 false，屏幕据此**不返回**，= 既有「保存失败不关闭」口径）。
     * 保存期间 [saving] 为真，重复点击直接短路（防重入·E3）。
     */
    suspend fun save(): Boolean {
        val current = _state.value ?: return false
        if (_saving.value) return false
        _saving.value = true
        return runCatching {
            val f = current.field
            when {
                f == null -> persistGlobal(current.text) // 三态原样存，绝不 trim
                f.kind == StoryFieldKind.ARCHIVE -> persistArchive(f, current.text.ifBlank { null })
                else -> persistCraft(f, craftValueToStore(f, current.mode, current.text))
            }
            Log.i(TAG, "保存故事文本设定 story=$storyId field=$fieldKey mode=${current.mode} len=${current.text.length}")
        }.onFailure { _error.value = it.message; Log.e(TAG, "保存故事文本设定失败 field=$fieldKey", it) }
            .also { _saving.value = false }
            .isSuccess
    }

    // ── 三个全局哨兵的分派（装载 / 出厂默认 / 落库各一处·键的判定单源 = [StoryEditableField.GLOBAL_KEYS]）──

    /** 该全局项在 `AppSettings` 里的原值（三态照读，绝不先判空）。 */
    private fun globalStoredValue(settings: AppSettings): String? = when (fieldKey) {
        StoryEditableField.GLOBAL_SCENE_BEATS_KEY -> settings.storySceneBeats
        StoryEditableField.GLOBAL_TASTE_PROFILE_KEY -> settings.storyTasteProfile
        else -> settings.storyBannedExpressions
    }

    /** 该全局项的出厂默认全文；null = 没有出厂默认（口味画像），不给「恢复默认」钮。 */
    private fun globalFactoryDefault(): String? = when (fieldKey) {
        StoryEditableField.GLOBAL_SCENE_BEATS_KEY -> StoryCraftSections.SCENE_BEATS_DEFAULT
        StoryEditableField.GLOBAL_TASTE_PROFILE_KEY -> null
        else -> StoryWritingTechniques.bannedExpressionsBaseline
    }

    /** 全局项落库：三态**原样存**（不 trim、不判空回退），null=移键的语义由仓库侧承担。 */
    private suspend fun persistGlobal(text: String) = when (fieldKey) {
        StoryEditableField.GLOBAL_SCENE_BEATS_KEY -> settingsRepository.setStorySceneBeats(text)
        StoryEditableField.GLOBAL_TASTE_PROFILE_KEY -> settingsRepository.setStoryTasteProfile(text)
        else -> settingsRepository.setStoryBannedExpressions(text)
    }

    /**
     * 三态 → 落库值：跟随全局 = null（键不落 JSON）／本书关闭 = `""`／自定义 = trim 后的文本。
     * 二态字段没有「关闭」，空白等于回出厂默认，故归 null；节奏偏好走既有 [CustomStoryPrompts.normalizedPacing] 单源。
     */
    private fun craftValueToStore(field: StoryEditableField, mode: StoryFieldMode, text: String): String? = when {
        field.kind == StoryFieldKind.CRAFT_TRI && mode == StoryFieldMode.FOLLOW -> null
        field.kind == StoryFieldKind.CRAFT_TRI && mode == StoryFieldMode.OFF -> ""
        field == StoryEditableField.PACING -> CustomStoryPrompts.normalizedPacing(text)
        field.kind == StoryFieldKind.CRAFT_TRI -> text.trim() // 自定义态清空 = 本书关闭，与 OFF 同义
        else -> text.trim().ifEmpty { null }
    }

    /** 写法族落库：**copy-merge**（fresh 读 → copy 只换本字段 → hasAnyValue 判空清 JSON），绝不新构造对象。 */
    private suspend fun persistCraft(field: StoryEditableField, value: String?) {
        val current = CustomStoryPrompts.decode(repository.getStory(storyId)?.customPromptsJson) ?: CustomStoryPrompts()
        val merged = when (field) {
            StoryEditableField.WRITER_IDENTITY -> current.copy(writerIdentity = value)
            StoryEditableField.GENRE_TECHNIQUES -> current.copy(genreTechniques = value)
            StoryEditableField.WRITING_RULES -> current.copy(writingRules = value)
            StoryEditableField.PACING -> current.copy(pacingPreference = value)
            StoryEditableField.SCENE_BEATS -> current.copy(sceneBeats = value)
            StoryEditableField.TASTE_PROFILE -> current.copy(tasteProfile = value)
            StoryEditableField.BANNED_OVERRIDE -> current.copy(bannedExpressions = value)
            else -> return
        }
        repository.updateCustomPrompts(storyId, if (merged.hasAnyValue) CustomStoryPrompts.encode(merged) else null)
    }

    /** 档案族落库：卷二新增的单列定向写（空白→null·与生成侧并发时 last-write-wins·J3）。 */
    private suspend fun persistArchive(field: StoryEditableField, value: String?) {
        val now = System.currentTimeMillis()
        when (field) {
            StoryEditableField.OUTLINE -> repository.updateStoryOutlineUserEdit(storyId, value, now)
            StoryEditableField.CURRENT_ARC -> repository.updateCurrentArcUserEdit(storyId, value, now)
            StoryEditableField.INTIMACY -> repository.updateIntimacyLedger(storyId, value, now)
            StoryEditableField.SCENE_LEDGER -> repository.updateSceneLedger(storyId, value, now)
            StoryEditableField.SCENE_STATE -> repository.updateSceneState(storyId, value, now)
            StoryEditableField.CHARACTER_STATES -> repository.updateCharacterStates(storyId, value, now)
            StoryEditableField.OPEN_THREADS -> repository.updateOpenThreads(storyId, value, now)
            StoryEditableField.SUMMARY -> repository.updateStorySummaryUserEdit(storyId, value, now)
            StoryEditableField.BIBLE -> repository.updateStoryBible(storyId, value)
            else -> return
        }
    }

    private companion object {
        const val TAG = "StoryFieldEditor"
    }
}
