package com.situ.aichat.diagnostics

import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台任务健康诊断（Android 16 可靠性加固·③）。
 *
 * 本应用「AI 有自己的生活」的体验（主动发消息、生成日程/心情、延迟回复…）全压在 WorkManager 上，而国行 ROM
 * （HyperOS/MIUI）激进杀后台是项目第一大软肋；Android 16 又进一步收紧了后台配额（前台服务运行期并发的 job 会
 * 烧配额、可能被掐）。偏偏本应用无 GMS = 没有 Play Vitals / Firebase，线上完全没有「为什么这台小米没按时发」的
 * 可观测手段。
 *
 * 本类把系统现成的两类答案抽出来打进 logcat（开发期 `adb logcat -s BgWorkDiag` 即可看，零持久化、零 UI、不碰审美关）：
 *  1. [JobScheduler.getPendingJobReasons]（API 36）/ [JobScheduler.getPendingJobReason]（API 34）——「这条排队的后台
 *     任务此刻为什么没跑」：APP_STANDBY / BACKGROUND_RESTRICTION / QUOTA / DEVICE_STATE / 各种约束未满足…，正是排查
 *     HyperOS 限制的金线索。
 *  2. [WorkInfo.getStopReason]（WorkManager 2.9+）——「最近哪条 worker 被系统中途停了、为什么」：QUOTA / TIMEOUT /
 *     约束失守 / 后台限制…，直接照见 Android 16 配额收紧是否咬到本应用。
 *
 * 触发点：回前台一次性快照（[com.situ.aichat.ui.AppViewModel.onAppForeground]）——用户每次打开 app 就在 logcat 留一
 * 张「后台健康快照」，无需常驻观察者、零额外功耗、不刷屏。整链 [runCatching] 包死：诊断任何异常都绝不波及正常功能。
 */
