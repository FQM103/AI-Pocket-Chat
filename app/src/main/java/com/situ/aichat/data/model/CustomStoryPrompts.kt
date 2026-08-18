package com.situ.aichat.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 自定义故事提示词（1:1 iOS `Models/Story.swift` `CustomStoryPrompts` :4-48），
 * JSON 编解码后存入 [com.situ.aichat.data.local.entity.StoryEntity.customPromptsJson]。
 *
 * 前三个「写作口径」字段仅自定义类型故事（`isCustomGenre`）在创建时写入；预设类型（修仙/都市等）留 null，
 * 运行时走 `StoryGenerationPromptBuilder` 的预设默认（见 [composeForCreation]）。
 * [pacingPreference]（卷三 V2）**不受题材限制**：任何故事都可在创建高级表单或故事设置页填写，
 * 故预设题材故事也可能持有一份仅含该字段的 JSON（读端本就无题材门槛）。
 *
 * 序列化用 `encodeDefaults=false`（与 RedPacketData 等一致）：null 字段省略 = iOS Swift Codable
 * 对 nil Optional 跳过 encode（老数据 decode 兼容）。
 */
@Serializable
data class CustomStoryPrompts(
    /** 类型核心技法（如「【修仙核心技法】- 修炼体系要分层…」）。 */
    val genreTechniques: String? = null,
    /** 写作身份（如「你是一位修仙小说大师…」）；null 时用文风选择器的默认身份。 */
    val writerIdentity: String? = null,
    /** 通用写作规则；null 时用默认写作铁律。 */
    val writingRules: String? = null,
    /**
     * 节奏偏好（卷三 V2·安卓新增第四字段）：用户一小段（上限 [PACING_MAX_CHARS] 字），如「慢热，多写日常」。
     * null/空 = 不注入，创作与弧线 prompt 逐字不变；非空时经
     * [com.situ.aichat.story.StoryPromptSections.pacingPreferenceLine] 注入两锚。
     * 老 JSON 无此键 → decode 得 null（`ignoreUnknownKeys` 双向兼容·零 DB 迁移）。
     */
    val pacingPreference: String? = null,
    /**
     * 本故事的文字忌口（2026-07-30·安卓新增第五字段）。**真三态**（故事二期卷二 J1 起，口径与 [sceneBeats] 一致）：
     * `null` = 跟随全局；`""`/纯空白 = **本书关掉忌口段**（不再落到全局或内置默认）；非空文本 = 本故事覆盖。
     * 三层取值单源 = [com.situ.aichat.story.StoryPromptSections.resolvedBannedExpressions]。
     * 老 JSON 无此键 → decode 得 null（`ignoreUnknownKeys` 双向兼容·零 DB 迁移）。
     */
    val bannedExpressions: String? = null,
    /**
     * 章末选择节点开关（图纸二 D3·安卓新增第六字段）：**null = 关**（2026-08-05 用户拍板默认翻转：从不点选项、
     * 结尾解绑自然收束；存量书一并变关）/ true = 本书打开章末选项。
     * 取值一律走单源谓词 [effectiveChapterChoices]，装配点禁止散落 `?: false`。
     * 老 JSON 无此键 → decode 得 null（`ignoreUnknownKeys` 双向兼容·零 DB 迁移）。
     */
    val chapterChoicesEnabled: Boolean? = null,
    /**
     * 本故事的场面节拍（故事二期卷一·第八字段·提案 §3.1 层 1）。**真三态**（与忌口的二态刻意不同）：
     * `null` = 跟随全局 / `""`（或纯空白）= **本书关掉主节拍段** / 文本 = 本书覆盖。
     * 取值单源 = [com.situ.aichat.story.StoryCraftSections.resolvedSceneBeats]（本书 › 全局 › 出厂默认）。
     * 老 JSON 无此键 → decode 得 null（`ignoreUnknownKeys` 双向兼容·零 DB 迁移）。
     */
    val sceneBeats: String? = null,
    /**
     * 本故事的读者口味画像（故事二期卷一·第九字段·提案 §5.3）。三态同 [sceneBeats]，但**无内置默认**：
     * 本书与全局都没填 → 整段不注入。取值单源 = [com.situ.aichat.story.StoryCraftSections.resolvedTasteProfile]。
     */
    val tasteProfile: String? = null,
    /**
     * 场景状态快照开关（故事二期卷一·第十字段·提案 §4.3）：**null = 开**（老书零变化）/ false = 本书不注入
     * 「## 当前场景状态」段。取值单源谓词 [effectiveSceneSnapshot]，装配点禁止散落 `?: true`。
     */
    val sceneSnapshotEnabled: Boolean? = null,
) {
    /** 是否含任何非 null 字段，用于决定是否把 JSON 写入 Story（1:1 iOS `hasAnyValue` :13-16）。 */
    val hasAnyValue: Boolean
        get() = genreTechniques != null || writerIdentity != null || writingRules != null ||
            pacingPreference != null || bannedExpressions != null ||
            chapterChoicesEnabled != null ||
            sceneBeats != null || tasteProfile != null || sceneSnapshotEnabled != null

    /**
     * 是否有**内容类**字段（图纸二 D3 副作用修正）：两个格式开关虽然也住这份 JSON，但它们不是「提示词内容」——
     * 设定页「自定义提示词」行的已填写/未填写只看这个谓词，否则用户只动了一下开关，那一行就谎报「已填写」。
     * 落库判空仍用 [hasAnyValue]（开关也得能把 JSON 留住）。pacingPreference 计入与本卷之前一致，不改既有口径。
     */
    val hasAnyPromptContent: Boolean
        get() = genreTechniques != null || writerIdentity != null || writingRules != null ||
            pacingPreference != null || bannedExpressions != null ||
            sceneBeats != null || tasteProfile != null

    /**
     * 章末选择节点是否开着的**单源谓词**（图纸二 D3·2026-08-05 默认翻转为关）：`== true` —— 只有显式打开才算开；
     * null（老书 / 从没动过开关）与 false 都算关。
     * 所有装配点只许读它，绝不许各自写 `?: false`（同 [effectiveWriterIdentity] / `resolvedBannedExpressions` 的单源纪律）。
     */
    val effectiveChapterChoices: Boolean
        get() = chapterChoicesEnabled == true

    /** 场景状态快照是否开着的单源谓词（故事二期卷一·口径同 [effectiveChapterChoices]）。 */
    val effectiveSceneSnapshot: Boolean
        get() = sceneSnapshotEnabled != false

    /**
     * 「用户是否真的自定义了写作身份」的**单源判据**：trim 后非空才算数，纯空白等同没填。
     *
     * 三个消费点必须共用它，口径不许分叉（R1 复核 🔵-2 根治）：
     * ① [com.situ.aichat.story.StoryPromptSections.resolvedWriterIdentity]（身份段本体，空白→回退文风默认身份）；
     * ② [com.situ.aichat.story.StoryGenerationPromptBuilder.appendStorySetup]（创作 prompt 文风行避让）；
     * ③ `StoryOutlinePrompts.appendArcOutlineContext`（弧线大纲文风行避让）。
     *
     * 为什么必须 trim：两条正常写路（创建 `normalizedText`、设置页 `trim().ifBlank`）都已归一，
     * 但**备份导入**能把纯空白身份带进库。旧口径 `isNullOrEmpty` 会把 `"   "` 判成「已自定义」，
     * 后果是身份段是一片空白、文风行又被避让掉 —— 两头落空，模型收不到任何笔调信号。
     */
    val effectiveWriterIdentity: String?
        get() = writerIdentity?.trim()?.ifEmpty { null }

    companion object {
        /**
         * [pacingPreference] 保存端钳位字数（故事二期 D-8：100 → **300**，从「一句话」放宽到「一小段」，
         * 够写「张弛三七开，前三章蓄势铺垫，第四章再爆发」这类真实的节奏交代）。
         *
         * 「废静默截断」由**卷四的编辑框计数 + 硬拦**兑现；这里的 `take` 保留作**兜底**——
         * 备份包/老数据可能带进超长值，落库前仍要有个上限（J10）。
         */
        const val PACING_MAX_CHARS = 300

        /**
         * 保存端归一化 [pacingPreference]：trim → 截 [PACING_MAX_CHARS] 字 → 空归 null。
         * **创建流与设置流共用此单源**，两条写路的钳位口径不许分叉。
         */
        fun normalizedPacing(text: String?): String? =
            text?.trim()?.take(PACING_MAX_CHARS)?.ifEmpty { null }

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /** 编码为 JSON 串（写回 Room）。 */
        fun encode(prompts: CustomStoryPrompts): String =
            json.encodeToString(CustomStoryPrompts.serializer(), prompts)

        /**
         * 从 [jsonString] 解码；空/解码失败返回 null（1:1 iOS `Story.customPrompts` `try?` :157-163）。
         */
        fun decode(jsonString: String?): CustomStoryPrompts? {
            if (jsonString.isNullOrEmpty()) return null
            return runCatching { json.decodeFromString<CustomStoryPrompts>(jsonString) }.getOrNull()
        }

        /**
         * 合成新建故事时的初始 customPrompts（1:1 iOS `composeForCreation` :31-47）。
         *
         * 优先级：**用户在创建页填写的值 > null（走预设默认）**。
         * 仅用于 `isCustomGenre == true`；预设类型不应用 override（尊重「选预设就要该预设风格」）。
         * （`ScenePromptOverride.story` 全局默认层已 J4 销案：安卓无场景系统，override* 参数为保 1:1 iOS 签名
         *   保留但恒传空串、不产生效果，见 [com.situ.aichat.ui.story.StoryCreationViewModel]。）
         *
         * @param userWriterIdentity/userGenreTechniques/userWritingRules 用户 UI 上 trim 过的值（非空串或 null）
         * @param overrideWriterIdentity/overrideGenreTechniques/overrideWritingRules 安卓恒空串（scene-override 已销案）
         */
        fun composeForCreation(
            userWriterIdentity: String?,
            userGenreTechniques: String?,
            userWritingRules: String?,
            overrideWriterIdentity: String,
            overrideGenreTechniques: String,
            overrideWritingRules: String,
        ): CustomStoryPrompts = CustomStoryPrompts(
            genreTechniques = userGenreTechniques ?: overrideGenreTechniques.ifEmpty { null },
            writerIdentity = userWriterIdentity ?: overrideWriterIdentity.ifEmpty { null },
            writingRules = userWritingRules ?: overrideWritingRules.ifEmpty { null },
        )
    }
}
