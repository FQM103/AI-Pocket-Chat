package com.situ.aichat.voice

import android.content.Context
import android.media.audiofx.Visualizer
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The voice-call TTS playback engine — the Android port of the per-call `AVAudioPlayer` used by iOS
 * `VoiceCallTTSPipeline`. **Deliberately separate from [com.situ.aichat.tts.TtsAudioPlayer]**: the chat /
 * voice-message player refuses playback while the STT recorder is capturing (anti-feedback), but a call
 * records the mic for barge-in *while the AI speaks*, so that guard would block every call sentence. iOS
 * uses its own player with no such guard (the AEC from `MODE_IN_COMMUNICATION` handles the echo); this
 * mirrors that.
 *
 * Plays in-memory bytes (system WAV / remote MP3) via a media3 [ByteArrayDataSource] — no temp files, and
 * the bytes are released the moment a sentence finishes (= iOS `audioData = nil`, avoids long-call OOM).
 * [play] suspends until the clip ends (or errors); cancelling the calling coroutine stops playback at once.
 * Routed through `USAGE_VOICE_COMMUNICATION` so the earpiece/speaker choice (driven by
 * [AudioFocusController]) applies. [audioLevel] is metered from a [Visualizer] on the player's audio
 * session (= iOS `averagePower` → `(power+50)/50`), degrading to 0 when unavailable (no RECORD_AUDIO until
 * 10.1g, or device quirk). Single-threaded on the caller's main dispatcher, like ExoPlayer requires.
 */
@Singleton
@androidx.annotation.OptIn(UnstableApi::class) // ByteArrayDataSource / ProgressiveMediaSource / audioSessionId / setAudioAttributes
class CallTtsPlayer @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var player: ExoPlayer? = null
    private var visualizer: Visualizer? = null
    private var levelJob: Job? = null
    private var continuation: CancellableContinuation<Boolean>? = null

    @Volatile
    var audioLevel: Float = 0f
        private set

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) finish(success = true)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback error: ${error.errorCodeName}")
            finish(success = false)
        }
    }

    private fun ensurePlayer(): ExoPlayer = player ?: ExoPlayer.Builder(appContext).build().also {
        it.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_VOICE_COMMUNICATION)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ false, // the call owns focus via AudioFocusController
        )
        it.addListener(listener)
        player = it
    }

    /**
     * Play [bytes] and suspend until it finishes. Returns true on natural completion, false on decode/
     * playback error. Cancelling the coroutine stops the player immediately (used by interrupt / reset).
     */
    suspend fun play(bytes: ByteArray): Boolean = suspendCancellableCoroutine { cont ->
        val p = ensurePlayer()
        continuation = cont
        val sourceFactory = DataSource.Factory { ByteArrayDataSource(bytes) }
        val mediaSource = ProgressiveMediaSource.Factory(sourceFactory)
            .createMediaSource(MediaItem.fromUri(Uri.parse("bytes:///call-tts")))
        p.setMediaSource(mediaSource)
        p.prepare()
        p.playWhenReady = true
        startMetering(p.audioSessionId)
        cont.invokeOnCancellation { stopInternal() }
    }

    /** Resume the suspended [play] once (natural end / error), tearing down metering + media. */
    private fun finish(success: Boolean) {
        val cont = continuation ?: return
        continuation = null
        stopMetering()
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        if (cont.isActive) cont.resume(success)
    }

    /**
     * Fully release the underlying ExoPlayer (decoder + audio track). Called by the controller when the
     * call returns to IDLE — a between-calls idle singleton must not pin media resources. The next [play]
     * lazily rebuilds via [ensurePlayer].
     */
    fun release() {
        stopInternal()
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
    }

    /** Hard stop (cancellation path). Does NOT resume the continuation — the coroutine is already cancelling. */
    private fun stopInternal() {
        continuation = null
        stopMetering()
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    private fun startMetering(audioSessionId: Int) {
        stopMetering()
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) return
        val vis = try {
            Visualizer(audioSessionId).apply {
                measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
                captureSize = Visualizer.getCaptureSizeRange()[0]
                enabled = true
            }
        } catch (e: Exception) {
            // No RECORD_AUDIO yet (pre-10.1g) or unsupported on this device → silent fallback to level 0.
            Log.d(TAG, "visualizer unavailable: ${e.message}")
            null
        } ?: return
        visualizer = vis
        levelJob = scope.launch {
            val measurement = Visualizer.MeasurementPeakRms()
            while (isActive) {
                val ok = try {
                    vis.getMeasurementPeakRms(measurement) == Visualizer.SUCCESS
                } catch (e: Exception) {
                    false
                }
                // mRms is in mB (1/100 dB), full-scale-referenced like iOS averagePower → reuse the iOS map.
                audioLevel = if (ok) VoiceCallTtsLogic.normalizePlaybackLevel(measurement.mRms / 100f) else 0f
                delay(METER_TICK_MS)
            }
        }
    }

    private fun stopMetering() {
        levelJob?.cancel()
        levelJob = null
        visualizer?.let {
            try {
                it.enabled = false
                it.release()
            } catch (e: Exception) {
                Log.d(TAG, "visualizer release failed: ${e.message}")
            }
        }
        visualizer = null
        audioLevel = 0f
    }

    private companion object {
        const val TAG = "CallTtsPlayer"
        const val METER_TICK_MS = 50L // 1:1 iOS metering timer 0.05 s (VoiceCallTTSPipeline.swift:263)
    }
}
