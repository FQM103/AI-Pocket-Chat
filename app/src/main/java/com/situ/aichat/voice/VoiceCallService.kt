package com.situ.aichat.voice

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.situ.aichat.MainActivity
import com.situ.aichat.R
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.notification.NotificationChannels
import com.situ.aichat.util.AvatarStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that hosts a voice call — the Android replacement for iOS's Live Activity presence
 * (locked decision #3). It is what (a) grants the mic in the background per Android 14's
 * `FOREGROUND_SERVICE_TYPE_MICROPHONE` rule, (b) keeps the process alive under HyperOS background limits,
 * and (c) shows the ongoing [VoiceCallNotification] (CallStyle, with hang-up + speaker + chronometer).
 *
 * It OWNS the call lifecycle end-to-end: [start] launches it, it `startForeground`s immediately (the 5 s
 * rule), then loads the character (name + avatar), calls [VoiceCallController.startCall], and mirrors the
 * controller's [VoiceCallController.state] / [VoiceCallController.isSpeakerEnabled] into the notification —
 * stopping itself once the call returns to IDLE. The notification actions route back through `onStartCommand`.
 *
 * **10.1g scope:** system presence only. The app foreground/background hooks
 * ([VoiceCallController.onAppBackgrounded] / [onAppForegrounded]) + keep-screen-on are wired in 10.1h with
 * the UI (where they can be driven by the call screen's lifecycle and device-tested), together with the
 * pause-on-background-vs-keep-running decision. The UI (10.1h) is what calls [start] after RECORD_AUDIO is
 * granted; until then there is no entry point, so this is compile- + Logcat-verified.
 */
@AndroidEntryPoint
class VoiceCallService : android.app.Service() {

    @Inject lateinit var controller: VoiceCallController
    @Inject lateinit var characterRepo: CharacterRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var stopping = false
    /** 是否真的挂上过前台（= 5s 规则已满足）。为假时停服会致命崩，见 [ensureForegroundBeforeStop]。 */
    private var wentForeground = false
    private var callStartWallClockMs = 0L
    private var characterName: String = ""
    private var avatar: android.graphics.Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_HANG_UP -> controller.endCall()
            ACTION_TOGGLE_SPEAKER -> controller.toggleSpeaker()
            else -> Log.w(TAG, "unknown action ${intent?.action}")
        }
        // STICKY would relaunch with a null intent (no convo/char) → can't resume a call meaningfully; iOS
        // also doesn't resurrect a killed call. NOT_STICKY: if the process dies, the call is simply gone.
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val conversationUuid = intent.getStringExtra(EXTRA_CONVERSATION_UUID)
        val characterUuid = intent.getStringExtra(EXTRA_CHARACTER_UUID)
        if (conversationUuid.isNullOrEmpty() || characterUuid.isNullOrEmpty()) {
            Log.w(TAG, "start without conversation/character → stop")
            stopServiceNow()
            return
        }
        callStartWallClockMs = System.currentTimeMillis()
        NotificationChannels.ensureCreated(this)

        // startForeground FIRST (5 s rule) with a "连接中" placeholder — and verify it succeeded BEFORE
        // starting the call, so a RECORD_AUDIO denial can't leave a running call with no foreground host.
        if (!startForegroundSafely()) return
        Log.i(TAG, "foreground call service started for $characterUuid")
        controller.startCall(conversationUuid, characterUuid)
        observeCallState()

        // Load the character (name + avatar) async and refresh the notification once it's in.
        scope.launch {
            val character = characterRepo.get(characterUuid)
            characterName = character?.name.orEmpty()
            avatar = character?.avatarPath?.let { AvatarStore.load(it) }
            updateNotification()
        }
    }

    private fun observeCallState() {
        observeJob?.cancel()
        observeJob = scope.launch {
            combine(controller.state, controller.isSpeakerEnabled) { state, _ -> state }
                .collect { state ->
                    if (state == CallState.IDLE) {
                        Log.i(TAG, "call ended (IDLE) → stop service")
                        stopServiceNow()
                    } else {
                        updateNotification()
                    }
                }
        }
    }

    private fun buildNotification(stateOverride: CallState? = null) = VoiceCallNotification.build(
        context = this,
        state = stateOverride ?: controller.state.value,
        characterName = characterName,
        avatar = avatar,
        callStartWallClockMs = callStartWallClockMs,
        isSpeaker = controller.isSpeakerEnabled.value,
        hangUpIntent = servicePendingIntent(ACTION_HANG_UP, REQ_HANG_UP),
        speakerIntent = servicePendingIntent(ACTION_TOGGLE_SPEAKER, REQ_SPEAKER),
        contentIntent = contentPendingIntent(),
    )

    private fun startForegroundSafely(): Boolean = try {
        // Force DIALING for the placeholder — the controller is still IDLE until startCall() below.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(stateOverride = CallState.DIALING),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        wentForeground = true
        true
    } catch (e: Exception) {
        // e.g. RECORD_AUDIO not granted (Android 14 blocks the mic FGS type) → bail cleanly.
        Log.e(TAG, "startForeground failed: ${e.message}")
        stopServiceNow()
        false
    }

    /**
     * 早退路径的最后一道闸（2026-07-27·同 [com.situ.aichat.foreground.LlmGenerationForegroundService] 的启停竞态修复）：
     * 经 `startForegroundService()` 拉起的服务，**必须**真的挂上过前台才允许停——否则系统抛致命
     * `ForegroundServiceDidNotStartInTimeException`。缺 extras 直接 return、富通知被系统拒 这两条早退路走到停服时
     * 还没挂上，故先用最小通知（同渠道 + 剪影小图标，不碰 CallStyle / 头像 / 计时器等易被拒的富渲染）补一次。
     *
     * 补不上只剩两种可能：mic 型 FGS 被拒（缺 RECORD_AUDIO——与通知无关，兜底也救不回）或系统整体拒绝前台启动。
     * 此时只能照停（不比不停更差），留日志便于真机批定位。
     */
    private fun ensureForegroundBeforeStop() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                NotificationCompat.Builder(this, NotificationChannels.VOICE_CALL)
                    .setSmallIcon(R.drawable.ic_voice_call)
                    .setOngoing(true)
                    .build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            wentForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "minimal startForeground before stop also failed: ${e.message}")
        }
    }

    private fun updateNotification() {
        if (stopping) return
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: ${e.message}")
        }
    }

    private fun stopServiceNow() {
        if (stopping) return
        stopping = true
        observeJob?.cancel()
        if (!wentForeground) ensureForegroundBeforeStop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // FGS 超时回调（API 34/36）：microphone 前台类型按规不受 dataSync 那条 6h/24h 上限约束，正常通话不会触发；
    // 但作为防御性兜底——万一系统因任何原因判超时，优雅挂断停服，绝不卡到抛致命 RemoteServiceException。
    override fun onTimeout(startId: Int) = stopServiceNow()

    override fun onTimeout(startId: Int, fgsType: Int) = stopServiceNow()

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        avatar = null
        super.onDestroy()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, VoiceCallService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Tap the notification → open the call screen (10.1h handles [ACTION_OPEN_VOICE_CALL] navigation). */
    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_VOICE_CALL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQ_CONTENT,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "VoiceCallService"
        const val NOTIFICATION_ID = 0x7CA11 // "CALL"

        const val ACTION_START = "com.situ.aichat.voice.START_CALL"
        const val ACTION_HANG_UP = "com.situ.aichat.voice.HANG_UP"
        const val ACTION_TOGGLE_SPEAKER = "com.situ.aichat.voice.TOGGLE_SPEAKER"
        /** MainActivity opens the call screen on this (10.1h). */
        const val ACTION_OPEN_VOICE_CALL = "com.situ.aichat.voice.OPEN_VOICE_CALL"

        const val EXTRA_CONVERSATION_UUID = "voice_call_conversation_uuid"
        const val EXTRA_CHARACTER_UUID = "voice_call_character_uuid"

        private const val REQ_HANG_UP = 1
        private const val REQ_SPEAKER = 2
        private const val REQ_CONTENT = 3

        /** Start the foreground call service (call ONLY after RECORD_AUDIO is granted — 10.1h). */
        fun start(context: Context, conversationUuid: String, characterUuid: String) {
            val intent = Intent(context, VoiceCallService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONVERSATION_UUID, conversationUuid)
                putExtra(EXTRA_CHARACTER_UUID, characterUuid)
            }
            context.startForegroundService(intent)
        }
    }
}
