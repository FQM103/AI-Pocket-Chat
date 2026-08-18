package com.situ.aichat.story

import android.content.Context
import android.util.Log
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.foreground.LlmGenerationForegroundController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 追更（chase）自动连载检查（11.1g-2，1:1 iOS `StoryScheduleActor.checkAndGenerateStories` :14-74 前台路径）。
 *
 * 用户拍板（2026-06-05）「开 app 生成 + 前台服务保活、不做周期后台生成」：本服务只由**回前台**触发
 * （[onAppForeground]），**不**复刻 iOS 的 BGTask 30min 周期（MIUI 限流不可靠）。生成跑在本服务的**应用级作用域**
 * （survive ViewModel 重建/屏幕切换；进程级存活的前台服务 = 11.1g-3）。
 *
 * 与手动路径（[StoryGenerationTaskManager]）的关系（= iOS 双路径，共用单一 [StoryGenerationService]）：
 * - 手动：status→generating、有进度 UI、发**完成**通知；自动：**不改 generating**（留 serializing，崩了下次回前台自愈重试）、
 *   无进度 UI、排**解锁**通知（[StoryUnlockNotificationScheduler]）。
 * - 去重：自动跳过 `taskManager.isGenerating` 的故事；反向（自动生成中用户手动点同一故事）为与 iOS 同源的罕见并发暴露，归 D1/P12。
 */
