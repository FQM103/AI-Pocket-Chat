package com.situ.aichat.foreground

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.situ.aichat.notification.NotificationChannels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 通用「后台 LLM/长生成」进程保活前台服务（13.7b 泛化自 P11 故事生成前台服务）。生成/流式期间挂前台，防国产
 * ROM 在用户切走 app 后秒杀进程（= iOS `beginBackgroundTask` 的安卓加强版——iOS 仅 ~30s 宽限，安卓前台服务无
 * 时限地保活）。
 *
 * 复用方（[LlmGenerationForegroundController] 引用计数共享同一实例）：故事章节生成（手动/追更自动）、聊天流式
 * 回复（A7「切走不中断」）。本服务是**纯进程保活锚点**——真正的生成/流式跑在各调用方的协程作用域里，只要本
 * 服务在前台，进程不被杀、那些协程就能继续。启停由控制器按「活跃任务数」引用计数控制（启在任务前=前台、停在
 * 任务完=可能已后台 → stopService 不受后台启动限制）。
 */
@AndroidEntryPoint
class LlmGenerationForegroundService : Service() {

    /** 引用计数控制器：超时回调里复位计数（见 [onTimeout]）+ 提供 [LlmGenerationForegroundController.activity] 供通知刷新。 */
    @Inject lateinit var controller: LlmGenerationForegroundController

    /** 服务级作用域：收集控制器活动流，把进度/typing 刷进常驻通知。onDestroy 取消。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observingActivity = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 5s 规则：被拉起后必须立即 startForeground。失败/被拒则自停，绝不卡住。
        NotificationChannels.ensureCreated(this)
        if (!startForegroundWithFallback()) {
            // 两档都失败，已无路可走：此刻 stopSelf 仍可能被系统判「没及时 startForeground」，但不比不停更好。
            Log.e(TAG, "startForeground 全档失败，退化为无前台保活：任务仍在各自 scope 继续，只是不抗后台杀")
            stopSelf()
            return START_NOT_STICKY
        }
        // 5s 规则已满足 → 现在停服才安全。任务在本服务起身前就全部结束时（生成秒失败=实测 19ms 窗口），
        // 控制器记了停服欠账，这里兑现：服务起来的唯一意义就是「合法地停下去」，绝不让 stopService 抢在前头。
        if (controller.onServiceForegrounded()) {
            Log.d(TAG, "起身即发现任务已全部结束 → 就地自停（避开启停竞态致命异常）")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        observeActivity()
        return START_NOT_STICKY
    }

    /**
     * 挂前台，两档兜底：① 当前活动态（药丸/进度，= 现状外观，有权限时行为零变）；② 静默态
     * （`build(_, null)`——已在**无通知权限**的真机上验证可成功：备份导出路实测 FGS 正常起、无 FATAL）。
     *
     * 任一档成功即满足 5s 规则，此后 `stopSelf` 绝对安全。这是对「startForeground 抛错 → stopSelf → 系统致命异常」
     * 这条老路的第二道闸（第一道在控制器的启停竞态闸）。
     */
    private fun startForegroundWithFallback(): Boolean =
        // 首帧尽量不吃 IO：typing 态要 decode 头像，真慢时由 observeActivity 的 IO 线程补刷。
        tryStartForeground("活动态") { LlmForegroundNotification.build(this, controller.activity.value) } ||
            tryStartForeground("静默态") { LlmForegroundNotification.build(this, null) }

    private fun tryStartForeground(label: String, build: () -> Notification): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground($label) 失败：${e.message}")
        false
    }

    /**
     * 收集控制器活动流（⑤ Live Update）：故事生成推进度时把常驻通知刷成四段 ProgressStyle 灵动岛；
     * typing 时刷成不确定进度 + 头像；两槽皆空回落通用静默态。
     *
     * 整个 build 包进 IO：typing 分支要 decode 角色头像位图（`AvatarStore.loadBlocking` 是阻塞磁盘解码
     * 且**自身不切线程**），放主线程会卡帧。
     */
    private fun observeActivity() {
        if (observingActivity) return
        observingActivity = true
        val service = this
        scope.launch {
            controller.activity.collect { a ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        service.getSystemService(NotificationManager::class.java)
                            ?.notify(NOTIFICATION_ID, LlmForegroundNotification.build(service, a))
                    }.onFailure { Log.w(TAG, "常驻通知刷新失败（不影响保活）：${it.message}") }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // 停止时确保撤掉常驻通知（stopService 触发 onDestroy；显式 REMOVE 双保险）。
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // 清控制器残值：此刻 scope 已 cancel，清值无人消费 → 既不闪静默帧、下次拉起也不闪旧值。
        controller.onServiceStopped()
        super.onDestroy()
    }

    // FGS 超时回调（API 34 起 onTimeout(startId)；API 36 起带 fgsType 的重载）：dataSync FGS 每滚动 24h 上限 ~6h，
    // 到点系统必调此方法，本服务须在数秒内停掉自己、否则抛致命 RemoteServiceException。本项目生成/流式均分钟级，
    // 触发即意味引用计数泄漏 → 复位计数（[LlmGenerationForegroundController.onForegroundServiceTimedOut]）+ 即刻停服。
    override fun onTimeout(startId: Int) = handleTimeout()

    override fun onTimeout(startId: Int, fgsType: Int) = handleTimeout()

    private fun handleTimeout() {
        Log.w(TAG, "前台服务超时（dataSync 6h 上限）→ 复位引用计数并停服，避免致命 RemoteServiceException")
        controller.onForegroundServiceTimedOut()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        /** 常驻通知 id（沿用故事前台服务的 id 空间，避开他类通知）。 */
        const val NOTIFICATION_ID = 0x57081
        private const val TAG = "LlmGenFgs"

        /** 拉起前台服务（须在前台调用——本项目生成/流式总在「开 app」时发起，满足后台启动限制）。 */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, LlmGenerationForegroundService::class.java))
        }

        /** 停止前台服务（stopService 不受后台启动限制，任务完成时即使已退后台也能停）。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, LlmGenerationForegroundService::class.java))
        }
    }
}
