package com.situ.aichat.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.situ.aichat.MainActivity
import com.situ.aichat.R

/**
 * 世界系统通知的构建与发出（W8 图纸 §4.2/§4.3）——**独立于** [Notifier]（后者已 629 行越 600 红线，新功能一律进新文件）。
 * 承载世界侧常量（category / action / requestKey 前缀 / 摘要固定 id）+ 三种发出：个体到达 / 合并摘要 / 撤摘要。
 *
 * 构建形态参照 [Notifier.postPet]：走同包 [privacyBuilder]（锁屏隐私占位 + BigText + autoCancel + WORLD 渠道），经
 * [Notifier.postSafely] 发出（内部刷新出声台账·门 7 排队据此）。点击深链走 [ACTION_OPEN_WORLD]——本块只定义常量，
 * 点击暂 = 打开 app 默认页（world 路由分支归 W9/W11，未识别 action 自然落空，无需分支）。**不物化**（瞬时提醒）。
 */
object NotifierWorld {

    /** 世界到达通知的 category（[NotificationAlarmReceiver] 据此把到点闹钟入队 [com.situ.aichat.world.notify.WorldNotifyWorker] 验真再发·§3.2）。 */
    const val CATEGORY_WORLD_ARRIVAL = "world_arrival"

    /** 世界通知点击 action（打开 app·world 路由分支归 W9/W11）。 */
    const val ACTION_OPEN_WORLD = "com.situ.aichat.notification.OPEN_WORLD"

    /** 用户到达目的地腿的 requestKey（一 owner 至多一枚待发闹钟·§3.1）。 */
    const val REQUEST_KEY_USER_ARRIVAL = "world_arrival_user"

    /** 角色来访到达腿的 requestKey 前缀（完整 = 前缀 + characterUuid·§3.1）。 */
    const val REQUEST_KEY_VISIT_PREFIX = "world_arrival_c:"

    /** 合并摘要的固定 requestKey（其 hashCode = 摘要固定通知 id·同日多次溢出只覆盖不堆叠·§4.2）。 */
    const val REQUEST_KEY_SUMMARY = "world_summary"

    /** 合并摘要固定通知 id（= [REQUEST_KEY_SUMMARY] 的 hashCode）。 */
    private val SUMMARY_ID = REQUEST_KEY_SUMMARY.hashCode()

    /**
     * 个体到达通知（§4.2/§4.3·[title]/[body] 由 [com.situ.aichat.world.notify.WorldNotifyService] fire 时从 DB 现读名字组好）。
     * WORLD 渠道·autoCancel·点击 [ACTION_OPEN_WORLD]。无权限静默跳过；后台 worker 进程可能未初始化渠道 → 先 ensureCreated。
     */
    @SuppressLint("MissingPermission") // 同 Notifier 各 post()：isGranted 守卫
    fun postArrival(context: Context, notificationId: Int, title: String, body: String) {
        if (!NotificationPermission.isGranted(context)) return
        NotificationChannels.ensureCreated(context) // 防后台闹钟/worker 进程未经启动初始化 → 渠道缺失
        val pi = PendingIntent.getActivity(
            context,
            notificationId,
            openWorldIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = privacyBuilder(
            context, NotificationChannels.WORLD, title, body, pi, smallIcon = R.drawable.ic_notif_world,
        ).build()
        Notifier.postSafely(context, notificationId, notification, "worldArrival")
    }

    /**
     * 合并摘要通知（§4.2/§4.3·封顶溢出时发）：固定 id 替换式 + `setOnlyAlertOnce(true)`——同日多次溢出只第一次出声，
     * 其后静默更新计数 [n]。无权限静默跳过。
     */
    @SuppressLint("MissingPermission") // 同 postArrival：isGranted 守卫
    fun postSummary(context: Context, n: Int) {
        if (!NotificationPermission.isGranted(context)) return
        NotificationChannels.ensureCreated(context)
        val pi = PendingIntent.getActivity(
            context,
            SUMMARY_ID,
            openWorldIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = privacyBuilder(
            context,
            NotificationChannels.WORLD,
            context.getString(R.string.notif_world_summary_title),
            context.getString(R.string.notif_world_summary_body, n),
            pi,
            smallIcon = R.drawable.ic_notif_world,
        ).setOnlyAlertOnce(true).build()
        Notifier.postSafely(context, SUMMARY_ID, notification, "worldSummary")
    }

    /** 撤合并摘要（回 app 即撤·[com.situ.aichat.world.link.WorldLinkRunner] 前台通行证 step 0.5 调）。 */
    fun cancelSummary(context: Context) {
        NotificationManagerCompat.from(context).cancel(SUMMARY_ID)
    }

    private fun openWorldIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_WORLD
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
}
