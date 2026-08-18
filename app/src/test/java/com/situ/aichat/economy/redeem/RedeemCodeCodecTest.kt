package com.situ.aichat.economy.redeem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RedeemCodeCodec] 单测——断言反推 iOS RedeemCodeCodec：编解码往返 / 验签 / 容错归一 / codeHash 稳定 / 过期换算。
 * 用确定性测试 secret（32 字节），不依赖生产 RedeemCodeSecret。
 */
class RedeemCodeCodecTest {

    private val secret = ByteArray(32) { (it * 7 + 1).toByte() }
    private val tier = RedeemCodeCodec.AmountTier.ONE_THOUSAND
    private fun payload(serial: Int = 12345, expiryDay: Int = 400) =
        RedeemCodeCodec.Payload(tier, expiryDay, serial)

    @Test fun encodeDecode_roundTrip() {
        val p = payload()
        val code = RedeemCodeCodec.encode(p, secret)
        assertTrue("格式 AIC-XXXXX-XXXXX-XXXXX", code.matches(Regex("^AIC-[0-9A-Z]{5}-[0-9A-Z]{5}-[0-9A-Z]{5}$")))
        val decoded = RedeemCodeCodec.decode(code, secret)
        assertEquals(p, decoded)
        assertEquals(1000, decoded.coins)
    }

    @Test fun allTiers_roundTrip() {
        for (t in RedeemCodeCodec.AmountTier.entries) {
            val p = RedeemCodeCodec.Payload(t, expiryDay = 1000, serial = 7)
            val decoded = RedeemCodeCodec.decode(RedeemCodeCodec.encode(p, secret), secret)
            assertEquals(t, decoded.tier)
            assertEquals(t.coins, decoded.coins)
        }
    }

    @Test fun maxFields_roundTrip() {
        // serial 19bit max 524287, expiryDay 14bit max 16383
        val p = RedeemCodeCodec.Payload(RedeemCodeCodec.AmountTier.FIFTY, expiryDay = 16383, serial = 524287)
        assertEquals(p, RedeemCodeCodec.decode(RedeemCodeCodec.encode(p, secret), secret))
    }

    @Test fun wrongSecret_invalidSignature() {
        val code = RedeemCodeCodec.encode(payload(), secret)
        val otherSecret = ByteArray(32) { (it + 99).toByte() }
        val e = runCatching { RedeemCodeCodec.decode(code, otherSecret) }.exceptionOrNull()
        assertTrue(e is RedeemCodeCodec.CodecError.InvalidSignature)
    }

    @Test fun tamperedChar_invalidSignature() {
        val code = RedeemCodeCodec.encode(payload(), secret)
        // 改最后一组首字符（在 Crockford 字母表内换一个），破坏 HMAC
        val chars = code.toCharArray()
        val idx = code.lastIndexOf('-') + 1
        chars[idx] = if (chars[idx] == '0') '1' else '0'
        val e = runCatching { RedeemCodeCodec.decode(String(chars), secret) }.exceptionOrNull()
        assertTrue(e is RedeemCodeCodec.CodecError.InvalidSignature)
    }

    @Test fun normalize_tolerantVariants_decodeSame() {
        val code = RedeemCodeCodec.encode(payload(), secret) // e.g. AIC-ABCDE-...
        val normalized = RedeemCodeCodec.normalize(code)!!
        // 小写 + 去连字符 + 去 AIC 前缀 都应归一到同一串
        assertEquals(normalized, RedeemCodeCodec.normalize(code.lowercase()))
        assertEquals(normalized, RedeemCodeCodec.normalize(code.replace("-", "")))
        assertEquals(normalized, RedeemCodeCodec.normalize(code.removePrefix("AIC-")))
        assertEquals(normalized, RedeemCodeCodec.normalize("  $code  "))
    }

    @Test fun normalize_oilFolding() {
        // O→0, I→1, L→1：OIL 重复 5 次（15 长）归一为 011 重复
        assertEquals("011011011011011", RedeemCodeCodec.normalize("OILOILOILOILOIL"))
    }

