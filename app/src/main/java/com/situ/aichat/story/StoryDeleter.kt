package com.situ.aichat.story

import com.situ.aichat.data.repository.StoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「删除故事」共用协作者（2026-08-04 缺口修复·照 [StoryArchiver] 姿势）：级联删库 + 撤销该书全部
 * 已排「章节解锁」精确闹钟。此前三个删除入口（书页 / 书架长按 / 档案全览长按）各自只调
 * [StoryRepository.deleteStory]，闹钟到点照发「第 N 章已解锁」、深链指向已不存在的书——收口在这一处，
 * 三个 VM 不各写一遍（CLAUDE.md §2）。
 *
 * 顺序有讲究（微图纸 2026-08-04 §4）：
 * 1. **先捕章号**——闹钟 key 含章号（`storyUnlock_{storyId}_{chapterNumber}`），级联删库后章行已不在查不到；
 * 2. 删库——DB 异常原样上抛，由各入口 VM 自己 runCatching（三处的失败提示语义各不相同）；
 * 3. 删库**成功后**才撤闹钟——删失败时书还在书架上，追更提醒不能丢。
 *
 * 已知窄缝：撤销后、进程内仍在飞的自动连载若恰好再排一章，闹钟会重新出现——但其落库因故事行已删而失败，
 * 章也不存在，与既有删除赛跑窗口同宽，不为此加锁。
 */
@Singleton
class StoryDeleter @Inject constructor(
    private val repository: StoryRepository,
    private val unlockScheduler: StoryUnlockNotificationScheduler,
) {

    /**
     * 删除一本故事（级联删章节/角色）并撤销其全部章节解锁闹钟。
     * @throws Exception 删库失败时原样上抛（此时闹钟一个都没撤）。
     */
    suspend fun delete(storyId: String) {
        val chapterNumbers = repository.getChapterNumbers(storyId)
        repository.deleteStory(storyId)
        unlockScheduler.cancelUnlocks(storyId, chapterNumbers)
    }
}
