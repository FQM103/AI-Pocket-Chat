package com.situ.aichat.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.situ.aichat.work.NotificationRescheduleWorker

/**
 * 开机重排（P6.1c）。`AlarmManager` 精确闹钟**不跨重启保留**，开机后需重新烤一遍——对齐 iOS
 * `RECEIVE_BOOT_COMPLETED` 重注册（manifest 已声明该权限）。
 *
 * onReceive 必须轻量：这里只入队一个 [NotificationRescheduleWorker]（WorkManager 在后台跑实际重排），
 * 不在广播里做 IO / 网络。兼容国产 ROM 的 QUICKBOOT 广播。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != ACTION_QUICKBOOT_POWERON &&
            action != ACTION_HTC_QUICKBOOT_POWERON
        ) {
            return
        }
        Log.d(TAG, "开机广播：$action → 入队通知重排")
        val request = OneTimeWorkRequest.Builder(NotificationRescheduleWorker::class.java)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NotificationRescheduleWorker.UNIQUE_ONESHOT,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    companion object {
        private const val TAG = "BootReceiver"
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