    @Test fun normalize_wrongLength_null() {
        assertNull(RedeemCodeCodec.normalize("AIC-ABC"))
        assertNull(RedeemCodeCodec.normalize(""))
        assertNull(RedeemCodeCodec.normalize("0123456789ABCDEF")) // 16 chars
    }

    @Test fun decode_illegalChar_invalidFormat() {
        // 'U' 不在 Crockford 字母表，归一后长度 15 但 crockfordValue 返回 null
        val e = runCatching { RedeemCodeCodec.decode("AIC-UUUUU-UUUUU-UUUUU", secret) }.exceptionOrNull()
        assertTrue(e is RedeemCodeCodec.CodecError.InvalidFormat)
    }

    @Test fun codeHash_stableAcrossVariants_32hex() {
        val code = RedeemCodeCodec.encode(payload(), secret)
        val h1 = RedeemCodeCodec.codeHash(RedeemCodeCodec.normalize(code)!!)
        val h2 = RedeemCodeCodec.codeHash(RedeemCodeCodec.normalize(code.lowercase())!!)
        assertEquals(h1, h2)
        assertEquals(32, h1.length)
        assertTrue(h1.matches(Regex("^[0-9a-f]{32}$")))
    }

    @Test fun expiryDate_isEndOfDayUtc() {
        val p = RedeemCodeCodec.Payload(tier, expiryDay = 0, serial = 0)
        // epoch(2025-01-01 00:00:00Z) + 86399s = 2025-01-01 23:59:59Z
        assertEquals(RedeemCodeCodec.EPOCH_MILLIS + 86_399_000L, p.expiryDateMillis)
    }

    @Test fun expiryDayFromMillis_roundTrip_andClamp() {
        val day = 500
        val millis = RedeemCodeCodec.EPOCH_MILLIS + day * 86_400_000L + 3_600_000L // +1h into the day
        assertEquals(day, RedeemCodeCodec.expiryDayFromMillis(millis))
        assertEquals(0, RedeemCodeCodec.expiryDayFromMillis(RedeemCodeCodec.EPOCH_MILLIS - 999_999L)) // 负→0
        assertEquals(16383, RedeemCodeCodec.expiryDayFromMillis(RedeemCodeCodec.EPOCH_MILLIS + 99_999L * 86_400_000L)) // 上限
    }

    @Test fun computeHmac_is40bit() {
        val h = RedeemCodeCodec.computeHmac(payload(), secret)
        assertTrue(h in 0..0xFF_FFFF_FFFFL)
        assertNotNull(h)
    }

    /**
     * 跨语言 parity：发码工具 `tools/generate_codes.py` 用**同一确定性 secret**（本测试的 [secret]）产出的码，
     * 必须与本 codec encode 逐字一致——证明工具生成的码 app 一定解得了、兑得了。下列向量由实际运行的
     * generate_codes.py 得到（`encode_code(tier, expiryDay, serial, secret)`），固化为回归锚点防两端漂移。
     */
    @Test fun pythonGeneratorParity_knownVectors() {
        // tier=ONE_THOUSAND(3), expiryDay=400, serial=12345（= payload() 默认）
        assertEquals("AIC-R680C-1SCMD-QRW7T", RedeemCodeCodec.encode(payload(), secret))
        assertEquals(434252837114L, RedeemCodeCodec.computeHmac(payload(), secret))
        // tier=FIFTY(0), expiryDay=0, serial=0（全零边界）
        assertEquals(
            "AIC-00000-0076T-XTFBY",
            RedeemCodeCodec.encode(RedeemCodeCodec.Payload(RedeemCodeCodec.AmountTier.FIFTY, expiryDay = 0, serial = 0), secret),
        )
        // 工具发的码 → app 解出原 payload（端到端往返）
        assertEquals(payload(), RedeemCodeCodec.decode("AIC-R680C-1SCMD-QRW7T", secret))
    }
}