@Singleton
class StoryAutoSerializeService @Inject constructor(
    @ApplicationContext context: Context,
    private val storyRepository: StoryRepository,
    private val generationService: StoryGenerationService,
    private val taskManager: StoryGenerationTaskManager,
    private val unlockScheduler: StoryUnlockNotificationScheduler,
    private val foregroundController: LlmGenerationForegroundController,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 单次检查互斥（= iOS `StoryScheduleService.isRunning` 锁），防回前台多次叠跑。 */
    private val isRunning = AtomicBoolean(false)

    /** 整个回前台 pass（恢复 + 重排 + 检查）的守卫，避免多次 ON_RESUME 叠起 pass。 */
    private var foregroundJob: Job? = null

    /**
     * 本进程内是否已重排过一次解锁闹钟（图纸卷二 §3.5）：兜底语义（精确闹钟不跨重启）**全靠这一跑**，
     * 故进程首次回前台无条件重排；之后才吃防抖。进程死亡自动复位 = 重启后首次回前台必重排。
     */
    private var unlockRefreshedThisProcess = false

    /**
     * 回前台故事启动 pass（fire-and-forget，跑在 app-scope）：① 卡死恢复（[StoryGenerationTaskManager.recoverStuckStories]）
     * → ② 解锁闹钟重排（精确闹钟不跨重启的兜底·**进程首跑 ∨ 防抖到期**才跑）→ ③ 追更自动生成检查（防抖 + 互斥）。
     * 顺序：先清卡死、再排解锁、最后生成。
     *
     * ② 加防抖的理由：重排每次都要对章节表做一次无索引全表扫（`unlockAt > now`），书越厚越贵，
     * 而「同一进程里 10 分钟内反复切前后台」根本不会产生新的待解锁章。间隔复用
     * [StoryAutoSerializePolicy.MINIMUM_CHECK_INTERVAL_MS]，但**时间戳与生成检查各记各的**（独立 key，互不顶掉）。
     */
    fun onAppForeground() {
        if (foregroundJob?.isActive == true) return
        foregroundJob = scope.launch {
            taskManager.recoverStuckStories()
            refreshUnlockNotificationsIfDue()
            checkAndGenerateStories(reason = "appForeground")
        }
    }

    /** 解锁重排的「进程首跑 ∨ 防抖到期」门（§3.5）：跑之前先记时间戳，判过即记（同生成检查的惯例）。 */
    private suspend fun refreshUnlockNotificationsIfDue() {
        val now = System.currentTimeMillis()
        val firstRun = !unlockRefreshedThisProcess
        if (!firstRun && !StoryAutoSerializePolicy.isDebounceElapsed(prefs.getLong(KEY_LAST_UNLOCK_REFRESH_AT, 0L), now)) {
            Log.d(TAG, "解锁闹钟重排跳过（防抖未到）")
            return
        }
        unlockRefreshedThisProcess = true
        prefs.edit().putLong(KEY_LAST_UNLOCK_REFRESH_AT, now).apply()
        unlockScheduler.refreshAllUnlockNotifications(now)
    }

    /**
     * 自动连载检查（1:1 iOS `checkAndGenerateStories`）：互斥 → 防抖 → 拉 serializing → 逐个（跳 free / 今日已更 /
     * 手动生成中）生成下一章 → 排解锁通知。失败仅记日志、状态留 serializing（下次回前台自愈重试 = iOS actor）。
     */
    suspend fun checkAndGenerateStories(reason: String, force: Boolean = false) {
        if (!isRunning.compareAndSet(false, true)) return
        var acquired = false // 前台保活：首个真生成才挂前台，批末统一释放（无可生成则不挂）。
        try {
            if (!force && !debounceElapsedAndRecord()) {
                Log.d(TAG, "自动连载检查跳过（防抖未到）reason=$reason")
                return
            }
            val zone = ZoneId.systemDefault()
            val serializing = storyRepository.getStoriesByStatus(StoryStatus.SERIALIZING)
            Log.d(TAG, "自动连载检查 reason=$reason，候选 ${serializing.size} 部")
            for (snapshot in serializing) {
                // 逐个重新拉取，取最新缓存态（= iOS 循环内重新 fetch）。
                val story = storyRepository.getStory(snapshot.id) ?: continue
                if (taskManager.isGenerating(story.id)) continue // 用户正手动生成 → 跳（去重）
                val now = System.currentTimeMillis()
                if (!StoryAutoSerializePolicy.shouldAutoGenerate(story.updateMode, story.cachedLatestChapterCreatedAt, now, zone)) {
                    continue
                }
                if (!acquired) {
                    foregroundController.acquire()
                    acquired = true
                }
                try {
                    // R5#1：自动路径也套全局超时（与手动路 StoryGenerationTaskManager 同一分档函数）——LLM 流久挂时
                    // 旧逻辑会把 isRunning + 前台服务永久占住、自动连载彻底停摆且服务泄漏。withTimeout 超时抛
                    // TimeoutCancellationException（是 CancellationException 子类，故须在其**之前**捕获）。
                    // 卷一 V9 分档：思考模型 600s / 其余 300s。
                    val thinking = generationService.creationConfig()?.isThinkingModel == true
                    val chapter = withTimeout(StoryGenerationProgressLogic.generationTimeoutMs(thinking)) {
                        if (story.cachedChapterCount > 0) {
                            generationService.generateNextChapter(story, System.currentTimeMillis())
                        } else {
                            generationService.generateFirstChapter(story, System.currentTimeMillis())
                        }
                    }
                    // 生成成功后：若该章有未来解锁时间（追更章在 materialize 算好），排到点解锁通知。
                    val unlockAt = chapter.unlockAt
                    if (unlockAt != null && unlockAt > System.currentTimeMillis()) {
                        unlockScheduler.scheduleUnlock(story.id, story.title, chapter.chapterNumber, chapter.title, unlockAt)
                    }
                    Log.d(TAG, "追更自动生成 story=${story.id.take(8)} ch=${chapter.chapterNumber}「${chapter.title}」")
                } catch (timeout: TimeoutCancellationException) {
                    // 单部超时计为一次失败：留 serializing 下次重试、**不重抛**（不传染取消，继续下一部）；
                    // 外层 finally 仍会 release 前台 + 复位 isRunning（withTimeout 抛出后 for/try 正常退出本次迭代）。
                    Log.e(TAG, "追更自动生成超时 story=${story.id.take(8)}（留 serializing 待重试）", timeout)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 1:1 iOS actor：仅记日志，不改状态（留 serializing，下次回前台重试自愈）。
                    Log.e(TAG, "追更自动生成失败 story=${story.id.take(8)}（留 serializing 待重试）", e)
                }
            }
        } finally {
            if (acquired) foregroundController.release()
            isRunning.set(false)
        }
    }

    /** 防抖判定 + 记录（= iOS `shouldRunNow`：判过即记 lastCheckAt）。 */
    private fun debounceElapsedAndRecord(): Boolean {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        if (!StoryAutoSerializePolicy.isDebounceElapsed(last, now)) return false
        prefs.edit().putLong(KEY_LAST_CHECK_AT, now).apply()
        return true
    }

    private companion object {
        const val TAG = "StoryAutoSerialize"
        const val PREFS = "story_schedule_prefs"
        const val KEY_LAST_CHECK_AT = "storySchedule.lastCheckAt"
        /** 解锁重排的独立防抖时间戳（§3.5）：与 [KEY_LAST_CHECK_AT] 分家，两条防抖互不影响。 */
        const val KEY_LAST_UNLOCK_REFRESH_AT = "storySchedule.lastUnlockRefreshAt"
    }
}
