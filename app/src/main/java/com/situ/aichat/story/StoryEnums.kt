package com.situ.aichat.story

/**
 * 故事 (M08) 各 raw 串常量，1:1 复刻 iOS `Models/Story.swift` / `StoryCharacterRole.swift` 里的字符串枚举。
 *
 * iOS 用 `enum XxxValue { static let ... }` 持 raw 串；安卓沿用「存 raw 串、按值比较」的项目惯例
 * （同 [com.situ.aichat.data.model] 下 WalletOwnerType / MomentAuthorType 等），避免 Room TypeConverter。
 *
 * 显示文案（如「等待选择」）走 strings.xml，留待 UI 块（11.1h）。
 */
object StoryStatus {
    /** 连载中（默认）。 */
    const val SERIALIZING = "serializing"
    /** 等待用户做选择。 */
    const val WAITING_CHOICE = "waitingChoice"
    /** 已完结。 */
    const val COMPLETED = "completed"
    /** 已暂停（不自动连载）。 */
    const val PAUSED = "paused"
    /** 生成中（有活跃生成任务）。 */
    const val GENERATING = "generating"
    /** 生成失败（卡住恢复时标记）。 */
    const val GENERATION_FAILED = "generationFailed"
}

/** 聊天影响权重 raw（1:1 iOS `StoryChatInfluenceWeightValue` :77-83）。 */
object StoryChatInfluenceWeight {
    /** 完全隔离（聊天不影响故事）。 */
    const val NONE = "none"
    /** 仅最近聊天话题。 */
    const val LIGHT = "light"
    /** 关系 + 最近互动摘要（默认）。 */
    const val MEDIUM = "medium"
    /** 关系/情绪/话题/记忆/成长/结构化记忆 全量。 */
    const val HEAVY = "heavy"
}

/** 故事角色类型 raw（1:1 iOS `Models/StoryCharacterRole.swift` `StoryRoleTypeValue` :5-9）。 */
object StoryRoleType {
    const val PROTAGONIST = "protagonist"
    const val SUPPORTING = "supporting"
    const val ANTAGONIST = "antagonist"
}

/** 叙事人称 raw（iOS `Story.narrativePerson`，默认 second）。 */
object StoryNarrativePerson {
    const val FIRST = "first"
    const val SECOND = "second"
    const val THIRD = "third"
}

/** 更新模式 raw（iOS `Story.updateMode`，默认 free；仅 chase 自动连载，见 spec §1.2）。 */
object StoryUpdateMode {
    /** 追更：到点自动生成下一章。 */
    const val CHASE = "chase"
    /** 自由：永不自动生成（默认）。 */
    const val FREE = "free"
}

/**
 * 请求结局类型 raw（iOS `Story.requestedEndingType`；生成时由 [StoryWritingTechniques] requestedEndingRequirements 读取，
 * 故 raw 串须与其 "open"/"custom" 分支一致）。custom 时另带 requestedEndingDetail。
 */
object StoryEndingType {
    /** 开放式结局。 */
    const val OPEN = "open"
    /** AI 自由发挥。 */
    const val AI = "ai"
    /** 用户指定结局（带 detail）。 */
    const val CUSTOM = "custom"
}