@Singleton
class BackgroundWorkDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 回前台健康快照：列「排队为何不跑」+「最近被停原因」，并打一行汇总。诊断绝不抛。 */
    suspend fun logForegroundSnapshot() {
        runCatching {
            val pending = logPendingJobs()
            val stopped = logRecentStopReasons()
            Log.i(TAG, "后台健康快照：排队待跑 $pending 个 · 近期被系统停 $stopped 个（详见上方；两者皆 0 = 一切正常）")
        }.onFailure { Log.w(TAG, "诊断快照失败（不影响功能）：${it.message}") }
    }

    /** 枚举本应用在 JobScheduler 里排队（含 WorkManager 底层 job）的任务，逐条打出「为何还没跑」。返回排队数。 */
    private fun logPendingJobs(): Int {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return 0
        // allPendingJobs 只含本应用 uid 调度的 job（WorkManager 的也在内）。
        val jobs = js.allPendingJobs
        if (jobs.isEmpty()) return 0
        jobs.forEach { job ->
            val why = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA ->
                    js.getPendingJobReasons(job.id).joinToString().ifEmpty { "(无阻塞原因)" }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    pendingJobReasonLabel(js.getPendingJobReason(job.id))
                else -> "(API<34：原因不可查)"
            }
            Log.i(TAG, "排队 job #${job.id}：$why")
        }
        return jobs.size
    }

    /** 快照式读一遍 WorkManager 的 WorkInfo，打出最近被系统中途停过的 worker（stopReason≠未停）。返回被停数。 */
    private suspend fun logRecentStopReasons(): Int {
        val infos = WorkManager.getInstance(context)
            .getWorkInfosFlow(WorkQuery.fromStates(WorkInfo.State.entries))
            .first()
        val stopped = infos.filter { it.stopReason != WorkInfo.STOP_REASON_NOT_STOPPED }
        stopped.forEach { wi ->
            // WorkManager 自动把 worker 类全名加成 tag；取它当可读名，取不到回退短 id。
            val name = wi.tags.firstOrNull { it.endsWith("Worker") }?.substringAfterLast('.')
                ?: wi.id.toString().take(8)
            Log.i(TAG, "近期被停 worker [$name] 态=${wi.state} 原因=${stopReasonLabel(wi.stopReason)} 已重试${wi.runAttemptCount}次")
        }
        return stopped.size
    }

    companion object {
        const val TAG = "BgWorkDiag"

        /**
         * [JobScheduler] 排队原因码 → 人话（API 34 引入整族常量；本函数只做纯映射，故 [RequiresApi] 标注、由 API≥34
         * 的调用点护住）。覆盖 QUOTA / APP_STANDBY / BACKGROUND_RESTRICTION 等排查 HyperOS 限制的关键码，未知码回退原值。
         */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        fun pendingJobReasonLabel(reason: Int): String = when (reason) {
            JobScheduler.PENDING_JOB_REASON_UNDEFINED -> "未定义"
            JobScheduler.PENDING_JOB_REASON_APP -> "应用自行暂停了它"
            JobScheduler.PENDING_JOB_REASON_APP_STANDBY -> "应用待机分桶受限(APP_STANDBY)"
            JobScheduler.PENDING_JOB_REASON_BACKGROUND_RESTRICTION -> "后台被限制(BACKGROUND_RESTRICTION·疑国产ROM杀后台)"
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "约束:电量不低未满足"
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CHARGING -> "约束:充电中未满足"
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONNECTIVITY -> "约束:网络未满足"
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_CONTENT_TRIGGER -> "约束:内容触发未满足"
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_DEVICE_IDLE -> "约束:设备空闲未满足"
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_MINIMUM_LATENCY -> "约束:最小延迟未到"
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_PREFETCH -> "约束:预取条件未满足"
            JobScheduler.PENDING_JOB_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "约束:存储不低未满足"
            JobScheduler.PENDING_JOB_REASON_DEVICE_STATE -> "设备状态不允许(DEVICE_STATE·如低电/过热)"
            JobScheduler.PENDING_JOB_REASON_EXECUTING -> "正在执行"
            JobScheduler.PENDING_JOB_REASON_INVALID_JOB_ID -> "无效 job id"
            JobScheduler.PENDING_JOB_REASON_JOB_SCHEDULER_OPTIMIZATION -> "被调度器优化推迟"
            JobScheduler.PENDING_JOB_REASON_QUOTA -> "配额耗尽(QUOTA·Android16收紧重点)"
            JobScheduler.PENDING_JOB_REASON_USER -> "用户态限制"
            else -> "原因码 $reason"
        }

        /**
         * [WorkInfo.getStopReason] 停止原因码 → 人话（WorkManager 库常量，全 API 可用；API<31 恒为「未停」）。
         * 重点照见 QUOTA/TIMEOUT 等 Android 16 配额收紧的咬合点，未知码回退原值。
         */
        fun stopReasonLabel(reason: Int): String = when (reason) {
            WorkInfo.STOP_REASON_NOT_STOPPED -> "未停"
            WorkInfo.STOP_REASON_UNKNOWN -> "未知"
            WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "应用主动取消"
            WorkInfo.STOP_REASON_PREEMPT -> "被更高优任务抢占(PREEMPT)"
            WorkInfo.STOP_REASON_TIMEOUT -> "超时(TIMEOUT)"
            WorkInfo.STOP_REASON_DEVICE_STATE -> "设备状态(DEVICE_STATE·如低电/过热)"
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "约束失守:电量过低"
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "约束失守:停止充电"
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "约束失守:网络断开"
            WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "约束失守:设备不再空闲"
            WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "约束失守:存储过低"
            WorkInfo.STOP_REASON_QUOTA -> "配额耗尽(QUOTA·Android16收紧重点)"
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "后台被限制(疑国产ROM杀后台)"
            WorkInfo.STOP_REASON_APP_STANDBY -> "应用待机分桶受限(APP_STANDBY)"
            WorkInfo.STOP_REASON_USER -> "用户操作(如强停/清后台)"
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "系统处理中"
            WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "预计启动时间变更"
            else -> "原因码 $reason"
        }
    }
}
