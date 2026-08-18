package com.situ.aichat.ui.story

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.situ.aichat.R
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.ui.designsystem.AppTheme

/** 故事状态 → 用户可见显示名 string res（1:1 iOS `StoryStatusValue.displayName`）。卡片 + 章节列表共用。 */
fun storyStatusDisplayNameRes(status: String): Int = when (status) {
    StoryStatus.WAITING_CHOICE -> R.string.story_status_waiting_choice
    StoryStatus.COMPLETED -> R.string.story_status_completed
    StoryStatus.PAUSED -> R.string.story_status_paused
    StoryStatus.GENERATING -> R.string.story_status_generating
    StoryStatus.GENERATION_FAILED -> R.string.story_status_failed
    else -> R.string.story_status_serializing
}

/** 状态徽章前景/背景色（ST7a·脱 iOS 系统橙绿 → 设计 token）。 */
data class StoryBadgeColors(val content: Color, val container: Color)

/**
 * 故事状态 → 徽章配色（ST7a·契约 §6.1·照 mockup 屏一 badge）：连载中 / 生成中 = 陶土；等你选择 = 琥珀 warning；
 * 已完结 / 暂停 = 中性凹陷；生成失败 = 红。取设计语言 status 双档（container 装饰底 + on 功能文字 ≥4.5:1）。
 */
@Composable
@ReadOnlyComposable
fun storyStatusBadgeColors(status: String): StoryBadgeColors {
    val c = AppTheme.colors
    return when (status) {
        StoryStatus.WAITING_CHOICE -> StoryBadgeColors(c.status.onWarning, c.status.warningContainer)
        StoryStatus.GENERATION_FAILED -> StoryBadgeColors(c.status.onError, c.status.errorContainer)
        StoryStatus.COMPLETED, StoryStatus.PAUSED -> StoryBadgeColors(c.text.secondary, c.surface.sunken)
        else -> StoryBadgeColors(c.accent.text, c.accent.container)
    }
}
