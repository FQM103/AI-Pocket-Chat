package com.situ.aichat.story

import com.situ.aichat.data.repository.StoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「完结归档」共用协作者（ST11·从 [com.situ.aichat.ui.story.StoryBookshelfViewModel].archiveStory **只搬不改**抽出）。
 *
 * 把故事直接标记 completed 收进档案分组——不生成结局章、不写 finalEndingType（null → 档案徽章走「全书完」既有兜底）；
 * 可从设置页/档案详情「继续写这个故事」逆转。
 *
 * 抽出的动机（ST11）：书架长按「完结归档」与阅读器建议卡「就此完结」是**同一个动作**，守卫语义必须一模一样——
 * 与其在两个 VM 里各写一遍（迟早漂移），不如共用这一处。两个 VM 各自把 [Result] 映射成自己的 toast。
 *
 * 落库前 fresh 读最新状态（长按/点按时刻的 UI 快照可能陈旧，PITFALLS 1b）：已完结幂等静默；
 * 生成中（状态 generating 或手动生成任务活跃）拒绝并提示，避免与生成落库赛跑。
 * 已知窄缝：追更自动连载路不经 [StoryGenerationTaskManager]、此处探不到，翻案后果 = 再归档一次
 * （ST10-4 微图纸 §6 已登记）。
 */
@Singleton
class StoryArchiver @Inject constructor(
    private val repository: StoryRepository,
    private val taskManager: StoryGenerationTaskManager,
) {

    /** 归档结果三态：调用方据此决定提示什么（[SKIPPED] 一律静默）。 */
    enum class Result {
        /** 已标记完结（调用方提示「已放入档案」）。 */
        ARCHIVED,

        /** 生成中，拒绝归档（调用方提示「正在生成中，稍后再试」）。 */
        BUSY,

        /** 无需动作：已完结（幂等）或故事已不存在——**静默**，不提示。 */
        SKIPPED,
    }

    /**
     * 归档一本故事。守卫与写库口径 = ST10-4 原实现字节级不变。
     *
     * @param storyId 目标故事
     * @param nowMillis 注入的当前时刻（updatedAt），保可测
     */
    suspend fun archive(storyId: String, nowMillis: Long): Result {
        val fresh = repository.getStory(storyId) ?: return Result.SKIPPED
        return when {
            fresh.status == StoryStatus.COMPLETED -> Result.SKIPPED
            fresh.status == StoryStatus.GENERATING || taskManager.activeGenerations.value.containsKey(storyId) ->
                Result.BUSY
            else -> {
                StoryStateTransitions.check(fresh.status, StoryStatus.COMPLETED, "StoryArchiver.archive")
                repository.updateStatus(storyId, StoryStatus.COMPLETED, nowMillis)
                Result.ARCHIVED
            }
        }
    }
}
