package com.situ.aichat.notification

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * POST_NOTIFICATIONS 运行时权限助手(P6.1a)。Android 13(API 33)+ 才需运行时授权；之前版本安装即授予。
 * 实际的授权请求 UI（弹窗）放在通知设置页(6.1c)；这里只提供查询，供 [Notifier] 发通知前守卫，
 * 无权限时静默跳过（避免崩溃 / 无效发送）。
 */
object NotificationPermission {

    /** 运行时通知权限名（API 33+ 生效）。 */
    val PERMISSION: String = Manifest.permission.POST_NOTIFICATIONS

    /** 是否已可发通知（API < 33 恒为 true）。 */
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }
}
