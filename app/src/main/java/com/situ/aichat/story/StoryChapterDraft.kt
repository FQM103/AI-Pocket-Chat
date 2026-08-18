package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一章的「内容快照」（阅读器掌控力 C3·图纸三 §3.1），JSON 编码后存入
 * [StoryChapterEntity.previousDraftJson]（章级单槽）与
 * [com.situ.aichat.data.local.entity.StoryEntity.pendingRewriteDraftJson]（重写期接力棒）。
 *
 * ## 只装 12 个内容字段
 * 快照 = 实体 16 列里**除轨道字段外**的全部：id / storyId / chapterNumber / createdAt / unlockAt 是这一章在书里的
 * 位置与身份，重写前后恒不变，因此既不进槽也不参与互换。其余 12 列（正文 + 选项态 + 心情/场景 + 小结 + AI 结局印）
 * 全部进槽——「换回上一版」= 后悔这次重写，旧的选择态理应一并回来。
 *
 * ## 全字段可空 + 布尔用 `Boolean?`
 * 老槽/半截 JSON 也要能 decode（[decode] 失败恒返 null，绝不崩）；两个布尔在写回实体时经 `?: false` 折叠，
 * 与实体默认值一致。序列化配置照 [com.situ.aichat.data.model.CustomStoryPrompts]：
 * `ignoreUnknownKeys`（将来加字段不炸老槽）+ `encodeDefaults = false`（null 字段省略，槽更小）。
 */
@Serializable
data class StoryChapterDraft(
    val title: String? = null,
    val teaser: String? = null,
    val content: String? = null,
    val mood: String? = null,
    val scenes: String? = null,
    val hasChoice: Boolean? = null,
    val choicePrompt: String? = null,
    val choiceOptions: String? = null,
    val userChoice: String? = null,
    val choiceMadeAt: Long? = null,
    val aiSuggestedEnding: Boolean? = null,
    val chapterSummary: String? = null,
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /** 章实体 → 快照（12 个内容字段逐个直拷；轨道字段有意不进）。 */
        fun fromEntity(chapter: StoryChapterEntity): StoryChapterDraft = StoryChapterDraft(
            title = chapter.title,
            teaser = chapter.teaser,
            content = chapter.content,
            mood = chapter.mood,
            scenes = chapter.scenes,
            hasChoice = chapter.hasChoice,
            choicePrompt = chapter.choicePrompt,
            choiceOptions = chapter.choiceOptions,
            userChoice = chapter.userChoice,
            choiceMadeAt = chapter.choiceMadeAt,
            aiSuggestedEnding = chapter.aiSuggestedEnding,
            chapterSummary = chapter.chapterSummary,
        )

        /** 编码为 JSON 串（写回 Room 的槽列）。 */
        fun encode(draft: StoryChapterDraft): String = json.encodeToString(serializer(), draft)

        /**
         * 从槽列解码；空 / 解码失败一律返 null（E6：槽含损坏 JSON 视同无槽——菜单项隐藏、绝不崩）。
         * 照 [com.situ.aichat.data.model.CustomStoryPrompts.decode] 的 `runCatching` 先例。
         */
        fun decode(jsonString: String?): StoryChapterDraft? {
            if (jsonString.isNullOrEmpty()) return null
            return runCatching { json.decodeFromString(serializer(), jsonString) }.getOrNull()
        }

        /**
         * 互换纯函数（E5）：把 [draft] 的 12 个内容字段写进 [chapter]，同时把**互换前的当前章**编码进
         * `previousDraftJson` 槽——即两版在同一个槽里对调，因此可反复来回切（`swapApplied` 自身对合：
         * 对结果再 swap 一次必回到起点，前提是两版都能无损编解码）。
         *
         * 返回的实体只用作「13 列定向 UPDATE 的取值来源」（[com.situ.aichat.data.local.dao.StoryDao.swapChapterDraft]），
         * **不做整行 @Update**——轨道字段与并发列不许被 clobber（D1 教训）。
         *
         * 布尔在此折叠 `?: false`（与实体默认一致）：老槽没写这两个键时按「无选择 / 未自标结局」还原。
         */
        fun swapApplied(chapter: StoryChapterEntity, draft: StoryChapterDraft): StoryChapterEntity =
            chapter.copy(
                title = draft.title ?: "",
                teaser = draft.teaser,
                content = draft.content ?: "",
                mood = draft.mood ?: DEFAULT_MOOD,
                scenes = draft.scenes,
                hasChoice = draft.hasChoice ?: false,
                choicePrompt = draft.choicePrompt,
                choiceOptions = draft.choiceOptions,
                userChoice = draft.userChoice,
                choiceMadeAt = draft.choiceMadeAt,
                aiSuggestedEnding = draft.aiSuggestedEnding ?: false,
                chapterSummary = draft.chapterSummary,
                previousDraftJson = encode(fromEntity(chapter)),
            )

        /** 与 [StoryChapterEntity.mood] 的实体默认一致（老槽缺 mood 键时的兜底）。 */
        private const val DEFAULT_MOOD = "peaceful"
    }
}
