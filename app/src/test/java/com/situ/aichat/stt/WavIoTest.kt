package com.situ.aichat.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Pure WAV-decode tests: PCM-16 LE → float[-1,1], chunk-walking tolerance, malformed rejection. */
class WavIoTest {

    private fun wav(samples: ShortArray, rate: Int = 16000, extraChunk: Pair<String, ByteArray>? = null): ByteArray {
        val out = ByteArrayOutputStream()
        fun tag(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
        fun u32(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF); out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF) }

        val dataLen = samples.size * 2
        val extraLen = extraChunk?.let { 8 + it.second.size } ?: 0
        tag("RIFF"); u32(4 + (8 + 16) + extraLen + (8 + dataLen)); tag("WAVE")
        tag("fmt "); u32(16); u16(1); u16(1); u32(rate); u32(rate * 2); u16(2); u16(16)
        extraChunk?.let { (id, body) -> tag(id); u32(body.size); out.write(body) }
        tag("data"); u32(dataLen)
        for (s in samples) u16(s.toInt() and 0xFFFF)
        return out.toByteArray()
    }

    @Test fun `decodes pcm16 mono to float`() {
        val f = decodeWavPcm16ToFloat(wav(shortArrayOf(0, 16384, -16384, 32767, -32768)))!!
        assertEquals(5, f.size)
        assertEquals(0f, f[0], 1e-6f)
        assertEquals(0.5f, f[1], 1e-4f)    // 16384/32768
        assertEquals(-0.5f, f[2], 1e-4f)
        assertEquals(32767f / 32768f, f[3], 1e-4f)
        assertEquals(-1.0f, f[4], 1e-6f)   // -32768/32768
    }

    @Test fun `walks past a LIST chunk between fmt and data`() {
        // sherpa test clips carry a LIST/INFO chunk before data — must still decode.
        val f = decodeWavPcm16ToFloat(wav(shortArrayOf(100, -100), extraChunk = "LIST" to "INFOxx".toByteArray()))!!
        assertEquals(2, f.size)
        assertEquals(100f / 32768f, f[0], 1e-6f)
        assertEquals(-100f / 32768f, f[1], 1e-6f)
    }

    @Test fun `decodes a longer buffer without overrun`() {
        assertNotNull(decodeWavPcm16ToFloat(wav(ShortArray(1600) { (it % 200 - 100).toShort() })))
    }

    @Test fun `rejects non-wav bytes`() {
        assertNull(decodeWavPcm16ToFloat(ByteArray(10)))
        assertNull(decodeWavPcm16ToFloat("NOTAWAVEINHERE!!".toByteArray()))
    }

    // P13.4b：MIC 录音器产出 PCM-16 → encodeWavPcm16 包成 WAV，须能被 decode 原样还原。

    @Test fun `encodes a canonical 44-byte header`() {
        val bytes = encodeWavPcm16(shortArrayOf(0, 1, 2), sampleRate = 16000)
        assertEquals(44 + 6, bytes.size) // header + 3 samples * 2B
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        // sampleRate little-endian at offset 24
        val rate = (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or
            ((bytes[26].toInt() and 0xFF) shl 16) or ((bytes[27].toInt() and 0xFF) shl 24)
        assertEquals(16000, rate)
    }

    @Test fun `encode then decode round-trips the samples`() {
        val samples = shortArrayOf(0, 16384, -16384, 32767, -32768)
        val f = decodeWavPcm16ToFloat(encodeWavPcm16(samples, sampleRate = 16000))!!
        assertEquals(5, f.size)
        assertEquals(0f, f[0], 1e-6f)
        assertEquals(0.5f, f[1], 1e-4f)
        assertEquals(-0.5f, f[2], 1e-4f)
        assertEquals(32767f / 32768f, f[3], 1e-4f)
        assertEquals(-1.0f, f[4], 1e-6f)
    }

    @Test fun `pcm16ToFloat matches the wav decode scale`() {
        val f = pcm16ToFloat(shortArrayOf(0, 16384, -32768))
        assertEquals(0f, f[0], 1e-6f)
        assertEquals(0.5f, f[1], 1e-4f)
        assertEquals(-1.0f, f[2], 1e-6f)
    }
}
