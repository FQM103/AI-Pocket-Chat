package com.situ.aichat.voice

import android.os.SystemClock
import android.util.Log
import com.situ.aichat.stt.BargeInDetector
import com.situ.aichat.stt.SttConstants
import com.situ.aichat.stt.effectiveInterruptThreshold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Barge-in monitoring while the AI speaks — lifted verbatim out of [VoiceCallController]
 * (2026-07-11 行数拆分·只搬不改). Earpiece adds recognition (non-backchannel speech ≥ 0.3 s); speaker is
 * energy-only (1:1 iOS — see [VoiceCallStt]). The energy [BargeInDetector] always runs over the mic level.
 * [armAfterProtectionWindow] waits 0.5 s before arming so the AI's own voice leaking into the mic does not
 * self-trigger (= iOS VoiceCallManager+TTS.swift:17-22); the mic start retries up to 3× / 0.15 s
 * (= iOS startMonitoringWithRetry). Fires [onBargeIn] at most until [stop].
 */
internal class VoiceCallBargeIn(
    private val stt: VoiceCallStt,
    private val scope: CoroutineScope,
    /** State guard for arming/starting: AI_SPEAKING and the call has not ended. */
    private val canMonitor: () -> Boolean,
    /** Loop-continuation guard (state only, matching the original energy-loop condition). */
    private val isAiSpeaking: () -> Boolean,
    private val isSpeakerEnabled: () -> Boolean,
    private val userThreshold: () -> Float,
    private val onBargeIn: () -> Unit,
) {
    private val detector = BargeInDetector()
    private var bargeInJob: Job? = null
    private var protectJob: Job? = null

    /** 0.5 s protection window after the first sentence starts playing, then start monitoring. */
    fun armAfterProtectionWindow() {
        protectJob?.cancel()
        protectJob = scope.launch {
            delay(SttConstants.AI_SPEAK_PROTECT_MS)
            if (canMonitor()) start()
        }
    }

    private fun start(attempt: Int = 0) {
        if (!canMonitor()) return
        val withRecognition = !isSpeakerEnabled()
        val started =
            (withRecognition && stt.startMonitoring(withRecognition = true) { onBargeIn() }) ||
                stt.startMonitoring(withRecognition = false) { onBargeIn() }
        if (!started) {
            if (attempt >= MONITOR_MAX_RETRY) {
                Log.w(TAG, "barge-in mic start failed after retries")
                return
            }
            scope.launch {
                delay(MONITOR_RETRY_MS)
                start(attempt + 1)
            }
            return
        }
        startEnergyBargeIn()
    }

    private fun startEnergyBargeIn() {
        detector.reset()
        bargeInJob?.cancel()
        bargeInJob = scope.launch {
            while (isActive && isAiSpeaking()) {
                delay(BARGE_IN_TICK_MS)
                val threshold = effectiveInterruptThreshold(userThreshold(), isSpeakerEnabled())
                if (detector.onLevel(stt.audioLevel, threshold, SystemClock.elapsedRealtime())) {
                    onBargeIn()
                    break
                }
            }
        }
    }

    fun stop() {
        bargeInJob?.cancel(); bargeInJob = null
        protectJob?.cancel(); protectJob = null
        detector.reset()
    }

    /** Reset only the energy detector's history (call-state reset). */
    fun resetDetector() = detector.reset()

    private companion object {
        const val TAG = "VoiceCallBargeIn"
        const val BARGE_IN_TICK_MS = 50L     // iOS interruptionTimer 0.05 s
        const val MONITOR_RETRY_MS = 150L
        const val MONITOR_MAX_RETRY = 3
    }
}
