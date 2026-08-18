package com.situ.aichat.tts

/**
 * Decodes the hex-string audio in a MiniMax T2A v2 response (`data.audio`) into bytes.
 * 1:1 with iOS `HexDataDecoder` (Utilities/HexDataDecoder.swift).
 *
 * MiniMax embeds the audio as "two hex chars per byte", e.g. "ff5b00a3…" → [0xff,0x5b,0x00,0xa3,…].
 * Tolerant of mixed case and interspersed whitespace/newlines (long responses sometimes wrap).
 * Any failure returns null; the caller falls back (throws invalidResponse / shows a toast).
 */
object HexDataDecoder {

    fun dataFromHexString(hex: String): ByteArray? {
        // Strip all whitespace/newlines first (tolerate servers inserting line breaks in long bodies).
        val cleaned = buildString(hex.length) {
            for (c in hex) if (!c.isWhitespace()) append(c)
        }
        if (cleaned.isEmpty()) return null
        if (cleaned.length % 2 != 0) return null

        val out = ByteArray(cleaned.length / 2)
        var i = 0
        while (i < cleaned.length) {
            val hi = Character.digit(cleaned[i], 16)
            val lo = Character.digit(cleaned[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }
}
