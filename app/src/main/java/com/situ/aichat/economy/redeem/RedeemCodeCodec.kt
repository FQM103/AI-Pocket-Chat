package com.situ.aichat.economy.redeem

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 兑换码编解码器（14.6c·无状态纯函数·1:1 iOS `RedeemCodeCodec`）。GMS-free（仅用 JDK 标准 JCE：HmacSHA256 / SHA-256）。
 *
 * ## 格式 `AIC-XXXXX-XXXXX-XXXXX`（15 字符 Crockford Base32 = 75 bit）
 * 位布局（高位在前）：`[ tier(2) | expiryDay(14) | serial(19) | hmac(40) ]`
 * - tier：金额档 0-3 → 50/200/500/1000 金币
 * - expiryDay：自 2025-01-01 UTC 起的天数
 * - serial：序号 0..524287
 * - hmac：HMAC-SHA256(secret, tier‖expiryDay‖serial) 前 40 bit
 *
 * 输入容错：大小写无关 / 连字符可省 / `AIC` 前缀可省 / `O→0`、`I/L→1`（Crockford）。`U` 等非字母表字符判非法。
 * 75 bit 用 [BigInteger] 装载（Kotlin 无 UInt128），逐码 5-bit 拼接；与 iOS UInt128 移位逐位等价。
 */
object RedeemCodeCodec {

    /** 金额档（4 档·1:1 iOS AmountTier）。 */
    enum class AmountTier(val raw: Int, val coins: Int) {
        FIFTY(0, 50),
        TWO_HUNDRED(1, 200),
        FIVE_HUNDRED(2, 500),
        ONE_THOUSAND(3, 1000);

        companion object {
            fun fromRaw(raw: Int): AmountTier? = entries.firstOrNull { it.raw == raw }
        }
    }

    data class Payload(val tier: AmountTier, val expiryDay: Int, val serial: Int) {
        val coins: Int get() = tier.coins

        /** 过期时刻毫秒：当天 UTC 23:59:59（epoch + expiryDay 天 + 86399 秒·1:1 iOS expiryDate）。 */
        val expiryDateMillis: Long get() = EPOCH_MILLIS + expiryDay.toLong() * 86_400_000L + 86_399_000L
    }

    sealed class CodecError : Exception() {
        /** 长度不对 / 含非法字符。 */
        object InvalidFormat : CodecError()
        /** HMAC 不匹配 → 伪造或 secret 不一致。 */
        object InvalidSignature : CodecError()
    }

    /** 兑换码 epoch：2025-01-01 00:00:00 UTC。 */
    const val EPOCH_MILLIS = 1_735_689_600_000L

    private const val MASK19 = 0x7FFFF
    private const val MASK14 = 0x3FFF
    private val MASK40: BigInteger = BigInteger.ONE.shiftLeft(40).subtract(BigInteger.ONE)
    private val crockfordAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray()

    // MARK: - Encoding（dev 生成码 / 单测用）

    fun encode(payload: Payload, secret: ByteArray): String {
        val hmac = computeHmac(payload, secret)
        var bits = BigInteger.valueOf(payload.tier.raw.toLong())
        bits = bits.shiftLeft(14).or(BigInteger.valueOf(payload.expiryDay.toLong()))
        bits = bits.shiftLeft(19).or(BigInteger.valueOf(payload.serial.toLong()))
        bits = bits.shiftLeft(40).or(BigInteger.valueOf(hmac))

        val chars = CharArray(15)
        for (i in 0 until 15) {
            val shift = (14 - i) * 5
            val chunk = bits.shiftRight(shift).and(BigInteger.valueOf(0x1F)).toInt()
            chars[i] = crockfordAlphabet[chunk]
        }
        val s = String(chars)
        return "AIC-${s.substring(0, 5)}-${s.substring(5, 10)}-${s.substring(10, 15)}"
    }

    // MARK: - Decoding

    /** 解码 + 验签。失败抛 [CodecError]（不区分语义；服务层再包装为用户错误）。 */
    fun decode(rawCode: String, secret: ByteArray): Payload {
        val normalized = normalize(rawCode) ?: throw CodecError.InvalidFormat

        var bits = BigInteger.ZERO
        for (c in normalized) {
            val v = crockfordValue(c) ?: throw CodecError.InvalidFormat
            bits = bits.shiftLeft(5).or(BigInteger.valueOf(v.toLong()))
        }

        val hmacRecv = bits.and(MASK40).toLong()
        val serial = bits.shiftRight(40).toInt() and MASK19
        val expiryDay = bits.shiftRight(59).toInt() and MASK14
        val tierRaw = bits.shiftRight(73).toInt() and 0x3

        val tier = AmountTier.fromRaw(tierRaw) ?: throw CodecError.InvalidFormat
        val payload = Payload(tier, expiryDay, serial)
        if (hmacRecv != computeHmac(payload, secret)) throw CodecError.InvalidSignature
        return payload
    }

    // MARK: - Normalization

    /**
     * 标准化为 15 字符 Crockford Base32（1:1 iOS normalize）：trim → 大写 → 去 `-`/空格 → 剥 `AIC` 前缀 →
     * O→0 / I,L→1。剥前缀必须在大写后、OIL 替换前（否则 I→1 破坏「AIC」匹配）。长度≠15 返回 null。
     */
    fun normalize(raw: String): String? {
        val stripped = raw.trim().uppercase().replace("-", "").replace(" ", "")
        val withoutPrefix = if (stripped.startsWith("AIC")) stripped.drop(3) else stripped
        val sb = StringBuilder(withoutPrefix.length)
        for (c in withoutPrefix) {
            when (c) {
                'O' -> sb.append('0')
                'I', 'L' -> sb.append('1')
                else -> sb.append(c)
            }
        }
        return if (sb.length == 15) sb.toString() else null
    }

    // MARK: - Hashing

    /** SHA-256 截断 hex（前 32 字符）作 codeHash·须传已 normalize 的串（1:1 iOS codeHash）。 */
    fun codeHash(normalized: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) hex.append("%02x".format(b.toInt() and 0xFF))
        return hex.substring(0, 32)
    }

    // MARK: - Date <-> expiryDay

    /** Date(millis) → expiryDay（自 epoch 整数天·UTC 截断·clamp[0,16383]·1:1 iOS expiryDay(from:)）。 */
    fun expiryDayFromMillis(millis: Long): Int {
        val days = ((millis - EPOCH_MILLIS) / 86_400_000L).toInt()
        return days.coerceIn(0, 16383)
    }

    // MARK: - HMAC

    /** HMAC-SHA256 截前 40 bit（5 字节大端 → Long·1:1 iOS computeHMAC）。 */
    fun computeHmac(payload: Payload, secret: ByteArray): Long {
        val input = byteArrayOf(
            payload.tier.raw.toByte(),
            ((payload.expiryDay shr 8) and 0xFF).toByte(),
            (payload.expiryDay and 0xFF).toByte(),
            ((payload.serial shr 16) and 0xFF).toByte(),
            ((payload.serial shr 8) and 0xFF).toByte(),
            (payload.serial and 0xFF).toByte(),
        )
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val out = mac.doFinal(input)
        var result = 0L
        for (i in 0 until 5) result = (result shl 8) or (out[i].toLong() and 0xFF)
        return result
    }

    private fun crockfordValue(c: Char): Int? {
        val idx = crockfordAlphabet.indexOf(c)
        return if (idx >= 0) idx else null
    }
}
