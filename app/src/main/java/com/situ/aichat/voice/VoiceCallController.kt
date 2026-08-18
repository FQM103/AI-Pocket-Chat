package com.situ.aichat.voice

import android.os.SystemClock
import android.util.Log
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.stt.SttConstants
import com.situ.aichat.stt.SttEngine
import com.situ.aichat.stt.SttRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One transcript line for the live-subtitle panel; the UI resolves [role] → speaker name. */
data class VoiceTranscriptLine(val role: String, val text: String)

/** Errors surfaced to the call UI; the screen (10.1h) maps each to a localized string. */
enum class VoiceCallError {
    MIC_UNAVAILABLE,   // RECORD_AUDIO denied or mic busy (= iOS voicePermissionDenied / engine failure)
    STT_UNAVAILABLE,   // U6: on-device sherpa recognizer model failed to load → user could never be heard
    INTERRUPTED,       // audio focus lost / backgrounded (= iOS voiceCallInterrupted)
    RESUME_FAILED,     // could not resume listening after an interruption (= iOS voiceCallResumeFailed)
}

/**
 * The voice-call orchestrator — the Android port of iOS `VoiceCallManager`. It owns the 7-state machine
 * ([CallState]), drives the [VoiceCallStt] listening/monitoring front-end + [AudioFocusController] audio
 * session, runs dual-mode barge-in, and handles audio-focus interruptions and foreground recovery.
 *
 * iOS makes this an `@Observable` view model created per call; the Android-idiomatic shape is a
 * `@Singleton` exposing `StateFlow`s, so the call survives the Compose UI being torn down and (from 10.1g)
 * is held alive by a foreground service under HyperOS background limits. One call at a time.
 *
 * **10.1f (done):** the PROCESSING → AI_SPEAKING leg streams the LLM token-by-token via
 * [VoiceCallTurnService] (scene=VOICE_CALL, no tools/vision) into the [VoiceCallTtsPipeline] (synth ≤2
 * concurrent / 30 s, play sequentially) on its own [CallTtsPlayer] (no anti-feedback guard — the call
 * records the mic while the AI speaks). [startAiTurn] /
 * [resumePendingAiPlayback] are now real; the pipeline calls back into [onFirstSentenceReady] /
 * [onTurnCompleted] / [onTurnFailed]. **10.1g** adds the RECORD_AUDIO / FOREGROUND_SERVICE manifest +
 * the service that calls [onAppBackgrounded] / [onAppForegrounded]; **10.1h** the UI + entry. **10.1i**
 * wires transcript persistence + the four post-call rounds via [VoiceCallPersistence]: each turn persists
 * the user utterance (before the LLM fetch) and the AI reply as `isPartOfVoiceCall` plainText, and on
 * hang-up the non-empty transcript is aggregated into one `CALL_RECORD_CARD`.
 */
