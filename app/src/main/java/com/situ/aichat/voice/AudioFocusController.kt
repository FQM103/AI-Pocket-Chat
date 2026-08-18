package com.situ.aichat.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voice-call audio session — the Android mapping of iOS `VoiceCallManager+AudioSession`. It owns audio
 * focus, the comm-audio mode (system AEC/AGC), and the speaker/earpiece/Bluetooth route.
 *
 * iOS `.playAndRecord` + `.voiceChat` → here:
 *  - `AUDIOFOCUS_GAIN` (exclusive) with `USAGE_VOICE_COMMUNICATION` / `CONTENT_TYPE_SPEECH` attributes;
 *  - `MODE_IN_COMMUNICATION`, which is what arms the platform AEC/AGC on the `VOICE_COMMUNICATION`
 *    record source (the call's anti-self-trigger, = the reason iOS picks `.voiceChat`, see §4 坑①);
 *  - route override via `setCommunicationDevice` (API 31+) / `isSpeakerphoneOn` (API 29-30) =
 *    iOS `overrideOutputAudioPort(.speaker/.none)`;
 *  - an `AudioDeviceCallback` re-applies the user's route when a headset/Bluetooth device comes or goes
 *    = iOS `handleRouteChange`.
 *
 * Focus loss (incoming phone call, another app grabbing audio) is surfaced via [onFocusLost] /
 * [onFocusRegained] = iOS interruption began/ended; the controller decides whether to pause or end.
 *
 * Needs `MODIFY_AUDIO_SETTINGS` (declared with the call service in 10.1g). Framework-bound, so it is
 * compile- + Logcat-verified; the testable thresholds live in the pure helpers.
 */
@Singleton
class AudioFocusController @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var focusRequest: AudioFocusRequest? = null
    private var savedMode: Int = AudioManager.MODE_NORMAL
    private var deviceCallback: AudioDeviceCallback? = null
    private var preferSpeaker = false
    private var active = false

    /** Focus lost (incoming call etc.) → controller pauses/ends the turn (= iOS interruption began). */
    var onFocusLost: (() -> Unit)? = null

    /** Focus regained → controller resumes (= iOS interruption ended). */
    var onFocusRegained: (() -> Unit)? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "audio focus lost ($change)")
                onFocusLost?.invoke()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "audio focus regained")
                onFocusRegained?.invoke()
            }
        }
    }

    /**
     * Acquire exclusive voice-comm focus + comm-audio mode and apply the current route. Returns true if
     * focus was granted. 1:1 iOS `configureAudioSession`.
     */
    fun acquire(): Boolean {
        if (!active) savedMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        active = true
        registerDeviceCallback()
        applyRoute()
        Log.i(TAG, "audio focus acquire granted=$granted mode=IN_COMMUNICATION")
        return granted
    }

    /** Abandon focus, clear the route override, restore the previous mode. 1:1 iOS `restoreAudioSession`. */
    fun release() {
        if (!active) return
        active = false
        unregisterDeviceCallback()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        clearCommunicationDevice()
        audioManager.mode = savedMode
        preferSpeaker = false
        Log.i(TAG, "audio focus released, mode restored")
    }

    /** Toggle speaker vs earpiece (= iOS `overrideOutputAudioPort`). Re-applies the route immediately. */
    fun setSpeakerEnabled(enabled: Boolean) {
        preferSpeaker = enabled
        applyRoute()
    }

    /**
     * Apply the preferred route: speaker when requested; otherwise a connected Bluetooth/wired headset if
     * present, else the earpiece. Mirrors iOS respecting the system route for headsets while honouring the
     * user's speaker/earpiece choice for the built-ins.
     */
    private fun applyRoute() {
        if (!active) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyRouteApi31()
        } else {
            applyRouteLegacy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S) // 仅由 applyRoute() 在 SDK_INT >= S 分支调用；标注让 lint 识别 API 31 守卫。
    private fun applyRouteApi31() {
        val devices = audioManager.availableCommunicationDevices
        val target = if (preferSpeaker) {
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            // Prefer an external comm headset (BT SCO / BLE / wired) over the earpiece (= .allowBluetoothHFP).
            devices.firstOrNull { it.type in EXTERNAL_COMM_TYPES }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }
        if (target != null && audioManager.setCommunicationDevice(target)) {
            Log.i(TAG, "route → ${target.type} (speaker=$preferSpeaker)")
        } else {
            Log.w(TAG, "setCommunicationDevice failed (speaker=$preferSpeaker)")
        }
    }

    @Suppress("DEPRECATION") // API 29-30 has no setCommunicationDevice; these are the supported path there.
    private fun applyRouteLegacy() {
        if (preferSpeaker) {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = true
        } else {
            audioManager.isSpeakerphoneOn = false
            if (audioManager.isBluetoothScoAvailableOffCall) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        }
        Log.i(TAG, "route(legacy) speaker=$preferSpeaker")
    }

    private fun clearCommunicationDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            run {
                audioManager.isSpeakerphoneOn = false
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        }
    }

    private fun registerDeviceCallback() {
        if (deviceCallback != null) return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                Log.i(TAG, "audio devices added → re-apply route")
                applyRoute()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                Log.i(TAG, "audio devices removed → re-apply route")
                applyRoute()
            }
        }
        audioManager.registerAudioDeviceCallback(cb, null)
        deviceCallback = cb
    }

    private fun unregisterDeviceCallback() {
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
    }

    private companion object {
        const val TAG = "VoiceCallAudio"
        val EXTERNAL_COMM_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
