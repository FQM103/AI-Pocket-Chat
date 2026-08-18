package com.situ.aichat.stt

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * sherpa-onnx implementation of [SttEngine] — on-device, offline, key-less. Loads the streaming
 * `multi-zh-hans` zipformer transducer (int8) from `assets/models/stt` once, lazily, and shares it across
 * the whole-utterance [transcribe] path (voice messages) and the [openStream] path (voice call).
 *
 * Graceful degradation mirrors the bge embedder (TextEmbedder): if the native lib or model assets are
 * missing / unloadable, [isAvailable] is false and callers no-op rather than crash.
 *
 * Endpointing is intentionally NOT delegated to sherpa's native endpoint detector ([enableEndpoint] off) —
 * the iOS-faithful 1.2 s trailing-silence rule lives in [SilenceTracker] (pure, testable), driven by the
 * call state machine in 10.1e.
 */
@Singleton
class SherpaSttEngine @Inject constructor(
    @ApplicationContext context: Context,
) : SttEngine {

    private val appContext = context.applicationContext

    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var loadFailed = false
    private val loadLock = Any()

    /** Guards the shared recognizer + its streams: sherpa decode/reset/getResult are not concurrency-safe. */
    private val decodeLock = Any()

    override val isAvailable: Boolean
        get() = ensureLoaded() != null

    override suspend fun transcribe(samples: FloatArray): String? {
        val recognizer = ensureLoaded() ?: return null
        return withContext(Dispatchers.Default) {
            synchronized(decodeLock) {
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, SttConstants.SAMPLE_RATE)
                    // A tail of silence flushes the last sub-word — sherpa needs trailing frames to finalize.
                    stream.acceptWaveform(FloatArray(SttConstants.SAMPLE_RATE / 2), SttConstants.SAMPLE_RATE)
                    stream.inputFinished()
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }
        }
    }

    override fun openStream(): SttStream? {
        val recognizer = ensureLoaded() ?: return null
        val stream = synchronized(decodeLock) { recognizer.createStream() }
        return SherpaSttStream(recognizer, stream, decodeLock)
    }

    private fun ensureLoaded(): OnlineRecognizer? {
        recognizer?.let { return it }
        if (loadFailed) return null
        synchronized(loadLock) {
            recognizer?.let { return it }
            if (loadFailed) return null
            try {
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SttConstants.SAMPLE_RATE, featureDim = 80, dither = 0f),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "$MODEL_DIR/encoder.int8.onnx",
                            decoder = "$MODEL_DIR/decoder.onnx",
                            joiner = "$MODEL_DIR/joiner.int8.onnx",
                        ),
                        tokens = "$MODEL_DIR/tokens.txt",
                        numThreads = 2,
                        provider = "cpu",
                    ),
                    endpointConfig = EndpointConfig(),
                    enableEndpoint = false,
                )
                recognizer = OnlineRecognizer(appContext.assets, config)
                Log.i(TAG, "sherpa STT loaded (multi-zh-hans, on-device)")
            } catch (t: Throwable) {
                loadFailed = true
                Log.w(TAG, "STT unavailable — voice recognition disabled: ${t.message}")
            }
        }
        return recognizer
    }

    /** A single streaming session. All native calls go through the engine's [decodeLock]. */
    private class SherpaSttStream(
        private val recognizer: OnlineRecognizer,
        private val stream: OnlineStream,
        private val decodeLock: Any,
    ) : SttStream {
        @Volatile private var closed = false

        override fun accept(samples: FloatArray) {
            if (closed) return
            synchronized(decodeLock) {
                stream.acceptWaveform(samples, SttConstants.SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
            }
        }

        override fun decodedText(): String {
            if (closed) return ""
            return synchronized(decodeLock) { recognizer.getResult(stream).text }.trim()
        }

        override fun reset() {
            if (closed) return
            synchronized(decodeLock) { recognizer.reset(stream) }
        }

        override fun close() {
            if (closed) return
            closed = true
            synchronized(decodeLock) { stream.release() }
        }
    }

    private companion object {
        const val TAG = "SttEngine"
        const val MODEL_DIR = "models/stt"
    }
}
