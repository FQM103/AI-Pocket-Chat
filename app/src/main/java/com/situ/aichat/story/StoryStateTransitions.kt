package com.situ.aichat.story

import android.util.Log

/**
 * 故事状态机合法转换表 + 观测闸（ST3a·FABLE5_STORY_REDESIGN_PROPOSAL §9「状态机收口」第一刀）。
 *
 * **本表 = 现状的镜像，不是理想化设计**：从 2026-07 代码里全部状态写点反推出的真实转换全集——
 * [StoryGenerationTaskManager]（runGeneration / handleFailure / retryGeneration / recoverStuckStories）、
 * [StoryChapterMaterializer]（materializeChapter / prepareRewrite）、[StoryGenerationService.continueStory]、
 * [com.situ.aichat.data.repository.StoryRepository.commitUserChoice]、
 * StoryReader / StorySettings / StoryBookshelf 三个 ViewModel 的直写。逐边出处：
 *
 * - `serializing → generating`：TaskManager.runGeneration（正常起生成）。
 * - `serializing → serializing`：复位类写（commitUserChoice / prepareRewrite / requestEnding）+
 *   自动连载路 materialize 无选择章（StoryAutoSerializeService 直调生成、不经 TaskManager、无 generating 中间态）。
 * - `serializing → waitingChoice / completed`：自动连载路 materialize（同上，落库时状态仍是 serializing）。
 *   ST11 起 materialize 的 completed 只剩两条来源：**用户请求结局** + **满章封顶且自动扩展用尽**——
 *   「LLM 自标 isEnding」已不再决定状态（拍板②完结权归用户，只落章印 aiSuggestedEnding）；
 *   用户手动完结走归档边（见下 `waitingChoice / paused / generationFailed → completed`）。
 * - `serializing → paused`：书架 togglePause。
 * - `serializing → generationFailed`：TaskManager.handleFailure 边缘（generating 落库写失败后失败收尾 /
 *   materialize 已复位 serializing 后尾部异常仍走失败收尾）。
 * - `waitingChoice → serializing`：commitUserChoice / forceContinue / requestEnding / prepareRewrite。
 * - `waitingChoice → generationFailed`：materialize 已置 waitingChoice 后尾部异常 → handleFailure。
 * - `completed → serializing`：continueStory（开启续篇）/ prepareRewrite 重写末章。
 * - `completed → generationFailed`：materialize 已置 completed 后尾部异常 → handleFailure。
 * - `paused → serializing`：书架 togglePause / 设置页恢复连载 / 暂停中重写末章（prepareRewrite 不设状态门）。
 * - `generating → serializing / waitingChoice / completed`：手动路 materialize（[StoryGenerationPolicy.decideStatus]）。
 * - `generating → generationFailed`：handleFailure（失败 / 超时 / 取消）/ recoverStuckStories（卡死恢复）。
 * - `generationFailed → serializing`：retryGeneration / 失败态下 requestEnding / prepareRewrite。
 * - `generationFailed → generating`：retryGeneration 先写 serializing 后持旧句柄直接 startGeneration
 *   （写点手头的 from 是旧态，DB 真值已是 serializing）。
 * - `waitingChoice / paused / generationFailed → completed`：书架长按「完结归档」
 *   （ST10-4 StoryBookshelfViewModel.archiveStory·手动归档不生成结局章；serializing → completed 边
 *   已存在，归档与自动连载路共用）。
 *
 * INSERT 型初始态（创建新书 / restartStory 复制新书恒 serializing）不是转换、不进表。
 *
 * **观测闸，不改行为**：各写点在写状态前调 [check]——非法只 Log.w 记录（带 site 与 from→to），
 * 写入仍照旧执行；不为取 from 增加额外 DB 读（调用点手头没有旧状态就传 null 直接放行）。
 * 后续要升级为「硬闸」（非法即拒写）须另行拍板再议。
 */
internal object StoryStateTransitions {

    /** 合法转换表：from → 允许的 to 集合（[StoryStatus] raw 串）。 */
    private val LEGAL: Map<String, Set<String>> = mapOf(
        StoryStatus.SERIALIZING to setOf(
            StoryStatus.SERIALIZING,
            StoryStatus.WAITING_CHOICE,
            StoryStatus.COMPLETED,
            StoryStatus.GENERATING,
            StoryStatus.PAUSED,
            StoryStatus.GENERATION_FAILED,
        ),
        StoryStatus.WAITING_CHOICE to setOf(
            StoryStatus.SERIALIZING,
            StoryStatus.COMPLETED,
            StoryStatus.GENERATION_FAILED,
        ),
        StoryStatus.COMPLETED to setOf(
            StoryStatus.SERIALIZING,
            StoryStatus.GENERATION_FAILED,
        ),
        StoryStatus.PAUSED to setOf(
            StoryStatus.SERIALIZING,
            StoryStatus.COMPLETED,
        ),
        StoryStatus.GENERATING to setOf(
            StoryStatus.SERIALIZING,
            StoryStatus.WAITING_CHOICE,
            StoryStatus.COMPLETED,
            StoryStatus.GENERATION_FAILED,
        ),
        StoryStatus.GENERATION_FAILED to setOf(
            StoryStatus.SERIALIZING,
            StoryStatus.GENERATING,
            StoryStatus.COMPLETED,
        ),
    )

    /**
     * 观测闸：写状态前调用。
     *
     * @param from 调用点手头的旧状态 raw；null = 手头没有旧状态 → 直接放行（不为取 from 加 DB 读）
     * @param to 即将写入的新状态 raw
     * @param site 写点标识（「类名.方法名」），非法时随日志输出便于定位
     * @return 是否合法转换。非法时已 Log.w（带 site 与 from→to）；调用方**不据此拦截写入**——本闸只观测。
     */
    fun check(from: String?, to: String, site: String): Boolean {
        if (from == null) return true
        val legal = LEGAL[from]?.contains(to) == true
        if (!legal) Log.w(TAG, "非法状态转换 [$site] $from → $to（观测闸：仍照旧写入）")
        return legal
    }

    private const val TAG = "StoryStateTransitions"
}
