package com.situ.aichat.prompt

/**
 * 1:1 port of iOS `MessageSplitter`. Splits a full LLM reply into multiple natural short messages to
 * mimic a real person texting (方案 M04 分句多气泡), and exposes the `isMetaLine` / `isQuoteOnlyLine`
 * line filters reused by [ReplyParser.sanitizeAssistantResponse].
 *
 * Length thresholds count UTF-16 chars (Kotlin `String.length`); Chinese is BMP (1 unit/char) so this
 * matches the iOS grapheme count for typical chat text.
 */
object MessageSplitter {

    private const val SECONDARY_SPLIT_THRESHOLD = 26
    private const val MINIMUM_SECONDARY_CHUNK_LENGTH = 8
    private const val MERGE_SHORT_SEGMENT_THRESHOLD = 6
    private const val PREFERRED_SEGMENT_LENGTH = 18
    private const val MAXIMUM_MERGED_SEGMENT_LENGTH = 26

    private val primaryDelimiters = setOf('。', '！', '？', '.', '!', '?', '；', '…', '\n')
    private val secondaryDelimiters = setOf('，', ',', '、', '：', ':')
    private const val PUNCTUATION_AND_QUOTE_SET = "，,。！？；;….!?\"'“”‘’「」『』"
    // 批2 2-1（2026-07-02 过审）：裸子串整行删除只保留「日常聊天几乎不可能自然出现」的整短语。
    // 旧列表含「在线/镜头/旁白/背景音/轻碰/脆响/输入中」等日常词——"我们在线下见吧"（含"在线"）、
    // "新买的镜头"这类正常回复会被整行静默吃掉。日常词降级为结构化判定：整行被括号包裹（舞台指示）
    // 或行首标签式（"旁白："）才删；"在线" 彻底移除（"在线等"是真实网络用语，误删伤害 > 漏过）。
    private val bareMetaKeywords = listOf("发来一条语音", "语音背景", "系统提示")
    private val narrationSignalKeywords = listOf(
        "发来一条语音", "语音背景", "背景音", "镜头", "旁白",
        "系统提示", "动作描写", "轻碰", "脆响", "输入中",
    )
    private val narrationLabelRegex = Regex("""^(旁白|画外音|背景音|镜头|系统提示|动作描写)\s*[:：]""")

    private data class InternalSegment(val text: String, val isCard: Boolean)

    /**
     * 将完整文本拆分为多条短消息。
     *
     * **源头分段权威化（2026-07-08 拍板·契约 REVERSE_LIST §9 V10）**：提示词早已要求模型「用空行/换行分条」
     * （`pb_chatfmt_segments` + 短句口吻 `pb_style_l3`），模型给出的分段**照单全收**——空行优先作消息边界
     * （块内单换行保留为气泡内换行），无空行则每行一条；只保留元信息行过滤 + 卡片行独立 + maxSegments
     * 安全钳（超出按最短相邻合并），**不再**逗号二次切（>26 字）/ 短段强合 / 凑 minSegments 强拆——
     * 这三样正是「拆出来的句子不自然”的来源。模型没分段（无任何换行或只得 1 段）→ 走旧算法兜底。
     *
     * @param maxSegments 最多拆分条数（0 = 不限制，卡片段不计入）
     * @param minSegments 最少拆分条数（0 = 不限制，卡片段不计入；权威模式下不凑数）
     */
    fun split(text: String, maxSegments: Int = 0, minSegments: Int = 0): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        trustedSegments(trimmed)?.let { trusted ->
            var segments = trusted
            if (maxSegments > 0) segments = mergeNonCardsToFit(segments, maxSegments).toMutableList()
            for (idx in segments.indices) {
                if (!segments[idx].isCard) {
                    segments[idx] = InternalSegment(stripTrailingConnectorPunctuation(segments[idx].text), false)
                }
            }
            val result = segments.map { it.text }.filter { it.isNotEmpty() }
            if (result.isNotEmpty()) return result
        }

        val blocks = extractBlocks(trimmed)

        var segments = mutableListOf<InternalSegment>()
        for (block in blocks) {
            if (block.isCard) {
                segments.add(InternalSegment(block.text, true))
            } else {
                for (sub in splitNonCardText(block.text)) {
                    segments.add(InternalSegment(sub, false))
                }
            }
        }

        if (segments.isEmpty()) return listOf(trimmed)

        if (maxSegments > 0) segments = mergeNonCardsToFit(segments, maxSegments).toMutableList()
        if (minSegments > 0) segments = splitNonCardsToFit(segments, minSegments).toMutableList()

