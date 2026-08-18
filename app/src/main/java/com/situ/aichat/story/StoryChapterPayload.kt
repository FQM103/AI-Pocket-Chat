package com.situ.aichat.story

import kotlinx.serialization.Serializable

/**
 * 一章生成结果的「已解析型」（1:1 iOS `StoryGenerationService.StoryChapterPayload` :12-60）。
 *
 * 两步法/三级解析的统一产物：既可由 LLM 结构化 JSON 直接 [kotlinx] 解码（第三级），也可由纯代码
 * [StoryGenerationParsing.buildPayload] 从 [StoryMetadataParser.ParseResult] 组装（第一/二级），
 * 再由生成服务 materialize 落库成 [com.situ.aichat.data.local.entity.StoryChapterEntity]。
 *
 * 必填 title/mood/content/hasChoice（= iOS 合成 Decodable 的非可选字段，解码缺失即失败 → 走修复/兜底）；
 * 其余可空默认 null（iOS 可选字段缺失→nil）。JSON 键与 iOS 字段名一致，[kotlinx] 默认序列名即可，无需 @SerialName。
 */
@Serializable
data class StoryChapterPayload(
    val title: String,
    val teaser: String? = null,
    val mood: String,
    val content: String,
    val hasChoice: Boolean,
    val choicePrompt: String? = null,
    val choiceOptions: List<String>? = null,
    val summary: String? = null,
    val currentArc: String? = null,
    val isEnding: Boolean? = null,
    /** 角色当前状态追踪（LLM 每章输出）。 */
    val characterStates: String? = null,
    /** 待回收的伏笔/悬念（LLM 每章输出）。 */
    val openThreads: String? = null,
    /** 下一章各选项方向提示（V2）。 */
    val nextChapterBeats: String? = null,
    /**
     * 本章人物关系新进展（故事二期卷一·可选）：`[里程碑]`/`[近况]` 开头、分号分隔的 0–3 条。
     * 落库时经 [StoryLedgers.appendIntimacy] 追加进故事的关系史账本；缺失 / 「无」= 本章无新增，不追加。
     */
    val intimacyUpdates: String? = null,
    /** 章末场景状态（可选）：**字段缺失 = 沿用上一章**、显式「无」= 清空（两分在落库口 materializeChapter）。 */
    val sceneEndState: String? = null,
    /** 本章重点场景标签（可选）：非「无」时经 [StoryLedgers.appendScene] 追加一行进场景台账。 */
    val sceneTag: String? = null,
)
