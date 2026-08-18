package com.situ.aichat.story

import com.situ.aichat.data.repository.StoryRepository

/**
 * 已存走向的两个写口（图纸 2026-08-06「已存走向推进区状态化」§3.5）：**覆盖**与**撤回**。
 *
 * 治的是 `StoryReaderViewModel.submitChoice` 那道「`ch.userChoice != null` 即 return」的静默丢弃门——
 * 它是首次提交的正确守卫（配 4 秒反悔窗防手滑），但用户重开导演台改一条已存走向时，字被无声吃掉。
 * 于是编辑已存走向另走这里：**直写、不进反悔窗**（编辑本身可再编辑，即是反悔通道）。
 *
 * 为什么是 object 而不是塞进 VM：`StoryReaderViewModel` 已越 600 行硬上限挂号（FILE_SIZE_REFACTOR_BACKLOG），
 * 只许 +接线不许 +逻辑体；房风照 [StoryChoiceClassifier] / [StoryArchiver]（参数注入，不动 DI）。
 *
 * **三条禁令**（图纸 §9 机制锁）：
 * 1. 不调 `clearEndingRequestAfterUserAction`——结局意图覆盖注入点契约锁死**恰三处**
 *    （commitPendingChoice / forceContinue / rewrite），改走向/撤走向不是生成触发动作，真要生成时 forceContinue 会清；
 * 2. 覆盖恒 `commitUserChoice(setSerializing = false, fromStatus = null)`——只动 userChoice 一列，状态零碰；
 * 3. 撤回的状态回转只做 `fresh.status == SERIALIZING` 一档（PAUSED/GENERATION_FAILED 回转不合法且语义混乱）。
 *
 * 异常一律向上抛，由调用方（VM 的 runCatching）兜成体面错误（PITFALLS 1b「suspend 服务 API 异常契约 = 调用方兜底」）。
 */
internal object StoryDirectionEditor {

    /**
     * 覆盖已存走向：只写 `userChoice` + `choiceMadeAt` 两列，**书的状态一个字节不动**。
     *
     * @param text 用户在导演台栏 A 的新文本（本函数负责 trim）
     * @return `false` **仅**表示忙碌（见 [isBusy]）；其余一律 true——含「故事查无」「文本空白」两个 no-op 成功路
     *   （空白本该被 UI 的 dirty 判据挡住，这里是防御位）。
     */
    suspend fun overwrite(
        repository: StoryRepository,
        taskManager: StoryGenerationTaskManager,
        storyId: String,
        chapterId: String,
        text: String,
        nowMillis: Long,
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val fresh = repository.getStory(storyId) ?: return true
        if (isBusy(fresh.status, taskManager, storyId)) return false
        repository.commitUserChoice(
            storyId = storyId,
            chapterId = chapterId,
            choice = trimmed,
            nowMillis = nowMillis,
            setSerializing = false,
            fromStatus = null,
        )
        return true
    }

    /**
     * 撤回已存走向：清 `userChoice`/`choiceMadeAt`；**有选项章 + 连载中**时同事务把书转回等待选择
     * ——否则追更自动路（只捞 SERIALIZING 的书）会对「重新待答的那个选择」裸跑生成。
     *
     * 回转判定用的是**现读的 fresh 状态**、不信 VM 手头的快照（PITFALLS 1b：事务外的并发敏感判定要现算）。
     *
     * @param chapterHasChoice 本章是否有选项节点（决定撤回后选择区会不会重新开放）
     * @return `false` **仅**表示忙碌；故事查无 → true 且零写。
     */
    suspend fun withdraw(
        repository: StoryRepository,
        taskManager: StoryGenerationTaskManager,
        storyId: String,
        chapterId: String,
        chapterHasChoice: Boolean,
        nowMillis: Long,
    ): Boolean {
        val fresh = repository.getStory(storyId) ?: return true
        if (isBusy(fresh.status, taskManager, storyId)) return false
        repository.withdrawUserChoice(
            storyId = storyId,
            chapterId = chapterId,
            revertToWaitingChoice = chapterHasChoice && fresh.status == StoryStatus.SERIALIZING,
            fromStatus = fresh.status,
            nowMillis = nowMillis,
        )
        return true
    }

    /**
     * 忙碌守卫（照 `StoryReaderViewModel.planFinale` / [StoryArchiver] 逐字）：生成中拒写。
     *
     * **双闸只挡得住一路半**（图纸 §5 E10 如实登记）：手动生成走 [StoryGenerationTaskManager]，
     * `activeGenerations` 直接命中；追更自动路（`StoryAutoSerializeService`）不经 TaskManager、
     * 生成期间书的状态仍留 SERIALIZING，故探不到。**残余竞态窗由两件事兜底**：生成侧读的是章节快照，
     * 而覆盖写只动 `userChoice` 单列 ⇒ 最坏结果是「新走向下一章才生效」，不会有数据损坏、不会写坏正在生成的那一章。
     */
    private fun isBusy(
        freshStatus: String,
        taskManager: StoryGenerationTaskManager,
        storyId: String,
    ): Boolean = freshStatus == StoryStatus.GENERATING ||
        taskManager.activeGenerations.value.containsKey(storyId)
}
