package com.situ.aichat.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.situ.aichat.work.NotificationReplyWorker

/**
 * 13.8·B1 通知直接回复接收者：用户在通知栏「回复」框打字提交 → 系统把文字塞进本 intent（动作 PI 为 FLAG_MUTABLE）→
 * 此处取出 → 起加急 worker 后台跑完整一轮 LLM 回复并回推通知栏（[NotificationReplyWorker]）。manifest exported=false
 * （仅本应用回复动作 PI 能触发）。
 *
 * onReceive 必须轻量同步——只取文字 + 入队 worker。重活（LLM）在 worker：App 可能已被国行 ROM 杀、由系统拉起本进程
 * 仅 ~10s 窗口，不能在此做 IO / 网络（同 [NotificationAlarmReceiver] 约束）。
 */
class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_REPLY) return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Notifier.KEY_TEXT_REPLY)
            ?.toString()
            ?.trim()
        if (text.isNullOrEmpty()) return // 空回复忽略（极端情况下系统可能无文字触发）
        NotificationReplyWorker.enqueue(
            context = context,
            conversationUuid = intent.getStringExtra(Notifier.EXTRA_REPLY_CONVERSATION),
            characterId = intent.getStringExtra(Notifier.EXTRA_REPLY_CHARACTER_ID),
            title = intent.getStringExtra(Notifier.EXTRA_REPLY_TITLE).orEmpty(),
            avatarPath = intent.getStringExtra(Notifier.EXTRA_REPLY_AVATAR),
            notificationId = intent.getIntExtra(Notifier.EXTRA_REPLY_NOTIF_ID, 0),
            text = text,
        )
    }
}
