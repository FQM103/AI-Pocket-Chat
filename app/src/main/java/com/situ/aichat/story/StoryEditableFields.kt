package com.situ.aichat.story

import androidx.annotation.StringRes
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts

/**
 * 书页「统一编辑页」的**字段注册表单源**（故事二期卷二·提案 §11 / 图纸 §3.2）。
 *
 * 一处登记全部 16 个可编辑文本字段：长相分类（[StoryFieldKind]）、标题词条、字数上限、出厂默认来源、
 * 当前值取法、继承层文本、设定 Tab 行右侧的值标推导。**纯函数、零 Android 依赖**（只引 `@StringRes` 常量），
 * 编辑页 / 设定 Tab / 档案 Tab 三处只许读这里，绝不各自再写一份判断。
 *
 * 「全局文字忌口」是编辑页的**变体**而非第 16 个字段（它落 DataStore、不属于任何一本书），
 * 走 [GLOBAL_BANNED_KEY] 这个哨兵路由键，由编辑页 VM 单独分派。
 */
enum class StoryFieldKind {
    /** 三态（跟随全局 / 本书自定义 / 本书关闭）：本书 › 全局 › 出厂默认的覆盖链。 */
    CRAFT_TRI,

    /** 二态（出厂默认 / 已自定义）：只有本书一层，没有「关闭」这一态。 */
    CRAFT_PLAIN,

    /** 叙事状态账本：AI 每章续记、用户可改，无三态段。 */
    ARCHIVE,
}

/** 值标的三种呈现语气（色与装饰由 UI 层的 `HubValueLabel` 映射·图纸 §4.3）。 */
enum class StoryFieldValueStyle {
    /** 中性（出厂默认 / 跟随全局 / 全局已关 / 未设置）。 */
    NEUTRAL,

    /** 用户已经动过手（陶土色 Medium）。 */
    CUSTOM,

    /** 本书主动关掉（次级色 + 删除线）。 */
    OFF,
}

/**
 * 设定 Tab 行右侧的值标。[labelRes] 为 null 时显示 [echo]（仅节奏偏好回显原文摘要）。
 */
data class StoryFieldValueLabel(
    @StringRes val labelRes: Int?,
    val style: StoryFieldValueStyle,
    val echo: String? = null,
)

/** 三项全局创作设定的当前值（`AppSettings` 原值·三态语义照存，调用方不许先判空）。 */
data class StoryGlobalCraftValues(
    val sceneBeats: String? = null,
    val tasteProfile: String? = null,
    val bannedExpressions: String? = null,
)

/**
 * 可进统一编辑页的 16 个字段。[key] 是路由参数（落 URL，改动等于旧链接失效，勿改字面）。
 *
 * @param offLabelRes 三态字段「本书关闭」态的值标词条（忌口用「本书已关」，其余用「已关闭」）
 */
