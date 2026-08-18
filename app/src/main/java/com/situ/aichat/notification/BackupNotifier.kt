package com.situ.aichat.notification

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.situ.aichat.R

/**
 * 自动备份完成 / 失败的系统通知（13.6c；安卓超越 iOS——iOS 无自动备份）。用户拍板「成功+失败都通知」。
 *
 * 固定通知 id → 新一条覆盖旧的（不在通知栏堆叠多条）。挂 [NotificationChannels.BACKUP]（LOW，静默进通知栏、
 * 不弹横幅不响——备份非紧急）。无 POST_NOTIFICATIONS 权限时静默跳过（备份本身照常完成）。
 */
object BackupNotifier {
    private const val NOTIF_ID = 4201
    private const val TAG = "BackupNotifier"

    fun postSuccess(context: Context, includeMedia: Boolean) {
        val body = context.getString(
            if (includeMedia) R.string.auto_backup_notif_success_media else R.string.auto_backup_notif_success_text,
        )
        // 成功 → 仅跳备份页（不自动重选目录）。
        post(context, context.getString(R.string.auto_backup_notif_success_title), body, focusFolder = false)
    }

    fun postFailure(context: Context, detail: String) {
        val body = if (detail.isBlank()) {
            context.getString(R.string.auto_backup_notif_failed_generic)
        } else {
            context.getString(R.string.auto_backup_notif_failed, detail)
        }
        // 失败/目录丢失 → 跳备份页并自动开目录选择器重选（focusFolder=true）。
        post(context, context.getString(R.string.auto_backup_notif_failed_title), body, focusFolder = true)
    }

    @SuppressLint("MissingPermission") // 由 NotificationPermission.isGranted 守卫
    private fun post(context: Context, title: String, body: String, focusFolder: Boolean) {
        if (!NotificationPermission.isGranted(context)) return
        // 成功/失败共用固定 NOTIF_ID（新覆盖旧），故用不同 requestCode 让两条 PendingIntent 互不串台（P0-19）。
        val contentIntent = Notifier.backupSettingsIntent(
            context,
            focusFolder = focusFolder,
            requestCode = if (focusFolder) NOTIF_ID + 1 else NOTIF_ID,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.BACKUP)
            .setSmallIcon(R.drawable.ic_notif_backup)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "备份通知投递失败 id=$NOTIF_ID: ${e.message}")
        }
    }
}