@Singleton
class VoiceCallController @Inject constructor(
    private val engine: SttEngine,
    recorder: SttRecorder,
    private val audioFocus: AudioFocusController,
    private val settingsRepository: SettingsRepository,
    private val callPlayer: CallTtsPlayer,
    private val turnService: VoiceCallTurnService,
    private val persistence: VoiceCallPersistence,
    private val fallbackVoice: VoiceCallFallbackVoice,
    private val followUpService: VoiceCallFollowUpService,
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val stt = VoiceCallStt(engine, recorder, scope)
    private val pipeline = VoiceCallTtsPipeline(callPlayer)
    private val bargeIn = VoiceCallBargeIn(
        stt = stt,
        scope = scope,
        canMonitor = { _state.value == CallState.AI_SPEAKING && !hasEnded },
        isAiSpeaking = { _state.value == CallState.AI_SPEAKING },
        isSpeakerEnabled = { _isSpeakerEnabled.value },
        userThreshold = { userThreshold },
        onBargeIn = { handleInterruption() },
    )

    init {
        // Pipeline → controller callbacks (= iOS VoiceCallManager+TTS.swift setupTTSPipeline). Wired once;
        // the pipeline is reset per turn but the callbacks are stable.
        pipeline.onFirstSentenceReady = { onFirstSentenceReady() }
        pipeline.onAllSentencesPlayed = { onTurnCompleted(pipeline.committedTranscriptText) }
        pipeline.onAllSentencesFailed = { onTurnFailed(pipeline.committedTranscriptText) }
        pipeline.onSentenceCommitted = { text ->
            committedAiResponse = text
            anyAiAudibleThisCall = true // C6：真出过声（兜底短句不算——它不是对用户的回应）
            _subtitleFallbackActive.value = false // VU2：真出声即撤字幕通话模式（计数不清零→再失声立即重进）
        }
        pipeline.onSentenceFailed = { text -> Log.w(TAG, "voice TTS sentence failed: ${text.take(20)}") }
        // No onBeforePlayback: the comm-device route set by AudioFocusController persists across ExoPlayer
        // sentences (USAGE_VOICE_COMMUNICATION), unlike iOS AVAudioPlayer which needs a per-clip re-apply.
    }

    private val _state = MutableStateFlow(CallState.IDLE)
    val state: StateFlow<CallState> = _state.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _isSpeakerEnabled = MutableStateFlow(false)
    val isSpeakerEnabled: StateFlow<Boolean> = _isSpeakerEnabled.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private val _error = MutableStateFlow<VoiceCallError?>(null)
    val error: StateFlow<VoiceCallError?> = _error.asStateFlow()

    /** Recent (role, text) transcript lines for the live-subtitle panel (= iOS `recentTranscriptLines`). */
    private val transcript = VoiceCallTranscript()
    val recentTranscript: StateFlow<List<VoiceTranscriptLine>> get() = transcript.recent

    // --- Call bookkeeping (= the iOS VoiceCallManager fields) ---
    private var hasEnded = false
    private var conversationUuid: String? = null
    private var characterUuid: String? = null
    private var callStartElapsedMs = 0L
    /** Wall-clock call start (for the call-record `startTime` ISO + duration; elapsed timer drives the UI). */
    private var callStartWallMs = 0L
    private var userThreshold = SttConstants.DEFAULT_INTERRUPT_THRESHOLD
    private var currentTurnUserText = ""
    private var pendingAiResponse: String? = null
    private var committedAiResponse = ""
    private var interruptionPrefixText = ""
    private var hadActiveStream = false
    private var fallbackPlaysThisCall = 0 // C5：每通电话「没听清」兜底最多播两次，防复读机
    private var anyAiAudibleThisCall = false // C6：本通电话 AI 是否真说出过至少一句（圆场触发判据）
    // VU2 失声两级表达（2026-07-12）：本通累计「零句说出口」的 AI 轮数（仿 fallbackPlaysThisCall per-call
    // 口径），经 VM 暴露给通话屏做瞬时短句/自动展开/钉行·角标表达；controller 只计数，不改任何状态机转移。
    private val _ttsTurnFailures = MutableStateFlow(0)
    val ttsTurnFailures: StateFlow<Int> = _ttsTurnFailures.asStateFlow()
    // 字幕通话模式：≥2 轮失声即进（钉行+角标）；下一轮真出声（onSentenceCommitted）即撤，再失声立即重进。
    private val _subtitleFallbackActive = MutableStateFlow(false)
    val subtitleFallbackActive: StateFlow<Boolean> = _subtitleFallbackActive.asStateFlow()
    private val interruption = VoiceCallInterruption()

    // --- Jobs ---
    private var dialJob: Job? = null
    private var endJob: Job? = null
    private var durationJob: Job? = null
    private var audioLevelJob: Job? = null
    private var aiTurnJob: Job? = null
    private var fallbackJob: Job? = null

    // MARK: - Lifecycle

    /** Begin a call for the given conversation/character. No-op unless idle (= iOS `startCall` guard). */
    fun startCall(conversationUuid: String, characterUuid: String) {
        if (!VoiceCallTransitions.canStart(_state.value)) return

        resetCallState()
        this.conversationUuid = conversationUuid
        this.characterUuid = characterUuid
        callStartElapsedMs = SystemClock.elapsedRealtime()
        callStartWallMs = System.currentTimeMillis()
        Log.i(TAG, "startCall conversation=$conversationUuid character=$characterUuid")

        audioFocus.onFocusLost = { onFocusLost() }
        audioFocus.onFocusRegained = { onFocusRegained() }
        if (!audioFocus.acquire()) {
            // Another app holds exclusive audio (rare). Proceed — the focus listener will pause us if the
            // holder actually plays, and the interruption self-heal re-probes on foreground return.
            Log.w(TAG, "audio focus not granted at startCall — continuing")
        }

        startDurationTimer()
        startAudioLevelTimer()
        transition(CallState.DIALING)

        // Read the user's barge-in sensitivity once at the start (= iOS init reading AppSettings).
        scope.launch { userThreshold = settingsRepository.getAppSettings().sanitizedVoiceCallInterruptThreshold }
        // C5：趁网络还好把「没听清」兜底语音按当前音色烘好（拨号 1.5s + 首轮间隙足够；指纹未变=秒退）。
        scope.launch { fallbackVoice.ensureBaked(characterUuid) }

        dialJob = scope.launch {
            delay(DIAL_MS)
            if (VoiceCallTransitions.dialingComplete(_state.value, hasEnded) != CallState.LISTENING) return@launch
            transition(CallState.LISTENING)
            startListening()
        }
    }

    /** Hang up. No-op if not in an endable state (= iOS `endCall` guard). */
    fun endCall() {
        if (!VoiceCallTransitions.canEnd(_state.value, hasEnded)) return
        hasEnded = true
        interruption.clear()

        val wasAiSpeaking = _state.value == CallState.AI_SPEAKING
        val heardText = if (wasAiSpeaking) pipeline.interrupt() else ""

        cancelTurnJobs()
        bargeIn.stop()
        stt.stopListening()
        stt.stopMonitoring()
        pipeline.reset()
        committedAiResponse = ""

        if (heardText.isNotBlank()) {
            transcript.append("assistant", heardText)
        }
        // Persist the interrupted AI text (if any) THEN the aggregate call record, in order, on a fresh
        // coroutine off the about-to-reset call state — 1:1 iOS endCall (saveAIMessage then saveCallRecord).
        // Snapshot transcript/timing now; resetCallState (0.8 s later) won't touch the copy.
        val recordConvoId = conversationUuid
        val recordCharId = characterUuid
        val recordStartWall = callStartWallMs
        val transcriptSnapshot = transcript.snapshot()
        val recordHadTtsFailure = _ttsTurnFailures.value > 0 // VU3：本通有过失声 → 通话卡记录之（自愈显示在读端裁）
        // C6 通话失联圆场：用户说过话、AI 却一句都没能说出口 → 挂断后角色主动在聊天里圆回来
        // （真人心智：电话断了会发「刚信号不好，你说啥？」）。出过声（含被打断说了半句）都不算失联。
        val silentCall = !anyAiAudibleThisCall && transcriptSnapshot.any { it.first == "user" }
        scope.launch {
            if (heardText.isNotBlank() && recordConvoId != null && recordCharId != null) {
                persistence.saveAiMessage(recordConvoId, recordCharId, heardText)
            }
            if (recordConvoId != null) {
                persistence.saveCallRecord(recordConvoId, transcriptSnapshot, recordStartWall, System.currentTimeMillis(), hadTtsFailure = recordHadTtsFailure)
                if (silentCall) followUpService.followUpAfterSilentCall(recordConvoId)
            }
        }

        audioFocus.release()
        _audioLevel.value = 0f
        transition(CallState.ENDING)

        // The UI dismisses 0.8 s after seeing ENDING (= iOS VoiceCallView); reset the singleton to IDLE
        // afterwards so the next call can start.
        durationJob?.cancel(); durationJob = null
        audioLevelJob?.cancel(); audioLevelJob = null
        endJob = scope.launch {
            delay(ENDING_DISMISS_MS)
            transition(CallState.IDLE)
            resetCallState()
            // Free the idle ExoPlayer between calls (decoder + audio track); the next call lazily rebuilds.
            callPlayer.release()
        }
    }

    fun toggleSpeaker() = setSpeakerEnabled(!_isSpeakerEnabled.value)

    fun setSpeakerEnabled(enabled: Boolean) {
        if (_isSpeakerEnabled.value == enabled) return
        _isSpeakerEnabled.value = enabled
        audioFocus.setSpeakerEnabled(enabled)
        // 1:1 iOS `setSpeakerEnabled`: only the route changes. The energy barge-in threshold is read live
        // each tick (so the 0.12 speaker cap takes effect immediately); the recognition-vs-energy mode is
        // NOT switched mid-utterance — it was fixed when monitoring started for this AI turn.
    }

    // MARK: - Listening turn

    private fun startListening(): Boolean {
        // U6：端侧识别模型加载失败时 openStream() 恒返回 null，VoiceCallStt 会「死听」（mic 起得来、却永不出 final
        // 结果），用户一直说 AI 永不回、毫无提示。这里 fail-fast：不进死听、surfac STT_UNAVAILABLE 让 UI 显错；状态保持
        // LISTENING（canEnd 含之）→红色挂断键可用、用户能干净结束。isAvailable 一次加载后稳定，重复检查廉价。
        if (!engine.isAvailable) {
            Log.w(TAG, "STT model unavailable on startListening — cannot recognize user")
            _error.value = VoiceCallError.STT_UNAVAILABLE
            return false
        }
        val started = stt.startListening(
            onVoiceDetected = { onVoiceDetected() },
            onFinalResult = { text -> onFinalResult(text) },
        )
        if (!started) {
            Log.w(TAG, "mic unavailable on startListening")
            _error.value = VoiceCallError.MIC_UNAVAILABLE
        }
        return started
    }

    private fun onVoiceDetected() {
        val next = VoiceCallTransitions.voiceDetected(_state.value) ?: return
        transition(next)
    }

    // internal：T2 行为测试的注入点（真路径要喂完整音频帧才能到这，测试直接注入识别结果）。
    internal fun onFinalResult(rawText: String) {
        if (hasEnded) return
        var normalized = rawText.trim()
        if (interruptionPrefixText.isNotEmpty()) {
            val prefix = interruptionPrefixText
            interruptionPrefixText = ""
            normalized = if (normalized.isEmpty()) prefix else prefix + normalized
        }
        if (normalized.isEmpty()) {
            // Nothing usable — go back to listening (= iOS onFinalResult empty branch), with mic-retry
            // self-heal（C1 假听自愈：任何回听路径都不许「显示在听、实际没启麦」）。
            returnToListeningWithRetry()
            return
        }
        handleUserFinishedSpeaking(normalized)
    }

    private fun handleUserFinishedSpeaking(text: String) {
        if (hasEnded) return
        transition(CallState.PROCESSING)
        stt.stopListening()
        // C2 思考中可打断：PROCESSING 全程保持带识别监听（此刻 AI 未出声、无回授），用户开口即可作废
        // 本轮以新话推进——「正在思考」不再是听不见用户的黑洞。
        startThinkingMonitor()
        transcript.append("user", text)
        currentTurnUserText = text
        pendingAiResponse = null
        committedAiResponse = ""
        // Persist the user utterance on the (never-cancelled) controller scope BEFORE starting the
        // cancellable AI turn — so a hang-up / background in the PROCESSING window can't drop it
        // (= iOS handleUserFinishedSpeaking: saveUserMessage precedes the cancellable streamTask). The
        // persist completes first, then the turn's history fetch naturally includes it. 10.1i.
        val convoId = conversationUuid
        scope.launch {
            if (convoId != null) persistence.saveUserMessage(convoId, text)
            if (!hasEnded) startAiTurn(text)
        }
    }

    // MARK: - AI turn (10.1f seam)

    /**
     * Stream the LLM (scene=VOICE_CALL, no tools/vision) token-by-token into the [pipeline] (split
     * sentences, synth ≤2 concurrent / 30 s, play sequentially) — 1:1 iOS `streamLLMResponse`. The pipeline
     * fires [onFirstSentenceReady] → AI_SPEAKING, [onTurnCompleted] when all play, [onTurnFailed] on synth
     * failure. A config/key/network error drops the turn back to listening ([failTurnToListening]).
     *
     * The user utterance is persisted by the caller BEFORE this runs (normal flow) or was already persisted
     * (foreground-recovery restart), so the history fetch in [VoiceCallTurnService.streamResponse] sees it.
     */
    private fun startAiTurn(userText: String) {
        hadActiveStream = true
        val convoId = conversationUuid
        val charId = characterUuid
        if (convoId == null || charId == null) {
            Log.w(TAG, "startAiTurn without conversation/character")
            failTurnToListening()
            return
        }
        Log.i(TAG, "startAiTurn userText='${userText.take(20)}'")
        aiTurnJob?.cancel()
        aiTurnJob = scope.launch {
            try {
                // Resolve TTS once for the turn (no moodEmoji = call path), arm the pipeline, then stream.
                val synthesize = turnService.resolveSynthesizer(charId)
                pipeline.beginTurn(synthesize)
                val full = turnService.streamResponse(convoId, charId, userText) { token ->
                    pipeline.feedToken(token)
                }
                pendingAiResponse = full
                pipeline.finishFeeding()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "voice LLM turn failed: ${e.message}")
                failTurnToListening()
            }
        }
    }

    /** LLM config/stream failure → drop the turn, play the pre-baked "没听清" clip, return to listening. */
    private fun failTurnToListening() {
        if (hasEnded) return
        pipeline.reset()
        pendingAiResponse = null
        committedAiResponse = ""
        hadActiveStream = false
        bargeIn.stop()
        stt.stopMonitoring()
        playFallbackThenListen()
    }

    /**
     * C5 预烘焙兜底（2026-07-12）：LLM 回合死掉的时刻网络多半也不好——现场合成必然跟着挂，所以播
     * **通话开始时就烘好的本地音频**（角色自己的音色说「刚没听清，你再说一遍好不好？」）。用户的体感
     * 不是「出错了」而是「她没听清」，会自然再说一遍 = 失败被对话流吸收。兜底句是系统代言：不进
     * transcript / 通话卡 / 模型历史。每通电话最多播 [FALLBACK_MAX_PER_CALL] 次（防复读机）；没烘好
     * / 超次 / 播放失败一律静默退化为直接回听——回听自愈（C1）保证用户永远能继续说话推进。
     */
    private fun playFallbackThenListen() {
        val charId = characterUuid
        if (charId == null || fallbackPlaysThisCall >= FALLBACK_MAX_PER_CALL) {
            returnToListeningWithRetry()
            return
        }
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            val clip = fallbackVoice.bakedClipOrNull(charId)
            if (hasEnded) return@launch
            if (clip == null) {
                returnToListeningWithRetry()
                return@launch
            }
            fallbackPlaysThisCall++
            transition(CallState.AI_SPEAKING) // 听感=TA 在说话；短句不 arm 打断（2-3s 窗不值竞态成本）
            try {
                callPlayer.play(clip)
            } catch (e: CancellationException) {
                throw e // 挂断/来电取消：交由取消方（endCall/pauseFor）决定去向
            } catch (e: Exception) {
                Log.w(TAG, "fallback clip playback failed: ${e.message}")
            }
            if (!hasEnded) returnToListeningWithRetry()
        }
    }

    /** Called by the 10.1f pipeline when the first sentence is ready to play (= iOS onFirstSentenceReady). */
    internal fun onFirstSentenceReady() {
        if (hasEnded) return
        // C2：思考监听在保护窗内彻底闭麦（AI 开口的声音不进识别流），0.5s 后 barge-in 按路由规则重启监听。
        stt.stopMonitoring()
        transition(CallState.AI_SPEAKING)
        bargeIn.armAfterProtectionWindow()
    }

    /**
     * Called by the 10.1f pipeline when the whole response finished playing OR synthesis/playback failed —
     * the handling was byte-identical (= iOS onAllSentencesPlayed / onAllSentencesFailed): persist whatever
     * was spoken (final text, else the last committed sentence), then return to listening.
     */
    internal fun onTurnCompleted(finalText: String) = finishAiTurn(finalText)

    internal fun onTurnFailed(partialText: String) = finishAiTurn(partialText)

    private fun finishAiTurn(text: String) {
        if (hasEnded) return
        bargeIn.stop()
        stt.stopMonitoring()
        // VU2 失声计数（2026-07-12）：这一轮「零句说出口」（committed 双空、却有整段回复要回落）= 一次失声。
        // 判据与下方 C4 回落链同源（text/committed/pending 三元组）；≥2 轮进字幕通话模式（active）。
        val fellBackToText = text.isBlank() && committedAiResponse.isBlank() && pendingAiResponse.orEmpty().isNotBlank()
        if (fellBackToText) {
            _ttsTurnFailures.value += 1
            if (_ttsTurnFailures.value >= 2) _subtitleFallbackActive.value = true
        }
        // C4 TTS 全失败不丢话（2026-07-12）：一句都没能说出口（committed 双空）时回落整段已生成回复——
        // 进字幕面板 + 通话记录卡 + 模型历史，绝不把到手的回复静默扔掉；用户听不到也看得到 TA 说了什么。
        // 正常播完/半途失败仍以「实际说出的部分」为准（未播尾巴照旧丢弃 = iOS 语义）。
        val aiText = text.ifBlank { committedAiResponse }.ifBlank { pendingAiResponse.orEmpty() }
        if (aiText.isNotBlank()) {
            transcript.append("assistant", aiText)
            saveAiMessage(aiText) // 10.1i
        }
        pendingAiResponse = null
        committedAiResponse = ""
        hadActiveStream = false
        returnToListeningWithRetry()
    }

    /**
     * Replay a complete [pendingAiResponse] that was generated but never spoken (= iOS
     * `resumePendingAIPlayback`): re-arm the pipeline and feed the cached (already-sanitized) text as one
     * shot. Empty → just resume listening.
     */
    private fun resumePendingAiPlayback() {
        if (hasEnded) return
        val response = pendingAiResponse?.trim().orEmpty()
        val charId = characterUuid
        if (response.isEmpty() || charId == null) {
            returnToListeningWithRetry()
            return
        }
        transition(CallState.PROCESSING)
        startThinkingMonitor() // C2：重播等待窗同样可被用户开口打断
        pendingAiResponse = response
        committedAiResponse = ""
        hadActiveStream = false
        aiTurnJob?.cancel()
        aiTurnJob = scope.launch {
            val synthesize = turnService.resolveSynthesizer(charId)
            pipeline.beginTurn(synthesize)
            pipeline.feedToken(response)
            pipeline.finishFeeding()
        }
    }

    // MARK: - Thinking-stage barge-in (C2 思考中可打断 2026-07-12·超越 iOS)

    /**
     * Keep a recognition monitor running through PROCESSING so the user can always push the call forward
     * by just speaking（非附和 ≥0.3s 触发，同 barge-in 门槛）。AI 未出声 → 无回授，两种扬声路由都安全带识别。
     * 启动失败重试一次后放弃（退化为旧行为：思考中听不见——不阻塞主流程）。
     */
    private fun startThinkingMonitor(attempt: Int = 0) {
        if (hasEnded || _state.value != CallState.PROCESSING) return
        val started = stt.startMonitoring(withRecognition = true) { onThinkingInterruption() }
        if (!started && attempt < THINKING_MONITOR_MAX_RETRY) {
            scope.launch {
                delay(THINKING_MONITOR_RETRY_MS)
                startThinkingMonitor(attempt + 1)
            }
        }
    }

    /**
     * User spoke while the AI was thinking → the pending turn is stale by intent: cancel it, discard any
     * in-flight synthesis, carry the already-recognized words into the next turn, and hand the floor back
     * to the user（真人心智：对方还没接话你又开了口，他会基于新话回应）。internal：T2 注入点。
     */
    internal fun onThinkingInterruption() {
        if (hasEnded || VoiceCallTransitions.thinkingBargeIn(_state.value) == null) return
        aiTurnJob?.cancel(); aiTurnJob = null
        pipeline.reset()
        // 旧轮回复整体作废（用户主动推进 = 不想再听旧答案），与 saveAiMessage 的「已说出即不重播」同一防线。
        pendingAiResponse = null
        committedAiResponse = ""
        hadActiveStream = false
        interruptionPrefixText = stt.monitoringRecognizedText
        stt.stopMonitoring()
        if (startListening()) {
            transition(CallState.USER_SPEAKING)
        } else {
            returnToListeningWithRetry()
        }
    }

    // MARK: - Barge-in (dual mode — monitoring lives in [VoiceCallBargeIn])

    /** User barged in (energy or recognition). 1:1 iOS `handleInterruption`. internal：T2 回归钉注入点。 */
    internal fun handleInterruption() {
        if (VoiceCallTransitions.bargeIn(_state.value) == null) return // only while AI_SPEAKING
        bargeIn.stop()

        val heardText = pipeline.interrupt()
        aiTurnJob?.cancel(); aiTurnJob = null
        hadActiveStream = false

        if (heardText.isNotBlank()) {
            transcript.append("assistant", heardText)
            saveAiMessage(heardText) // 10.1i
            committedAiResponse = ""
        } else {
            pendingAiResponse = null
            committedAiResponse = ""
        }

        // Carry the speech recognized during monitoring into the next turn.
        interruptionPrefixText = stt.monitoringRecognizedText
        stt.stopMonitoring()
        // C1 假听自愈：barge-in 后麦克风重启失败绝不能停在「假 USER_SPEAKING」——退回带重试的回听。
        if (startListening()) {
            transition(CallState.USER_SPEAKING)
        } else {
            returnToListeningWithRetry()
        }
    }

    // MARK: - Audio interruption / recovery (统一双路 2026-07-11 修缮)
    // A single pause/resume mechanism behind BOTH the audio-focus and the app-background events (an
    // incoming phone call raises both). [VoiceCallInterruption] guarantees one teardown + one recovery
    // dispatch; the recovery ACTION semantics are unchanged (resolveForegroundRecoveryAction, 11 例锁定).

    private fun onFocusLost() = pauseFor(CallPauseReason.FOCUS_LOSS)

    private fun onFocusRegained() = resumeFrom(CallPauseReason.FOCUS_LOSS)

    /** Call-screen lifecycle hook (10.1h): host activity stopped — 1:1 iOS `handleAppDidEnterBackground`. */
    fun onAppBackgrounded() = pauseFor(CallPauseReason.BACKGROUND)

    /** Call-screen lifecycle hook (10.1h): host activity started — 1:1 iOS `handleAppWillEnterForeground`. */
    fun onAppForegrounded() = resumeFrom(CallPauseReason.BACKGROUND)

    private fun pauseFor(reason: CallPauseReason) {
        if (!VoiceCallTransitions.shouldPauseForInterruption(_state.value, hasEnded)) return
        if (!interruption.beginPause(reason)) return // already paused — only remember the extra reason
        val previousState = _state.value
        val streamWasActive = hadActiveStream

        bargeIn.stop()
        aiTurnJob?.cancel(); aiTurnJob = null
        fallbackJob?.cancel(); fallbackJob = null // C5：来电/后台时兜底短句直接砍掉，恢复动作照旧结算
        val heardText = pipeline.interrupt()
        pipeline.reset()
        stt.stopListening()
        stt.stopMonitoring()
        _audioLevel.value = 0f

        interruption.pendingAction = resolveForegroundRecoveryAction(
            callState = previousState,
            hadActiveStream = streamWasActive,
            pendingAiResponse = pendingAiResponse,
            heardText = heardText,
        )
        if (heardText.isNotBlank()) {
            transcript.append("assistant", heardText)
            saveAiMessage(heardText) // 10.1i
            committedAiResponse = ""
        } else if (interruption.pendingAction == ForegroundRecoveryAction.RESUME_LISTENING) {
            pendingAiResponse = null
            committedAiResponse = ""
        }
        transition(CallState.PROCESSING)
        _error.value = VoiceCallError.INTERRUPTED
    }

    private fun resumeFrom(reason: CallPauseReason) {
        if (hasEnded) return
        val action = interruption.endPause(reason) { audioFocus.acquire() } ?: return
        audioFocus.acquire() // re-arm mode + route (idempotent; the self-heal probe may have done it already)
        _error.value = null
        when (action) {
            ForegroundRecoveryAction.RESUME_LISTENING -> returnToListeningWithRetry()
            ForegroundRecoveryAction.RESTART_LLM_TURN -> {
                transition(CallState.PROCESSING)
                startThinkingMonitor() // C2：恢复重发的思考窗同样可打断
                // The user utterance was already persisted when the turn first started → don't re-persist.
                startAiTurn(currentTurnUserText)
            }
            ForegroundRecoveryAction.RESUME_PENDING_PLAYBACK -> resumePendingAiPlayback()
        }
    }

    /**
     * The ONE way back to LISTENING（C1 假听自愈·2026-07-12）：every return-to-listening path —
     * normal turn end, turn failure, empty recognition, barge-in mic restart failure, interruption
     * recovery — funnels here so a failed mic start always self-retries (up to [RESUME_MAX_RETRY] ×
     * [RESUME_RETRY_MS]) instead of leaving a deaf call that still claims to be listening. Exhausted
     * retries end the call visibly（RESUME_FAILED）—— a dead call beats a fake-listening one.
     */
    private fun returnToListeningWithRetry(attempt: Int = 0) {
        if (hasEnded) return
        transition(CallState.LISTENING)
        if (startListening()) {
            _error.value = null
            return
        }
        if (attempt >= RESUME_MAX_RETRY) {
            _error.value = VoiceCallError.RESUME_FAILED
            endCall()
            return
        }
        scope.launch {
            delay(RESUME_RETRY_MS)
            returnToListeningWithRetry(attempt + 1)
        }
    }

    // MARK: - Timers + transition

    private fun startDurationTimer() {
        durationJob?.cancel()
        durationJob = scope.launch {
            while (isActive) {
                _callDurationSeconds.value = (SystemClock.elapsedRealtime() - callStartElapsedMs) / 1000L
                delay(DURATION_TICK_MS)
            }
        }
    }

    private fun startAudioLevelTimer() {
        audioLevelJob?.cancel()
        audioLevelJob = scope.launch {
            while (isActive) {
                _audioLevel.value = when (_state.value) {
                    CallState.LISTENING, CallState.USER_SPEAKING -> stt.audioLevel
                    CallState.AI_SPEAKING -> callPlayer.audioLevel
                    else -> 0f
                }
                delay(AUDIO_LEVEL_TICK_MS)
            }
        }
    }

    /** Dedup + log + (10.1g) foreground-notification sync — 1:1 iOS `transition(to:)`. */
    private fun transition(to: CallState) {
        if (_state.value == to) return
        _state.value = to
        Log.i(TAG, "state → $to")
    }

    private fun cancelTurnJobs() {
        dialJob?.cancel(); dialJob = null
        aiTurnJob?.cancel(); aiTurnJob = null
        fallbackJob?.cancel(); fallbackJob = null
    }

    private fun resetCallState() {
        hasEnded = false
        conversationUuid = null // endCall snapshots before this runs; stale ids must not leak into IDLE
        characterUuid = null
        callStartElapsedMs = 0L
        callStartWallMs = 0L
        currentTurnUserText = ""
        pendingAiResponse = null
        committedAiResponse = ""
        interruptionPrefixText = ""
        hadActiveStream = false
        fallbackPlaysThisCall = 0
        anyAiAudibleThisCall = false
        _ttsTurnFailures.value = 0 // VU2：新通话失声计数归零
        _subtitleFallbackActive.value = false
        interruption.clear()
        transcript.clear()
        _isSpeakerEnabled.value = false
        _callDurationSeconds.value = 0L
        _audioLevel.value = 0f
        _error.value = null
        bargeIn.resetDetector()
    }

    // MARK: - Persistence (10.1i — delegates to VoiceCallPersistence)

    /**
     * Persist an AI message off the call flow (fire-and-forget) — 1:1 iOS `saveAIMessage` (sanitize + insert
     * `isPartOfVoiceCall` plainText + preview + embed + the four post-call rounds). The in-memory transcript
     * is updated by the caller; this only writes to the DB. (endCall persists with explicit ordering instead.)
     */
    private fun saveAiMessage(text: String) {
        val convoId = conversationUuid ?: return
        val charId = characterUuid ?: return
        // 已说出的内容一旦落库，整段回复就绝不再重播（= iOS saveAIMessage 内 pendingAIResponse = nil）。
        // T5 复核 R-1：漏掉这行时，「打断过一次 → 之后连续两次被中断」链会把陈旧 pendingAiResponse
        // 结算成 RESUME_PENDING_PLAYBACK，把用户亲手打断的整段回复从第一句重播。
        pendingAiResponse = null
        scope.launch { persistence.saveAiMessage(convoId, charId, text) }
    }

    private companion object {
        const val TAG = "VoiceCallController"
        const val DIAL_MS = 1500L            // iOS dialing wait (VoiceCallManager.swift:178)
        const val ENDING_DISMISS_MS = 800L   // iOS ending → dismiss (VoiceCallView.swift:112-118)
        const val DURATION_TICK_MS = 1000L
        const val AUDIO_LEVEL_TICK_MS = 50L
        const val RESUME_RETRY_MS = 400L
        const val RESUME_MAX_RETRY = 2
        const val THINKING_MONITOR_RETRY_MS = 150L // C2 思考监听麦启失败的轻重试（对齐 barge-in 的 150ms 节奏）
        const val THINKING_MONITOR_MAX_RETRY = 1
        const val FALLBACK_MAX_PER_CALL = 2 // C5 每通电话兜底句上限（第 3 次失败起静默回听，别当复读机）
    }
}
