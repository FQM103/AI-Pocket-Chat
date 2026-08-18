package com.situ.aichat.tts

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import kotlin.math.max

/**
 * Splits an AI voice reply into duration-bounded chunks, preferring whole sentences and never cutting
 * mid-sentence in the normal case (1:1 iOS `Utilities/VoiceResponseChunker.swift`). This is the CHAT
 * voice path (≤55s preferred / ≤60s hard), NOT the call pipeline. Pure + deterministic; the per-
 * character duration estimate mirrors iOS exactly (emoji detected via ICU `EMOJI_PRESENTATION`, the
 * same Unicode property iOS reads through `Unicode.Scalar.Properties.isEmojiPresentation`).
 */
object VoiceResponseChunker {

    private val sentenceDelimiters: Set<Char> = setOf(
        '。', '！', '？', '.', '!', '?', '；', ';', '…', '～', '~', '—', '\n',
    )
    private val clauseDelimiters: Set<Char> = setOf('，', ',', '、', '：', ':')
    private val fallbackBreakTokens = listOf(
        "但是", "不过", "因为", "所以", "然后", "而且",
        "可是", "如果", "并且", "同时", "然后再", "but ",
        "and ", "so ", "because ", "then ", "however ",
    )

    private const val STRONG_PAUSE = 0.58
    private const val WEAK_PAUSE = 0.22
    private const val CHINESE_CHAR = 0.30
    private const val LATIN_CHAR = 0.07
    private const val DIGIT_CHAR = 0.09
    private const val EMOJI = 0.20
    private const val SAFETY_MULTIPLIER = 1.08

    fun chunkForVoice(
        text: String,
        preferredMaxDuration: Double = 55.0,
        hardMaxDuration: Double = 60.0,
    ): List<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()