enum class StoryEditableField(
    val key: String,
    val kind: StoryFieldKind,
    @StringRes val titleRes: Int,
    /** 输入端硬拦上限（超出拒收，绝不静默截）；null = 无上限。 */
    val maxChars: Int? = null,
    /** 正文区上方是否给三档身份预设 chips（仅写作身份·[PersonaPresets]）。 */
    val hasPresetChips: Boolean = false,
    @StringRes val offLabelRes: Int = R.string.story_hub_value_off,
) {
    // ── 写法组（设定 Tab·顺序 = 图纸 §4.3）──
    WRITER_IDENTITY("writerIdentity", StoryFieldKind.CRAFT_PLAIN, R.string.story_field_writer_name, hasPresetChips = true),
    GENRE_TECHNIQUES("genreTechniques", StoryFieldKind.CRAFT_PLAIN, R.string.story_field_genre_tech_name),
    WRITING_RULES("writingRules", StoryFieldKind.CRAFT_PLAIN, R.string.story_field_rules_name),
    BANNED_OVERRIDE(
        "bannedExpressions",
        StoryFieldKind.CRAFT_TRI,
        R.string.story_field_banned_name,
        offLabelRes = R.string.story_hub_value_book_off,
    ),
    PACING("pacingPreference", StoryFieldKind.CRAFT_PLAIN, R.string.story_field_pacing_name, maxChars = CustomStoryPrompts.PACING_MAX_CHARS),
    SCENE_BEATS("sceneBeats", StoryFieldKind.CRAFT_TRI, R.string.story_field_beats_name),
    TASTE_PROFILE("tasteProfile", StoryFieldKind.CRAFT_TRI, R.string.story_field_taste_name),

    // ── 档案组（档案 Tab·顺序 = 图纸 §4.2 的 ①③④⑤⑥⑦⑧）──
    OUTLINE("storyOutline", StoryFieldKind.ARCHIVE, R.string.story_field_outline_name),

    /** 卡①第二行（R1 复核 D-9 修复·旧设定屏「当前剧情弧线」编辑口回归）：注入端=前情回顾「## 当前剧情弧线」段与弧线大纲「上一个弧线概述」段。 */
    CURRENT_ARC("currentArc", StoryFieldKind.ARCHIVE, R.string.story_settings_arc_title),
    INTIMACY("intimacyLedger", StoryFieldKind.ARCHIVE, R.string.story_field_intimacy_name),
    SCENE_LEDGER("sceneLedger", StoryFieldKind.ARCHIVE, R.string.story_field_scene_ledger_name),
    SCENE_STATE("sceneState", StoryFieldKind.ARCHIVE, R.string.story_field_scene_state_name),
    CHARACTER_STATES("characterStates", StoryFieldKind.ARCHIVE, R.string.story_field_states_name),
    OPEN_THREADS("openThreads", StoryFieldKind.ARCHIVE, R.string.story_field_threads_name),
    SUMMARY("storySummary", StoryFieldKind.ARCHIVE, R.string.story_field_summary_name),
    BIBLE("storyBible", StoryFieldKind.ARCHIVE, R.string.story_field_bible_name),
    ;

    /**
     * 本字段在这本书里的当前存储值（未设置 = null；`""`/空白对三态字段 = 本书关闭）。
     *
     * @param prompts 已解好的 [story] 自定义提示词（调用方一屏里要问很多字段时传进来，省掉逐字段重复解 JSON）；
     *   传 null = 本函数自己解，与不传这个参数的老调用点**逐字节等价**。
     */
    fun currentValue(story: StoryEntity, prompts: CustomStoryPrompts? = null): String? = when (this) {
        OUTLINE -> story.storyOutline
        CURRENT_ARC -> story.currentArc
        INTIMACY -> story.intimacyLedger
        SCENE_LEDGER -> story.sceneLedger
        SCENE_STATE -> story.sceneState
        CHARACTER_STATES -> story.characterStates
        OPEN_THREADS -> story.openThreads
        SUMMARY -> story.storySummary
        BIBLE -> story.storyBible
        else -> (prompts ?: CustomStoryPrompts.decode(story.customPromptsJson))?.let { craftValue(it) }
    }

    private fun craftValue(prompts: CustomStoryPrompts): String? = when (this) {
        WRITER_IDENTITY -> prompts.writerIdentity
        GENRE_TECHNIQUES -> prompts.genreTechniques
        WRITING_RULES -> prompts.writingRules
        PACING -> prompts.pacingPreference
        SCENE_BEATS -> prompts.sceneBeats
        TASTE_PROFILE -> prompts.tasteProfile
        BANNED_OVERRIDE -> prompts.bannedExpressions
        else -> null
    }

    /**
     * 出厂默认全文（「恢复默认」按钮的来源）；null = 本字段没有出厂默认，不给这个按钮。
     * 身份/技法随本书的文风与题材变，故要传 [story]。
     */
    fun factoryDefault(story: StoryEntity): String? = when (this) {
        WRITER_IDENTITY -> StoryWritingTechniques.writerIdentity(story.writingStyle)
        GENRE_TECHNIQUES -> StoryWritingTechniques.genreTechniques(story.genre)
        WRITING_RULES -> StoryWritingTechniques.writingPrinciples
        BANNED_OVERRIDE -> StoryWritingTechniques.bannedExpressionsBaseline
        SCENE_BEATS -> StoryCraftSections.SCENE_BEATS_DEFAULT
        else -> null
    }

    /** 该三态字段的全局层原值（非三态字段恒 null）。 */
    fun globalValue(globals: StoryGlobalCraftValues): String? = when (this) {
        SCENE_BEATS -> globals.sceneBeats
        TASTE_PROFILE -> globals.tasteProfile
        BANNED_OVERRIDE -> globals.bannedExpressions
        else -> null
    }

    /**
     * 「跟随全局」态下**实际会注入**的文本（编辑页的只读预览）——一律经既有三态取值单源算，
     * 传一本空书即代表「本书这一层没覆盖」，绝不在这里另写一遍优先级。null = 这一态什么都不注入。
     */
    fun inheritedText(globals: StoryGlobalCraftValues): String? = when (this) {
        SCENE_BEATS -> StoryCraftSections.resolvedSceneBeats(EMPTY_STORY, globals.sceneBeats)
        TASTE_PROFILE -> StoryCraftSections.resolvedTasteProfile(EMPTY_STORY, globals.tasteProfile)
        BANNED_OVERRIDE -> StoryPromptSections.resolvedBannedExpressions(EMPTY_STORY, globals.bannedExpressions)
        else -> null
    }

    /**
     * 设定 Tab 行右侧的值标（图纸 §3.2 推导表·**唯一实现点**）。
     * 三态字段走本书 → 全局 → 出厂默认三分；二态字段只看有没有填；节奏偏好回显原文摘要。
     *
     * @param prompts 口径同 [currentValue]：设定 Tab 一次解好七行共用，传 null 与老调用点逐字节等价。
     */
    fun valueLabel(
        story: StoryEntity,
        globals: StoryGlobalCraftValues,
        prompts: CustomStoryPrompts? = null,
    ): StoryFieldValueLabel {
        val current = currentValue(story, prompts)
        if (kind == StoryFieldKind.CRAFT_TRI) {
            if (current != null) {
                return if (current.isBlank()) {
                    StoryFieldValueLabel(offLabelRes, StoryFieldValueStyle.OFF)
                } else {
                    StoryFieldValueLabel(R.string.story_hub_value_custom, StoryFieldValueStyle.CUSTOM)
                }
            }
            val global = globalValue(globals)
            val hasDefault = factoryDefault(story) != null
            return when {
                global == null -> neutral(if (hasDefault) R.string.story_hub_value_default else R.string.story_hub_value_unset)
                global.isBlank() -> neutral(if (hasDefault) R.string.story_hub_value_global_off else R.string.story_hub_value_unset)
                else -> neutral(R.string.story_hub_value_follow)
            }
        }
        if (this == PACING) {
            return if (current.isNullOrBlank()) {
                neutral(R.string.story_hub_value_unset)
            } else {
                StoryFieldValueLabel(labelRes = null, style = StoryFieldValueStyle.CUSTOM, echo = flattenEcho(current))
            }
        }
        if (current.isNullOrBlank()) {
            return neutral(if (factoryDefault(story) != null) R.string.story_hub_value_default else R.string.story_hub_value_unset)
        }
        return StoryFieldValueLabel(R.string.story_hub_value_custom, StoryFieldValueStyle.CUSTOM)
    }

    private fun neutral(@StringRes res: Int) = StoryFieldValueLabel(res, StoryFieldValueStyle.NEUTRAL)

    companion object {
        /** 路由参数反查；查无（老链接 / 脏参数）→ null，由屏幕安全退出。 */
        fun fromKey(key: String?): StoryEditableField? = entries.firstOrNull { it.key == key }

        /**
         * 「全局文字忌口」编辑页的哨兵路由键（不是本书字段·落 DataStore·无三态段、带恢复默认）。
         * 与任何 [StoryEditableField.key] 都不相同，[fromKey] 对它恒返回 null。
         */
        const val GLOBAL_BANNED_KEY = "globalBannedExpressions"

        /** 「全局场面节拍」编辑页的哨兵路由键（口径同 [GLOBAL_BANNED_KEY]·出厂默认 = `SCENE_BEATS_DEFAULT`）。 */
        const val GLOBAL_SCENE_BEATS_KEY = "globalSceneBeats"

        /** 「全局口味画像」编辑页的哨兵路由键（口径同 [GLOBAL_BANNED_KEY]，但**无出厂默认** → 不给恢复默认钮）。 */
        const val GLOBAL_TASTE_PROFILE_KEY = "globalTasteProfile"

        /**
         * 三个全局哨兵键的集合——编辑页 VM 的 `invalid` 判定与装载/保存分派**共用这一处**，
         * 别处不许再散写字面量（卷四 §9-④ 机制锁）。
         */
        val GLOBAL_KEYS = setOf(GLOBAL_BANNED_KEY, GLOBAL_SCENE_BEATS_KEY, GLOBAL_TASTE_PROFILE_KEY)

        /** 行尾回显的截断长度（与创作设定行的既有惯例同一口径）。 */
        const val ECHO_CHARS = 12

        /** 一本「什么都没覆盖」的空书：只用来把三态取值单源钉在「本书这一层没值」的分支上。 */
        private val EMPTY_STORY = StoryEntity()

        /** 行尾值摘要：换行折成空格，超 [maxChars] 字补省略号。 */
        fun flattenEcho(text: String, maxChars: Int = ECHO_CHARS): String {
            val flat = text.trim().map { if (it == '\n' || it == '\r') ' ' else it }.joinToString("")
            return if (flat.length > maxChars) flat.take(maxChars) + "…" else flat
        }
    }
}

