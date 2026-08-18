package com.situ.aichat.voice

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.situ.aichat.R
import com.situ.aichat.notification.NotificationChannels

/**
 * Builds the ongoing voice-call notification — the Android replacement for iOS's Live Activity / Dynamic
 * Island / lock-screen bar (locked decision #3). Uses [NotificationCompat.CallStyle.forOngoingCall] (the
 * closest system semantic: companion as the "caller", a red hang-up button) + the live phase as the status
 * line + a speaker toggle + a count-up chronometer. The waveform is App-only (not in the notification —
 * battery + MIUI rate-limit, spec §3.5/§4坑4).
 *
 * [statusTextRes] is the pure phase→text map (1:1 iOS `VoiceCallLiveActivityPhase.statusText`), kept
 * separate so it is unit-tested without a framework.
 */
internal object VoiceCallNotification {

    /** Phase → status line (1:1 iOS `statusText`; ENDING/IDLE → 通话结束). */
    fun statusTextRes(state: CallState): Int = when (state) {
        CallState.DIALING -> R.string.voice_call_status_connecting
        CallState.LISTENING -> R.string.voice_call_status_listening
        CallState.USER_SPEAKING -> R.string.voice_call_status_user_speaking
        CallState.PROCESSING -> R.string.voice_call_status_thinking
        CallState.AI_SPEAKING -> R.string.voice_call_status_ai_speaking
        CallState.ENDING, CallState.IDLE -> R.string.voice_call_status_ended
    }

    fun build(
        context: Context,
        state: CallState,
        characterName: String,
        avatar: Bitmap?,
        callStartWallClockMs: Long,
        isSpeaker: Boolean,
        hangUpIntent: PendingIntent,
        speakerIntent: PendingIntent,
        contentIntent: PendingIntent,
    ): Notification {
        val name = characterName.ifBlank { context.getString(R.string.app_name) }
        val person = Person.Builder()
            .setName(name)
            .setImportant(true)
            .apply { if (avatar != null) setIcon(IconCompat.createWithBitmap(avatar)) }
            .build()

        // The action reflects the CURRENT output and toggles it (= iOS speaker button).
        val speakerLabel = context.getString(
            if (isSpeaker) R.string.voice_call_action_speaker_on else R.string.voice_call_action_speaker_off,
        )
        val speakerIcon = if (isSpeaker) R.drawable.ic_call_speaker else R.drawable.ic_voice_call
        val speakerAction = NotificationCompat.Action.Builder(speakerIcon, speakerLabel, speakerIntent).build()

        return NotificationCompat.Builder(context, NotificationChannels.VOICE_CALL)
            .setSmallIcon(R.drawable.ic_voice_call)
            .setContentTitle(name)
            .setContentText(context.getString(statusTextRes(state)))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUpIntent))
            .addAction(speakerAction)
            .setUsesChronometer(true)
            .setWhen(callStartWallClockMs)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            // ⑤ Live Update：把进行中的通话升格为状态栏「灵动药丸」(Android 16+ · HyperOS 超级岛同款)。通话 = 持续型
            // 实时通知的教科书场景；NotificationCompat 自带版本门控，API<36 / 不支持时只是不升格、照常显示常驻通知。
            .setRequestPromotedOngoing(true)
            .build()
    }
}
