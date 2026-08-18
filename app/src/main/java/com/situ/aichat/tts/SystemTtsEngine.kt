package com.situ.aichat.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A selectable on-device system voice (Android [Voice]) for the system-TTS picker (1:1 iOS `availableChineseVoices`). */
data class SystemVoiceOption(
    /** Stable identifier = [Voice.getName] (what gets stored as the character/global `voiceIdentifier`). */
    val id: String,
    /** Human label = the voice name (engines rarely give a friendlier display name). */
    val name: String,
    /** Android [Voice.getQuality] bucket (VERY_LOW 100 … VERY_HIGH 500); the UI maps it to a label. */
    val quality: Int,
    val localeTag: String,
)

/**
 * On-device system TTS byte synthesis (1:1 iOS `TTSService+SystemTTS.swift`). Wraps Android
 * [TextToSpeech.synthesizeToFile] → a WAV file → bytes, with rate = engine default, pitch = 1.0, a
 * 10s timeout, and zh-CN as the default voice. The HyperOS system engine quality varies (the TTS
 * settings UI recommends MiniMax instead — a deliberate iOS divergence), so any init/synthesis
 * failure degrades gracefully to `null` (callers fall back to a toast / text delivery).
 *
 * The [TextToSpeech] engine is expensive to create, so it's initialized once and reused (a singleton);
 * synthesis is serialized with a mutex since one engine handles one utterance at a time.
 */
@Singleton
class SystemTtsEngine @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var engine: TextToSpeech? = null

    /** In-flight utterance result, keyed by id; only one at a time thanks to [synthMutex]. */
    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null

    @Volatile
    private var pendingId: String? = null

    private val initMutex = Mutex()
    private val synthMutex = Mutex()

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            if (utteranceId == pendingId) pending?.complete(true)
        }

        // Kotlin 2.2 新增 OVERRIDE_DEPRECATION 诊断：@Deprecated 本身不再消音（覆写框架已弃用的单参 onError）。
        @Deprecated("Deprecated in Java")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            if (utteranceId == pendingId) pending?.complete(false)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (utteranceId == pendingId) pending?.complete(false)
        }
    }

    /** Lazily create + initialize the engine once. Returns null if the device has no usable TTS engine. */
    private suspend fun ensureEngine(): TextToSpeech? {
        engine?.let { return it }
        return initMutex.withLock {
            engine?.let { return@withLock it }
            val ready = CompletableDeferred<Boolean>()
            val tts = TextToSpeech(appContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
            val ok = withTimeoutOrNull(ENGINE_INIT_TIMEOUT_MS) { ready.await() } ?: false
            if (!ok) {
                Log.w(TAG, "system TTS engine init failed/timed out")
                runCatching { tts.shutdown() }
                return@withLock null
            }
            tts.setOnUtteranceProgressListener(progressListener)
            engine = tts
            tts
        }
    }

    /**
     * Synthesize [text] to WAV bytes (1:1 iOS `synthesizeSystem`). Picks [voiceIdentifier] when set;
     * otherwise falls back to the zh-CN default only if [allowDefaultVoice] (chat path passes false so
     * an unconfigured character stays silent; preview passes true). Returns null on any failure.
     */
    suspend fun synthesize(text: String, voiceIdentifier: String, allowDefaultVoice: Boolean): ByteArray? {
        if (text.isBlank()) return null
        val tts = ensureEngine() ?: return null
        return synthMutex.withLock { runSynthesis(tts, text, voiceIdentifier, allowDefaultVoice) }
    }

    private suspend fun runSynthesis(
        tts: TextToSpeech,
        text: String,
        voiceIdentifier: String,
        allowDefaultVoice: Boolean,
    ): ByteArray? {
        // Voice selection mirrors iOS: explicit identifier → that voice; else default zh-CN; else nothing.
        val trimmedId = voiceIdentifier.trim()
        if (trimmedId.isNotEmpty()) {
            val match = runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == trimmedId }
            if (match != null) {
                if (tts.setVoice(match) == TextToSpeech.ERROR) {
                    Log.w(TAG, "系统 TTS 合成失败·setVoice 失败 voice=$trimmedId")
                    return null
                }
            } else if (allowDefaultVoice) {
                if (tts.setLanguage(Locale.SIMPLIFIED_CHINESE) == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "系统 TTS 合成失败·语言不支持(zh-CN) voice=$trimmedId")
                    return null
                }
            } else {
                return null
            }
        } else if (allowDefaultVoice) {
            if (tts.setLanguage(Locale.SIMPLIFIED_CHINESE) == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "系统 TTS 合成失败·语言不支持(zh-CN) voice=默认zh-CN")
                return null
            }
        } else {
            return null
        }

        tts.setSpeechRate(1.0f) // engine default rate (= iOS AVSpeechUtteranceDefaultSpeechRate)
        tts.setPitch(1.0f)      // = iOS pitchMultiplier 1.0

        val id = UUID.randomUUID().toString()
        val outFile = File(appContext.cacheDir, "sys_tts_$id.wav")
        val done = CompletableDeferred<Boolean>()
        pending = done
        pendingId = id

        Log.d(TAG, "system TTS synth start: len=${text.length} voice=${trimmedId.ifEmpty { "默认zh-CN" }}")

        val queued = runCatching { tts.synthesizeToFile(text, Bundle(), outFile, id) }.getOrDefault(TextToSpeech.ERROR)
        if (queued != TextToSpeech.SUCCESS) {
            Log.w(TAG, "系统 TTS 合成失败·合成写文件失败 rc=$queued voice=${trimmedId.ifEmpty { "默认zh-CN" }}")
            cleanup(outFile)
            return null
        }

        val ok = withTimeoutOrNull(SYNTH_TIMEOUT_MS) { done.await() } ?: run {
            Log.w(TAG, "system TTS synth timed out (10s)")
            false
        }
        pending = null
        pendingId = null

        if (!ok) {
            cleanup(outFile)
            return null
        }
        return withContext(Dispatchers.IO) {
            val bytes = runCatching { outFile.takeIf { it.exists() && it.length() > 0 }?.readBytes() }.getOrNull()
            cleanup(outFile)
            bytes
        }
    }

    /** Enumerate installed Chinese system voices, best quality first (1:1 iOS `availableChineseVoices`). */
    suspend fun availableChineseVoices(): List<SystemVoiceOption> {
        val tts = ensureEngine() ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                tts.voices.orEmpty()
                    .filter { it.locale.language.startsWith("zh", ignoreCase = true) }
                    .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
                    .sortedByDescending { it.quality }
                    .map { v ->
                        SystemVoiceOption(
                            id = v.name,
                            name = v.name,
                            quality = v.quality,
                            localeTag = v.locale.toLanguageTag(),
                        )
                    }
            }.getOrDefault(emptyList())
        }
    }

    private fun cleanup(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    private companion object {
        const val TAG = "SystemTtsEngine"
        const val ENGINE_INIT_TIMEOUT_MS = 5_000L
        const val SYNTH_TIMEOUT_MS = 10_000L // 1:1 iOS 10s timeout
    }
}
