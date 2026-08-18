package com.situ.aichat.foreground

import com.situ.aichat.story.StoryGenPhase

/**
 * 前台常驻通知当前该显示什么（灵动岛卷一 §3.4）。取代原 `ForegroundProgress` 单 data class：
 * 药丸现在有两种真实内容，用 sealed 把「哪一种」表达进类型，通知层照 when 穷举分派、不必猜字段。
 *
 * 三态：[StoryProgress]（故事生成·确定性四段进度）｜[Typing]（用户等回复·不确定进度）｜
 * null（备份导出等纯保活 → 静默常驻通知）。
 *
 * 仲裁（谁占药丸）不在本文件也不在通知层，而在 [LlmGenerationForegroundController] 的双槽 —— 故事优先于 typing。
 */
sealed interface ForegroundActivity {

    /** 故事章节生成：四段真实进度（[overall] 与两条文案均由 `StoryProgressModel` 单源换算）。 */
    data class StoryProgress(
        val storyId: String,
        val overall: Double,
        val genPhase: StoryGenPhase,
        val phaseLabel: String,
        val shortLabel: String,
        val title: String,
        val chapterNumber: Int,
    ) : ForegroundActivity

    /** 用户主动发消息、等角色回复：不确定进度 + 角色头像（[conversationUuid] 供点击深链回该会话）。 */
    data class Typing(
        val characterName: String,
        val avatarPath: String?,
        val conversationUuid: String?,
    ) : ForegroundActivity
}
