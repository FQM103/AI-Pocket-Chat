package com.situ.aichat.data.model

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryChatInfluenceWeight
import com.situ.aichat.story.StoryCreationCatalog
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.story.StoryUpdateMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 「我的模板」承载的**整套创作设定**（图纸四 §3.2·2026-08-01 用户拍板扩围）。
 *
 * 存进 [com.situ.aichat.data.local.entity.UserStoryTemplateEntity.payloadJson] 单列——
 * 加字段零迁移（decode 走 `ignoreUnknownKeys`，老模板缺键即默认值）。
 *
 * **有意不含**（图纸 §0.2-11 锁定）：书名（模板是「设定包」不是「书」）、参演角色（用户拍板）、
 * 聊天角色选择（开书时现选）、段序 A/B 开关（那是全局实验项，不随模板走）。
 *
 * [customPromptsJson] 整串原样存：身份 / 技法 / 铁律 / 节奏偏好 / 文字忌口 / 两个格式开关全在这一串里
 * （[CustomStoryPrompts]），单串免拆装、也不会随该类加字段而漏带。
 */
@Serializable
data class UserStoryTemplatePayload(
    /** 题材（已解析的最终值，自定义题材存的就是那个自定义名）。 */
    val genre: String = "",
    /**
     * 当初是不是自定义题材。**StoryEntity 不存这个事实**，[fromStory] 按「题材不在内置目录里」反推
     * （图纸 §11 D-1）——它只影响「改一改再开」时创建屏把题材放进自定义输入框还是预设选择器，
     * 两条路解析出的最终题材字符串都等于 [genre]。
     */
    val isCustomGenre: Boolean = false,
    val writingStyle: String = "",
    /** [StoryNarrativePerson] raw。 */
    val narrativePerson: String = StoryNarrativePerson.SECOND,
    val chapterLengthPreference: Int = 1500,
    /** [StoryChatInfluenceWeight] raw。 */
    val chatInfluenceWeight: String = StoryChatInfluenceWeight.MEDIUM,
    val worldSetting: String? = null,
    val plotDirection: String? = null,
    /** [StoryUpdateMode] raw：追更 / 自由。 */
    val updateMode: String = StoryUpdateMode.FREE,
    val unlockHour: Int = 20,
    val unlockMinute: Int = 0,
    /** [CustomStoryPrompts] 的 JSON 串原样（null = 那本书没有任何自定义提示词）。 */
    val customPromptsJson: String? = null,
    /**
     * 自定义题材时创建屏选的「参考题材」。**它是纯创建屏辅助项、从不落库**（其效果——预填的类型技法——
     * 早已固化进 [customPromptsJson]），故 [fromStory] 恒得 null；字段保留是为了往返不丢
     * （将来若有别的存入口能提供它，无需改 schema）。见图纸 §11 D-1。
     */
    val referenceGenre: String? = null,
) {
    companion object {
        /** 模板数量上限（图纸 §0.2-10 锁定：单用户够用，防列表无限膨胀）。 */
        const val MAX_USER_TEMPLATES = 20

        /** 用户模板在模板墙 / 路由参数里的 id 前缀（内置 12 套 id 全是 kebab-case 无冒号，两个空间不相交·E15）。 */
        const val USER_TEMPLATE_ID_PREFIX = "user:"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(payload: UserStoryTemplatePayload): String =
            json.encodeToString(UserStoryTemplatePayload.serializer(), payload)

        /** 从 [jsonString] 解码；空 / 解码失败返回 null（照 [CustomStoryPrompts.decode] 口径·E7 损坏模板不崩）。 */
        fun decode(jsonString: String?): UserStoryTemplatePayload? {
            if (jsonString.isNullOrEmpty()) return null
            return runCatching { json.decodeFromString<UserStoryTemplatePayload>(jsonString) }.getOrNull()
        }

        /**
         * 从一本**已开的书**抽取整套创作设定（纯函数·存模板端唯一抽取口）。
         * 书名与角色有意不取（见类 KDoc）；[UserStoryTemplatePayload.isCustomGenre] 见其字段说明。
         */
        fun fromStory(story: StoryEntity): UserStoryTemplatePayload = UserStoryTemplatePayload(
            genre = story.genre,
            isCustomGenre = story.genre !in StoryCreationCatalog.genres,
            writingStyle = story.writingStyle,
            narrativePerson = story.narrativePerson,
            chapterLengthPreference = story.chapterLengthPreference,
            chatInfluenceWeight = story.chatInfluenceWeight,
            worldSetting = story.worldSetting,
            plotDirection = story.plotDirection,
            updateMode = story.updateMode,
            unlockHour = story.unlockHour,
            unlockMinute = story.unlockMinute,
            customPromptsJson = story.customPromptsJson,
            referenceGenre = null,
        )
    }
}

/**
 * 套用模板时的 **copy-merge**（图纸四 §3.4）：以模板存下的那串 [template] 作**底**，把表单里能编辑的
 * 非 null 字段覆盖上去——用户在「改一改再开」里改过什么就以他改的为准，表单表达不了的
 * （文字忌口 / 两个格式开关）留模板的。
 *
 * [template] 为 null（= 非模板路）时**原样返回 this**，故普通创建流的产物逐字节同现状（E5 回归钉）。
 * 逐字段列全而不用 `copy`：将来 [CustomStoryPrompts] 加字段，这里编译不过 = 强制来补一行，
 * 比静默漏带一个字段安全。
 */
internal fun CustomStoryPrompts.overlaidOnTemplate(template: CustomStoryPrompts?): CustomStoryPrompts =
    if (template == null) {
        this
    } else {
        CustomStoryPrompts(
            genreTechniques = genreTechniques ?: template.genreTechniques,
            writerIdentity = writerIdentity ?: template.writerIdentity,
            writingRules = writingRules ?: template.writingRules,
            pacingPreference = pacingPreference ?: template.pacingPreference,
            bannedExpressions = bannedExpressions ?: template.bannedExpressions,
            chapterChoicesEnabled = chapterChoicesEnabled ?: template.chapterChoicesEnabled,
        )
    }