        for (idx in segments.indices) {
            if (!segments[idx].isCard) {
                segments[idx] = InternalSegment(stripTrailingConnectorPunctuation(segments[idx].text), false)
            }
        }

        val result = segments.map { it.text }.filter { it.isNotEmpty() }
        return result.ifEmpty { listOf(trimmed) }
    }

    /**
     * 源头分段（权威模式）：按模型自己的换行结构切段——含空行按空行（块内单换行保留），否则按单换行逐行。
     * 卡片行恒独立成段；元信息行照旧过滤；纯标点段（如「??」的兄弟「!!!」）回贴上一条文本段（同旧算法口径,
     * 不丢字）。切出 <2 段视为「模型没分段」→ 返回 null 走旧算法。
     */
    private fun trustedSegments(text: String): MutableList<InternalSegment>? {
        if (!text.contains('\n')) return null
        val parts = if (text.contains("\n\n")) text.split(Regex("\n{2,}")) else text.split('\n')
        val segments = mutableListOf<InternalSegment>()
        fun addText(raw: String) {
            val cleaned = filterMetaLines(listOf(raw)).firstOrNull() ?: run {
                // 元信息过滤把整段清空:若原文是纯标点(texting 短反应残端),回贴到上一条文本段,与旧算法同口径。
                val t = raw.trim()
                if (t.isNotEmpty() && isQuoteOnlyLine(t)) {
                    val lastTextIdx = segments.indexOfLast { !it.isCard }
                    if (lastTextIdx >= 0) segments[lastTextIdx] = InternalSegment(segments[lastTextIdx].text + t, false)
                }
                return
            }
            segments.add(InternalSegment(cleaned, false))
        }
        for (part in parts) {
            val textLines = mutableListOf<String>()
            for (line in part.split('\n')) {
                if (isCardLine(line)) {
                    if (textLines.isNotEmpty()) { addText(textLines.joinToString("\n")); textLines.clear() }
                    segments.add(InternalSegment(line.trim(), true))
                } else if (line.isNotBlank()) {
                    textLines.add(line.trim())
                }
            }
            if (textLines.isNotEmpty()) addText(textLines.joinToString("\n"))
        }
        return if (segments.size >= 2) segments else null
    }

    private fun splitNonCardText(text: String): List<String> {
        var segments: List<String>
        if (text.contains("\n\n")) {
            val rawSegments = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
            segments = rawSegments.flatMap { segment ->
                if (visibleLength(segment) > 80 && !containsStickerTag(segment)) splitByPunctuation(segment)
                else listOf(segment)
            }
        } else {
            segments = splitByPunctuation(text)
        }
        segments = mergeShortSegments(segments)
        segments = filterMetaLines(segments)
        return segments
    }

    private fun splitByPunctuation(text: String): List<String> {
        val primarySegments = mutableListOf<String>()
        val current = StringBuilder()
        for (char in text) {
            current.append(char)
            if (primaryDelimiters.contains(char)) {
                val segment = current.toString().trim()
                if (segment.isNotEmpty()) primarySegments.add(segment)
                current.clear()
            }
        }
        val remainder = current.toString().trim()
        if (remainder.isNotEmpty()) primarySegments.add(remainder)

        val segments = mutableListOf<String>()
        for (part in primarySegments) {
            if (part.length > SECONDARY_SPLIT_THRESHOLD && !containsCalendarRef(part) && !containsStickerTag(part)) {
                val subCurrent = StringBuilder()
                for (char in part) {
                    subCurrent.append(char)
                    if (secondaryDelimiters.contains(char) && subCurrent.length >= MINIMUM_SECONDARY_CHUNK_LENGTH) {
                        val sub = subCurrent.toString().trim()
                        if (sub.isNotEmpty()) segments.add(sub)
                        subCurrent.clear()
                    }
                }
                val subRemainder = subCurrent.toString().trim()
                if (subRemainder.isNotEmpty()) segments.add(subRemainder)
            } else {
                segments.add(part)
            }
        }

        val refined = mutableListOf<String>()
        for (segment in segments) {
            val trimmedSegment = segment.trim()
            val punctuationOnly = trimmedSegment.all { PUNCTUATION_AND_QUOTE_SET.contains(it) }
            if (punctuationOnly && refined.isNotEmpty()) {
                refined[refined.size - 1] = refined.last() + trimmedSegment
                continue
            }
            refined.add(trimmedSegment)
        }
        return refined
    }

    fun isQuoteOnlyLine(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        return trimmed.all { PUNCTUATION_AND_QUOTE_SET.contains(it) }
    }

    fun isMetaLine(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        if (isTruncatedBracketFragment(trimmed)) return true
        if (bareMetaKeywords.any { trimmed.contains(it) }) return true
        if (narrationLabelRegex.containsMatchIn(trimmed)) return true
        if (hasTimeNarrationPattern(trimmed)) return true
        if (hasBracketedTimestamp(trimmed)) return true
        if (isParenthesized(trimmed) && hasNarrationSignal(trimmed)) return true
        if (isDSMLResidue(trimmed)) return true
        return false
    }

    private fun isTruncatedBracketFragment(text: String): Boolean {
        if (text.length > 30) return false
        val startsWithBracket = text.startsWith("[") || text.startsWith("【")
        if (!startsWithBracket) return false
        val hasClosing = text.contains("]") || text.contains("】")
        return !hasClosing
    }

    // MARK: - 缓存正则

    private val leadingMetaRegexes: List<Regex> = listOf(
        Regex("""^\s*[（(]\s*(凌晨|清晨|早上|上午|中午|下午|傍晚|晚上|深夜)[^）)]{0,20}\d{1,2}[:：]\d{2}\s*[）)]\s*"""),
        Regex("""^\s*[（(]\s*[^）)]{0,40}(发来一条语音|语音背景|旁白|系统提示|动作描写)[^）)]{0,40}\s*[）)]\s*"""),
    )
    private val bracketedTimestampRegex =
        Regex("""^\[?\d{4}(?:年\d{1,2}月\d{1,2}日|-\d{1,2}-\d{1,2})\s*\d{1,2}[:：]\d{2}\]?$""")
    private val timeNarrationRegex =
        Regex("""(凌晨|清晨|早上|上午|中午|下午|傍晚|晚上|深夜).{0,12}\d{1,2}[:：]\d{2}""")

    private fun stripLeadingMetaPrefix(text: String): String {
        var result = text.trim()
        for (regex in leadingMetaRegexes) {
            val match = regex.find(result)
            if (match != null) {
                result = result.removeRange(match.range).trim()
            }
        }
        return result
    }

    private fun hasBracketedTimestamp(text: String): Boolean = bracketedTimestampRegex.containsMatchIn(text)
    private fun hasTimeNarrationPattern(text: String): Boolean = timeNarrationRegex.containsMatchIn(text)

    private fun isParenthesized(text: String): Boolean =
        (text.startsWith("（") && text.endsWith("）")) || (text.startsWith("(") && text.endsWith(")"))

    private fun hasNarrationSignal(text: String): Boolean {
        if (hasTimeNarrationPattern(text)) return true
        return narrationSignalKeywords.any { text.contains(it) }
    }

    private fun mergeShortSegments(segments: List<String>): List<String> {
        if (segments.isEmpty()) return emptyList()
        val merged = mutableListOf<String>()
        for (segment in segments) {
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) continue

            val last = merged.lastOrNull()
            if (last != null) {
                val lastLength = visibleLength(last)
                val currentLength = visibleLength(trimmed)
                val shouldMerge = currentLength <= MERGE_SHORT_SEGMENT_THRESHOLD ||
                    (lastLength < PREFERRED_SEGMENT_LENGTH &&
                        currentLength < PREFERRED_SEGMENT_LENGTH &&
                        lastLength + currentLength <= MAXIMUM_MERGED_SEGMENT_LENGTH)
                if (shouldMerge) {
                    merged[merged.size - 1] = last + trimmed
                    continue
                }
            }
            merged.add(trimmed)
        }
        return merged
    }

    private fun filterMetaLines(segments: List<String>): List<String> {
        val cleaned = mutableListOf<String>()
        for (segment in segments) {
            if (segment.isEmpty()) continue
            if (isQuoteOnlyLine(segment)) continue
            if (isMetaLine(segment)) continue

            val finalSegment = stripLeadingMetaPrefix(segment).trim()
            if (finalSegment.isEmpty()) continue
            if (isQuoteOnlyLine(finalSegment)) continue
            if (isMetaLine(finalSegment)) continue
            cleaned.add(finalSegment)
        }
        return cleaned
    }

    private fun visibleLength(text: String): Int = text.trim().length

    private fun isDSMLResidue(text: String): Boolean {
        if (text.contains("DSML")) return true
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val snakeCaseOnly = trimmed.all { it.isLetter() || it == '_' || it == '#' }
        if (snakeCaseOnly && trimmed.contains("_")) return true
        return false
    }

    private fun containsCalendarRef(text: String): Boolean = text.contains("[#E") || text.contains("[#R")

    // MARK: - 卡片行预抽取

    private val cardLineRegex = Regex("""\[#[ER]\d+\]""")

    private fun isCardLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        return cardLineRegex.containsMatchIn(trimmed)
    }

    private fun extractBlocks(text: String): List<InternalSegment> {
        val lines = text.split("\n")
        val blocks = mutableListOf<InternalSegment>()
        val cardLines = mutableListOf<String>()
        val textLines = mutableListOf<String>()

        fun flushCards() {
            if (cardLines.isEmpty()) return
            val joined = cardLines.joinToString("\n").trim()
            if (joined.isNotEmpty()) blocks.add(InternalSegment(joined, true))
            cardLines.clear()
        }

        fun flushText() {
            if (textLines.isEmpty()) return
            val joined = textLines.joinToString("\n").trim()
            if (joined.isNotEmpty()) blocks.add(InternalSegment(joined, false))
            textLines.clear()
        }

        for (line in lines) {
            if (isCardLine(line)) {
                flushText()
                cardLines.add(line)
            } else {
                flushCards()
                textLines.add(line)
            }
        }
        flushCards()
        flushText()
        return blocks
    }

    private fun containsStickerTag(text: String): Boolean = text.contains("[sticker:")

    // MARK: - 条数范围调整

    private val anyPunctuation = setOf(
        '。', '！', '？', '.', '!', '?', '…', '~', '～',
        '，', ',', '、', '；', '：', ':',
    )
    private val trailingConnectorPunctuation = setOf('，', ',', '、', '；', ';', '：', ':', '。', '.')

    private fun stripTrailingConnectorPunctuation(text: String): String {
        var result = text
        while (result.isNotEmpty()) {
            val last = result.last()
            if (last.isWhitespace()) {
                result = result.dropLast(1)
                continue
            }
            if (trailingConnectorPunctuation.contains(last)) {
                if (last == '.') {
                    val trailingDots = result.reversed().takeWhile { it == '.' }.count()
                    if (trailingDots >= 3) break
                }
                result = result.dropLast(1)
                continue
            }
            break
        }
        return result
    }

    private fun mergeNonCardsToFit(segments: List<InternalSegment>, target: Int): List<InternalSegment> {
        if (target < 1) return segments
        val result = segments.toMutableList()
        var nonCardCount = result.count { !it.isCard }
        if (nonCardCount <= target) return result

        while (nonCardCount > target) {
            var bestIndex = -1
            var bestLength = Int.MAX_VALUE
            for (i in 0 until result.size - 1) {
                if (result[i].isCard || result[i + 1].isCard) continue
                val combined = visibleLength(result[i].text) + visibleLength(result[i + 1].text)
                if (combined < bestLength) {
                    bestLength = combined
                    bestIndex = i
                }
            }
            if (bestIndex < 0) break
            val joined = joinSegmentsNaturally(result[bestIndex].text, result[bestIndex + 1].text)
            result[bestIndex] = InternalSegment(joined, false)
            result.removeAt(bestIndex + 1)
            nonCardCount -= 1
        }
        return result
    }

    private fun joinSegmentsNaturally(first: String, second: String): String {
        val trimmedFirst = first.trim()
        val lastChar = trimmedFirst.lastOrNull() ?: return second
        if (anyPunctuation.contains(lastChar)) return trimmedFirst + second
        return trimmedFirst + "，" + second
    }

    private fun splitNonCardsToFit(segments: List<InternalSegment>, target: Int): List<InternalSegment> {
        if (target < 1) return segments
        val result = segments.toMutableList()
        var nonCardCount = result.count { !it.isCard }
        if (nonCardCount >= target) return result

        while (nonCardCount < target) {
            var longestIndex = -1
            var longestLength = -1
            for (i in result.indices) {
                if (result[i].isCard) continue
                val len = visibleLength(result[i].text)
                if (len > longestLength) {
                    longestLength = len
                    longestIndex = i
                }
            }
            if (longestIndex < 0) break

            val longest = result[longestIndex].text
            if (visibleLength(longest) < 20) break

            val midPoint = longest.length / 2
            var bestSplitOffset = -1
            var bestDistance = Int.MAX_VALUE
            for (offset in longest.indices) {
                if (offset < 6 || offset >= longest.length - 6) continue
                if (primaryDelimiters.contains(longest[offset])) {
                    val distance = kotlin.math.abs(offset - midPoint)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        bestSplitOffset = offset
                    }
                }
            }
            if (bestSplitOffset < 0) break

            val part1 = longest.substring(0, bestSplitOffset + 1).trim()
            val part2 = longest.substring(bestSplitOffset + 1).trim()
            if (part1.isEmpty() || part2.isEmpty()) break

            result[longestIndex] = InternalSegment(part1, false)
            result.add(longestIndex + 1, InternalSegment(part2, false))
            nonCardCount += 1
        }
        return result
    }
}
