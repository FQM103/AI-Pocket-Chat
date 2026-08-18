package com.situ.aichat.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Hex audio decode (1:1 iOS `HexDataDecoder`). Asserts reverse-derived from the iOS contract:
 * strip whitespace → empty/odd-length/invalid-char → null; otherwise two-hex-chars-per-byte.
 */
class HexDataDecoderTest {

    @Test
    fun `decodes lowercase hex pairs`() {
        assertArrayEquals(
            byteArrayOf(0xff.toByte(), 0x5b, 0x00, 0xa3.toByte()),
            HexDataDecoder.dataFromHexString("ff5b00a3"),
        )
    }

    @Test
    fun `decodes mixed case`() {
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x5B), HexDataDecoder.dataFromHexString("Ff5b"))
    }

    @Test
    fun `tolerates interspersed whitespace and newlines`() {
        assertArrayEquals(
            byteArrayOf(0xff.toByte(), 0x5b, 0x00),
            HexDataDecoder.dataFromHexString("ff 5b\n00\t"),
        )
    }

    @Test
    fun `odd length returns null`() {
        assertNull(HexDataDecoder.dataFromHexString("abc"))
    }

    @Test
    fun `empty and whitespace-only return null`() {
        assertNull(HexDataDecoder.dataFromHexString(""))
        assertNull(HexDataDecoder.dataFromHexString("   \n "))
    }

    @Test
    fun `invalid hex char returns null`() {
        assertNull(HexDataDecoder.dataFromHexString("zz"))
        assertNull(HexDataDecoder.dataFromHexString("ffzg"))
    }
}
