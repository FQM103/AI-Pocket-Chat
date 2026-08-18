package com.situ.aichat.foreground

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通用「后台 LLM/长生成」前台服务的引用计数控制器（13.7b 泛化自 P11 故事生成控制器，遵循「同一逻辑只写一处」）。
 *
 * 所有需要进程保活的长任务在开始前 [acquire]、结束后 [release]；计数 0→1 拉起 [LlmGenerationForegroundService]、
 * 1→0 停。复用方：故事章节生成（[com.situ.aichat.story.StoryGenerationTaskManager] 手动 +
 * [com.situ.aichat.story.StoryAutoSerializeService] 追更自动）、聊天流式回复
 * （[com.situ.aichat.ui.chat.AssistantTurnEngine] `runAssistantTurn`，A7「切走不中断」）、备份导入导出
 * （[com.situ.aichat.ui.backup.BackupViewModel]）。
 *
 * 为何引用计数：多路可能并发（多个故事同时生成 + 聊天流式 + 备份），单一前台服务靠计数共享，任一路在跑就保活、全停才撤。
 * [acquire] 失败（极端被拒）不抛——任务照常在各自 scope 继续，只是失去抗后台杀的保活（best-effort）。
 *
 * **保活计数与药丸内容是两件事**：[acquire]/[release] 只管服务生死；药丸显示什么走 [activity] 双槽
 * （见下）——备份路只 acquire 不占槽，于是显示静默态，正合「不打扰」。
 */
