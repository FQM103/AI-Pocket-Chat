package com.situ.aichat.stt

/**
 * Minimal PCM-16 WAV → float[-1,1] decoder for feeding [SttEngine.transcribe]. Walks the RIFF chunks so it
 * tolerates `LIST`/`INFO` chunks between `fmt ` and `data` (the sherpa test clips have them); only 16-bit
 * PCM is supported (what both the recorder and the bundled clips produce). For multi-channel input it keeps
 * channel 0. Returns null if the bytes are not a usable 16-bit PCM WAV.
 *
 * Pure (no Android/JVM-only APIs) so the parsing is unit-testable on the JVM; the voice-message path and the
 * on-device STT smoke test both decode through here.
 */
internal fun decodeWavPcm16ToFloat(bytes: ByteArray): FloatArray? {
    if (bytes.size < 44) return null

    fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
    fun u16(offset: Int) = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    fun u32(offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    if (tag(0) != "RIFF" || tag(8) != "WAVE") return null

    var channels = 1
    var bitsPerSample = 16
    var dataOffset = -1
    var dataLength = 0

    var pos = 12
    while (pos + 8 <= bytes.size) {
        val id = tag(pos)
        val size = u32(pos + 4)
        val body = pos + 8
        when (id) {
            "fmt " -> if (body + 16 <= bytes.size) {
                channels = u16(body + 2).coerceAtLeast(1)
                bitsPerSample = u16(body + 14)
            }
            "data" -> {
                dataOffset = body
                dataLength = size.coerceAtMost(bytes.size - body)
            }
        }
        if (dataOffset >= 0) break
        pos = body + size + (size and 1) // chunks are word-aligned
    }

    if (dataOffset < 0 || bitsPerSample != 16) return null

    val frameBytes = 2 * channels
    if (frameBytes <= 0) return null
    val frames = dataLength / frameBytes
    return FloatArray(frames) { i ->
        val o = dataOffset + i * frameBytes // channel 0 of frame i
        // signed little-endian 16-bit: low byte unsigned, high byte sign-extended
        val sample = (bytes[o].toInt() and 0xFF) or (bytes[o + 1].toInt() shl 8)
        sample / 32768f
    }
}

/**
 * Mono PCM-16 samples → canonical 44-byte RIFF/WAVE bytes (16-bit signed LE, little-endian header).
 * Mirror of what the iOS `AVAudioRecorder` writes for a voice message (16 kHz / mono / 16-bit PCM WAV);
 * the Android MIC recorder captures PCM-16 shorts then wraps them here for [com.situ.aichat.util.AudioStore].
 * Pure (no Android APIs) so it is JVM-unit-testable and round-trips through [decodeWavPcm16ToFloat].
 */
internal fun encodeWavPcm16(samples: ShortArray, sampleRate: Int): ByteArray {
    val channels = 1
    val bitsPerSample = 16
    val blockAlign = channels * bitsPerSample / 8
    val byteRate = sampleRate * blockAlign
    val dataSize = samples.size * 2
    val out = ByteArray(44 + dataSize)

    fun ascii(offset: Int, s: String) { for (i in s.indices) out[offset + i] = s[i].code.toByte() }
    fun u32(offset: Int, v: Int) {
        out[offset] = (v and 0xFF).toByte()
        out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }
    fun u16(offset: Int, v: Int) {
        out[offset] = (v and 0xFF).toByte()
        out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    ascii(0, "RIFF"); u32(4, 36 + dataSize); ascii(8, "WAVE")
    ascii(12, "fmt "); u32(16, 16); u16(20, 1 /* PCM */); u16(22, channels)
    u32(24, sampleRate); u32(28, byteRate); u16(32, blockAlign); u16(34, bitsPerSample)
    ascii(36, "data"); u32(40, dataSize)

    var o = 44
    for (s in samples) {
        val v = s.toInt()
        out[o] = (v and 0xFF).toByte()
        out[o + 1] = ((v shr 8) and 0xFF).toByte()
        o += 2
    }
    return out
}

/** PCM-16 shorts → float[-1,1] for feeding [SttEngine.transcribe] (no WAV round-trip needed). Pure. */
internal fun pcm16ToFloat(samples: ShortArray): FloatArray = FloatArray(samples.size) { samples[it] / 32768f }
