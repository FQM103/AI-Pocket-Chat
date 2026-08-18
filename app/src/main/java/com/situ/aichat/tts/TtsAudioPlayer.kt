package com.situ.aichat.tts

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.situ.aichat.stt.SttRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Which message (or preview) is currently playing, plus 0..1 progress, for voice-bubble UI. */
data class TtsPlaybackState(
    val playingId: String? = null,
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
)

/**
 * Single shared audio player for synthesized TTS (1:1 iOS `TTSService` shared player +
 * `+AudioPlayback.swift`). Plays the stored per-message audio (system WAV / remote MP3) on tap and
 * the test-listen preview; only one clip plays at a time (a second `play` stops the first — the
 * other bubble flips to "not playing" via [state]'s `playingId`). Uses media3 ExoPlayer (user-
 * approved, 铁律#4); `Player.STATE_ENDED` is the completion callback.
 *
 * Anti-feedback (1:1 iOS `play(data:)` guard): when recording, playback is refused and the finish
 * callback fires immediately, so the call/STT pipeline can mark the clip done without the speaker
 * bleeding into the mic. [isRecording] is wired to the on-device [SttRecorder] (10.1d).
 *
 * All methods must be called on the main thread (Compose click handlers / `viewModelScope`), where
 * ExoPlayer is created and driven.
 */
@Singleton
class TtsAudioPlayer @Inject constructor(
    @ApplicationContext context: Context,
    sttRecorder: SttRecorder,
) {
    private val appContext = context.applicationContext
    private val mainScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var player: ExoPlayer? = null
    private var onFinish: (() -> Unit)? = null
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(TtsPlaybackState())
    val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()

    /** Anti-feedback (1:1 iOS): refuse playback while the STT recorder is capturing. Wired to [SttRecorder]. */
    @Volatile
    var isRecording: () -> Boolean = { sttRecorder.isRecording }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) finishPlayback()
        }

        override fun onPlayerError(error: PlaybackException) {
            finishPlayback()
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        return ExoPlayer.Builder(appContext).build().also {
            it.addListener(listener)
            player = it
        }
    }

    /**
     * Play the audio file at [path], tagged with [id] (a message UUID, or a sentinel for preview).
     * Refuses while recording (fires [onFinished] then returns). A natural end / decode error fires
     * [onFinished] and returns [state] to idle.
     */
    fun play(id: String, path: String, onFinished: (() -> Unit)? = null) {
        if (isRecording()) {
            onFinished?.invoke()
            return
        }
        val file = File(path)
        if (!file.exists()) {
            onFinished?.invoke()
            return
        }
        // Stop whatever's playing without firing its callback (= iOS play() calling stop() first).
        stopInternal(fireCallback = false)
        val p = ensurePlayer()
        onFinish = onFinished
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        p.playWhenReady = true
        _state.value = TtsPlaybackState(playingId = id, isPlaying = true, progress = 0f)
        startProgressLoop()
    }

    /** Manual stop (tap-to-pause / leaving a screen). Does NOT fire the finish callback (= iOS stop()). */
    fun stop() {
        stopInternal(fireCallback = false)
    }

    private fun finishPlayback() {
        stopInternal(fireCallback = true)
    }

    private fun stopInternal(fireCallback: Boolean) {
        progressJob?.cancel()
        progressJob = null
        val cb = onFinish
        onFinish = null
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        _state.value = TtsPlaybackState()
        if (fireCallback) cb?.invoke()
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = mainScope.launch {
            while (isActive) {
                val p = player ?: break
                val dur = p.duration
                val progress = if (dur > 0) (p.currentPosition.toFloat() / dur).coerceIn(0f, 1f) else 0f
                _state.update { if (it.isPlaying) it.copy(progress = progress) else it }
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    private companion object {
        const val PROGRESS_TICK_MS = 80L // matches iOS 80ms progress refresh
    }
}