@Singleton
class LlmGenerationForegroundController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val activeCount = AtomicInteger(0)

    // ── 启停竞态闸（2026-07-27 闪退修复·图纸 docs/handoff/2026-07-27-前台服务启停竞态闪退.md）──
    // `startForegroundService()` 只是把「创建服务 + onStartCommand」排进**主线程 looper**；而 [acquire] 的调用方
    // 常在别的调度器上秒失败（无 key / 断网 / DB 拒写 / 状态机拒绝），[release] 的 stopService 会抢在服务起身之前
    // 到达 → 系统判「说要挂前台却没挂」，抛致命 ForegroundServiceDidNotStartInTimeException（实录仅 19ms 窗口）。
    // 故：服务确认挂上前台之前，停服请求**只记账不执行**，由服务在 startForeground 成功后自己兑现（[onServiceForegrounded]）。
    // 记账与兑现共用本类 monitor（同 [recompute]），杜绝「置账」与「读账」擦肩导致服务永不停。
    private var foregroundConfirmed = false
    private var stopPending = false

    // ── 双槽仲裁（灵动岛卷一 §3.4）──
    // 药丸只有一个坑，但有两路生产者：故事生成（确定性进度）与聊天 typing（不确定进度）。
    // 各占一槽、互不越权清对方，recompute 里定优先级——否则两路共用单坑时，先结束的那路会把另一路的药丸打成 null。
    private var storySlot: ForegroundActivity.StoryProgress? = null
    private var typingSlot: ForegroundActivity.Typing? = null

    private val _activity = MutableStateFlow<ForegroundActivity?>(null)
    /**
     * 当前前台常驻通知该显示什么（⑤ Live Update）：故事进度 > typing > null（静默保活）。
     * [LlmGenerationForegroundService] 收集本流并据此重建通知（非 null 时用 ProgressStyle 渲染灵动岛药丸）。
     */
    val activity: StateFlow<ForegroundActivity?> = _activity.asStateFlow()

    /** 故事优先于 typing：正在写故事时用户又发了消息，药丸该显示故事进度（信息量更大、且是用户主动发起的长任务）。 */
    @Synchronized
    private fun recompute() {
        _activity.value = storySlot ?: typingSlot
    }

    /** 标记一段任务开始：计数 0→1 时拉起前台服务（须在前台调用，本项目生成/流式总在「开 app」时发起）。 */
    fun acquire() {
        if (activeCount.getAndIncrement() == 0) {
            cancelPendingStop() // 新任务来了 = 撤销上一轮还没兑现的停服欠账
            try {
                LlmGenerationForegroundService.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "前台服务拉起失败（任务继续，仅失去后台保活）：${e.message}")
            }
        }
    }

    /**
     * 标记一段任务结束：计数降到 0 时停前台服务（over-release 安全，钳到 0）。
     *
     * **不在此清残值**：清值挪到 [onServiceStopped]（服务 onDestroy 调）。理由是两难——
     * 在 release 里清，停服前会先闪一帧静默态；不清，下次拉起会闪一帧旧值。
     * 而 onDestroy 时收集器 scope 已 cancel，此刻清值无人消费，两头的闪帧一起根治。
     *
     * **停服可能被推迟**：服务还没挂上前台时停它会致命崩（见本类启停竞态闸注释），此时只记欠账，
     * 由 [onServiceForegrounded] 兑现。
     */
    fun release() {
        val remaining = activeCount.updateAndGet { if (it > 0) it - 1 else 0 }
        if (remaining == 0) {
            stopOrDefer()
        }
    }

    /** 停服：服务已挂上前台就真停，否则只记欠账（与 [onServiceForegrounded] 同锁）。 */
    @Synchronized
    private fun stopOrDefer() {
        if (foregroundConfirmed) {
            LlmGenerationForegroundService.stop(context)
        } else {
            stopPending = true
        }
    }

    @Synchronized
    private fun cancelPendingStop() {
        stopPending = false
    }

    /**
     * 服务已成功 `startForeground`（由 [LlmGenerationForegroundService.onStartCommand] 在挂前台**之后**调用）。
     *
     * @return true = 这期间任务已全部结束，服务应就地自停——此刻 5s 规则已满足，自停不再触发致命异常。
     */
    @Synchronized
    fun onServiceForegrounded(): Boolean {
        foregroundConfirmed = true
        val shouldStop = stopPending || activeCount.get() == 0
        stopPending = false
        return shouldStop
    }

    /** 故事生成推进度（钳 overall 到 0–1）。 */
    fun updateStoryProgress(progress: ForegroundActivity.StoryProgress) {
        storySlot = progress.copy(overall = progress.overall.coerceIn(0.0, 1.0))
        recompute()
    }

    /** 故事生成全部结束：让出故事槽（若 typing 仍在，药丸回落 typing）。 */
    fun clearStoryProgress() {
        storySlot = null
        recompute()
    }

    /** 用户开始等角色回复。 */
    fun setTyping(typing: ForegroundActivity.Typing) {
        typingSlot = typing
        recompute()
    }

    /** 回复送达 / 打断 / 失败：撤 typing 槽。 */
    fun clearTyping() {
        typingSlot = null
        recompute()
    }

    /** 前台服务真的停了（onDestroy）：清两槽残值——此刻收集器已死，清值无人消费，故不会闪帧；同时复位启停握手。 */
    @Synchronized
    fun onServiceStopped() {
        storySlot = null
        typingSlot = null
        _activity.value = null
        foregroundConfirmed = false
        stopPending = false
    }

    /**
     * 前台服务被系统判超时回收（dataSync FGS 每滚动 24h 上限 ~6h，Android 15 起·targetSdk 36 生效）。本项目的生成/流式
     * 任务都是分钟级，正常碰不到这个上限——一旦触发，几乎必是 [acquire]/[release] 没配平（计数泄漏长期 > 0）。这里把
     * 计数清零，使后续 [acquire] 能重新 0→1 把服务拉起、恢复保活；在途协程已失去前台保活，继续 best-effort（与 acquire
     * 被拒时同款降级）。由 [LlmGenerationForegroundService.onTimeout] 调用——它已在系统回调里把服务停掉，这里只复位账。
     */
    fun onForegroundServiceTimedOut() {
        val before = activeCount.getAndSet(0)
        onServiceStopped()
        Log.w(TAG, "前台服务超时被系统回收 → 重置引用计数（原值 $before；疑 acquire/release 泄漏，后续 acquire 将重新拉起保活）")
    }

    /** 仅供单测核对引用计数（acquire/release/超时复位后的内部计数）。 */
    @VisibleForTesting
    internal fun activeCountForTest(): Int = activeCount.get()

    private companion object {
        const val TAG = "LlmGenFgsCtl"
    }
}