/**
 * 全局创作设定行右侧的值标（卷四 §3.2·App 设置「故事创作」子屏专用）。
 *
 * 与 [StoryEditableField.valueLabel] 有意**不复用**：那是「本书 › 全局 › 出厂默认」两层推导，
 * 全局屏只有一层（全局值本身），推错层比多写一个三分支函数危险得多。
 *
 * @param value 该项在 `AppSettings` 里的原值（null=从未设置 / `""`=用户清空 / 文本=自定义）
 * @param hasFactoryDefault 该项有没有出厂默认（忌口与场面节拍有；口味画像没有）
 */
@StringRes
fun globalValueLabel(value: String?, hasFactoryDefault: Boolean): Int = when {
    value == null -> if (hasFactoryDefault) R.string.story_hub_value_default else R.string.story_global_value_unset
    value.isBlank() -> if (hasFactoryDefault) R.string.story_hub_value_off else R.string.story_global_value_unset
    else -> if (hasFactoryDefault) R.string.story_hub_value_custom else R.string.story_global_value_set
}

/**
 * 写作身份三档预设全文（提案 §5.5 物料 M·**逐字锁定**，改动须回提案重新过审）。
 * 只在写作身份编辑页作 chips 代填用——点一下把全文灌进草稿，用户可继续改，**不落库**。
 */
object PersonaPresets {

    /** 【明快直给】 */
    const val DIRECT: String = "你是一位叙事明快的连载小说资深作者。笔下场景具体直接、细节扎实，不绕弯子、不故弄玄虚；" +
        "用词干净利落，靠细节与人物的反应堆出真实感，而不是形容词堆砌。情节推进张弛有度，始终保持代入感。"

    /** 【文艺含蓄】 */
    const val LITERARY: String = "你是一位文笔细腻的作家。写关键场景重氛围与情绪流动，多用暗示、留白与感官通感，" +
        "点到即止；靠张力与心理描写让读者意会，画面朦胧但温度十足。"

    /** 【张弛有度】 */
    const val MIXED: String = "你是一位老练的连载小说作者。场景与剧情并重：该细写时细写写透，该收笔时干脆利落转回主线；" +
        "关键场景具体不含糊，但始终服务于人物与关系的推进，让读者既满足又追剧情。"

    /** chips 的展示序（标签词条 → 全文）。 */
    val all: List<Pair<Int, String>> = listOf(
        R.string.story_field_preset_direct to DIRECT,
        R.string.story_field_preset_literary to LITERARY,
        R.string.story_field_preset_mixed to MIXED,
    )
}
