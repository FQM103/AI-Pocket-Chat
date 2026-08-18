package com.situ.aichat.ui.story

import com.situ.aichat.story.StoryStatus
import com.situ.aichat.story.StoryUpdateMode

/** 书架卡片快捷操作种类（1:1 iOS `StoryCardView.quickActionTitle` 的分支）。 */
internal enum class StoryQuickAction { REGENERATE, GENERATING, MAKE_CHOICE, CONTINUE_READING, READ_LATEST }

/** 书架卡长按菜单动作（ST10-4 状态感知菜单）。UI 侧 DELETE 渲染为危险项、与前组发丝分隔。 */
internal enum class StoryCardMenuAction { PAUSE, RESUME, ARCHIVE, SETTINGS, DELETE }

/**
 * 书架卡片快捷操作的纯决策（11.1h-2）。抽出便于单测锁定 iOS 优先级：
 * 生成失败 > 生成中 > 待选择(且有最新章) > 上次阅读 > 阅读最新 > 无。
 */
internal object StoryCardLogic {

    fun quickAction(
        status: String,
        hasPendingChoice: Boolean,
        latestChapterNumber: Int?,
        lastReadChapterNumber: Int?,
    ): StoryQuickAction? = when {
        status == StoryStatus.GENERATION_FAILED -> StoryQuickAction.REGENERATE
        status == StoryStatus.GENERATING -> StoryQuickAction.GENERATING
        hasPendingChoice && latestChapterNumber != null -> StoryQuickAction.MAKE_CHOICE
        lastReadChapterNumber != null -> StoryQuickAction.CONTINUE_READING
        latestChapterNumber != null -> StoryQuickAction.READ_LATEST
        else -> null
    }

    /**
     * 长按菜单项组装（ST10-4·微图纸 2026-07-17-故事书架菜单与结局归档）：只列当前状态下**真正有效**的
     * 动作，根治旧固定三项菜单里「暂停连载」在等待选择等状态下点击静默无效的陷阱。
     *
     * - PAUSE 仅「连载中 + 追更」：自动连载只推进该组合（[StoryAutoSerializeService] 只拉 serializing +
     *   [StoryAutoSerializePolicy] 跳 free），其它状态/模式下暂停无实际效果，列出来只会再骗一次点击；
     * - RESUME 仅「已暂停」；
     * - ARCHIVE（完结归档）除已完结/生成中全可用（生成中不给，避免与生成落库赛跑）；
     * - SETTINGS / DELETE 恒有。
     *
     * COMPLETED 不出现在书架在读区（无长按菜单），仍穷举返回兜底组合防未来调用面扩大。
     */
    fun menuActions(status: String, updateMode: String): List<StoryCardMenuAction> = buildList {
        when (status) {
            StoryStatus.SERIALIZING -> {
                if (updateMode == StoryUpdateMode.CHASE) add(StoryCardMenuAction.PAUSE)
                add(StoryCardMenuAction.ARCHIVE)
            }
            StoryStatus.PAUSED -> {
                add(StoryCardMenuAction.RESUME)
                add(StoryCardMenuAction.ARCHIVE)
            }
            StoryStatus.WAITING_CHOICE, StoryStatus.GENERATION_FAILED -> add(StoryCardMenuAction.ARCHIVE)
            else -> Unit // GENERATING / COMPLETED / 未知 raw：只留查看设定与删除
        }
        add(StoryCardMenuAction.SETTINGS)
        add(StoryCardMenuAction.DELETE)
    }
}