        val sentences = splitIntoSentences(normalized)
        val units = sentences.flatMap { expandOversizedUnit(it, preferredMaxDuration, hardMaxDuration) }
        return packUnits(units, preferredMaxDuration, hardMaxDuration)
    }

    /** Per-character speech-duration estimate (seconds), ×1.08 safety, min 1s. Reverse-checkable from iOS. */
    fun estimatedSpeechDuration(text: String): Double {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0.0

        var duration = 0.0
        var i = 0
        while (i < trimmed.length) {
            val cp = trimmed.codePointAt(i)
            i += Character.charCount(cp)

            if (Character.isWhitespace(cp)) continue
            if (UCharacter.hasBinaryProperty(cp, UProperty.EMOJI_PRESENTATION)) {
                duration += EMOJI
                continue
            }
            duration += when (cp.toChar()) {
                '。', '！', '？', '.', '!', '?', '；', ';', '…', '～', '~', '—' -> STRONG_PAUSE
                '，', ',', '、', '：', ':' -> WEAK_PAUSE
                else -> when {
                    cp in '0'.code..'9'.code -> DIGIT_CHAR
                    isLatin(cp) -> LATIN_CHAR
                    else -> CHINESE_CHAR
                }
            }
        }
        return max(1.0, duration * SAFETY_MULTIPLIER)
    }

    internal fun splitIntoSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (char in text) {
            current.append(char)
            if (char in sentenceDelimiters) {
                val segment = current.toString().trim()
                if (segment.isNotEmpty()) result.add(segment)
                current.setLength(0)
            }
        }
        val remainder = current.toString().trim()
        if (remainder.isNotEmpty()) result.add(remainder)
        return result.ifEmpty { listOf(text) }
    }

    private fun packUnits(
        units: List<String>,
        preferredMaxDuration: Double,
        hardMaxDuration: Double,
    ): List<String> {
        val results = mutableListOf<String>()
        var current = ""
        for (unit in units) {
            val trimmedUnit = normalize(unit)
            if (trimmedUnit.isEmpty()) continue
            if (current.isEmpty()) {
                current = trimmedUnit
                continue
            }
            val candidate = join(current, trimmedUnit)
            if (estimatedSpeechDuration(candidate) <= preferredMaxDuration) {
                current = candidate
            } else {
                results.add(current)
                current = trimmedUnit
            }
        }
        if (current.isNotEmpty()) results.add(current)

        return results.flatMap {
            if (estimatedSpeechDuration(it) <= hardMaxDuration) listOf(it)
            else splitByFallback(it, preferredMaxDuration, hardMaxDuration)
        }
    }

    private fun expandOversizedUnit(
        text: String,
        preferredMaxDuration: Double,
        hardMaxDuration: Double,
    ): List<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()
        if (estimatedSpeechDuration(normalized) <= hardMaxDuration) return listOf(normalized)

        val clauseParts = splitByDelimiters(normalized, clauseDelimiters)
        if (clauseParts.size > 1) {
            return clauseParts.flatMap { expandOversizedUnit(it, preferredMaxDuration, hardMaxDuration) }
        }
        val tokenParts = splitByFallbackTokens(normalized)
        if (tokenParts.size > 1) {
            return tokenParts.flatMap { expandOversizedUnit(it, preferredMaxDuration, hardMaxDuration) }
        }
        return splitByFallback(normalized, preferredMaxDuration, hardMaxDuration)
    }

    private fun splitByFallback(
        text: String,
        preferredMaxDuration: Double,
        hardMaxDuration: Double,
    ): List<String> {
        val characters = text.toCharArray()
        if (characters.isEmpty()) return emptyList()

        val results = mutableListOf<String>()
        var start = 0
        while (start < characters.size) {
            val current = StringBuilder()
            var lastPreferredBreak: Int? = null
            var index = start
            while (index < characters.size) {
                current.append(characters[index])
                val estimated = estimatedSpeechDuration(current.toString())

                if (isFallbackBreakCharacter(characters[index]) && estimated >= preferredMaxDuration * 0.45) {
                    lastPreferredBreak = index + 1
                }
                if (estimated >= preferredMaxDuration) {
                    val cut = lastPreferredBreak ?: (index + 1)
                    val part = normalize(String(characters, start, cut - start))
                    if (part.isNotEmpty()) results.add(part)
                    start = cut
                    break
                }
                if (estimated >= hardMaxDuration) {
                    val cut = max(start + 1, lastPreferredBreak ?: index)
                    val part = normalize(String(characters, start, cut - start))
                    if (part.isNotEmpty()) results.add(part)
                    start = cut
                    break
                }
                index++
            }
            if (index >= characters.size) {
                val tail = normalize(String(characters, start, characters.size - start))
                if (tail.isNotEmpty()) results.add(tail)
                break
            }
        }
        return results
    }

    private fun splitByDelimiters(text: String, delimiters: Set<Char>): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (char in text) {
            current.append(char)
            if (char in delimiters && current.length >= 4) {
                val part = normalize(current.toString())
                if (part.isNotEmpty()) parts.add(part)
                current.setLength(0)
            }
        }
        val remainder = normalize(current.toString())
        if (remainder.isNotEmpty()) parts.add(remainder)
        return parts
    }

    private fun splitByFallbackTokens(text: String): List<String> {
        for (token in fallbackBreakTokens) {
            val idx = text.indexOf(token)
            if (idx <= 0) continue // not found, or at the very start (lowerBound != startIndex in iOS)
            val left = normalize(text.substring(0, idx))
            val right = normalize(text.substring(idx))
            if (left.isNotEmpty() && right.isNotEmpty()) return listOf(left, right)
        }
        return listOf(text)
    }

    private fun join(lhs: String, rhs: String): String {
        val left = lhs.trim()
        val right = rhs.trim()
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left

        val leftLast = left.last()
        val rightFirst = right.first()
        if (leftLast == '\n') return left + right
        return if (needsWordSpacing(leftLast, rightFirst)) "$left $right" else left + right
    }

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").trim()

    private fun isFallbackBreakCharacter(char: Char): Boolean =
        char in clauseDelimiters || char == ' ' || char == '\n'

    private fun needsWordSpacing(lhs: Char, rhs: Char): Boolean =
        isASCIIWordLike(lhs) && isASCIIWordLike(rhs)

    private fun isASCIIWordLike(char: Char): Boolean =
        char.code < 128 && (char.isLetterOrDigit())

    private fun isLatin(cp: Int): Boolean =
        cp < 128 && Character.isLetter(cp)
}
