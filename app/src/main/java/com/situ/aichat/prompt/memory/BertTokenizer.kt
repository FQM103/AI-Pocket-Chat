package com.situ.aichat.prompt.memory

/**
 * BERT WordPiece tokenizer for bge-small-zh-v1.5 (BertTokenizer, do_lower_case=false,
 * tokenize_chinese_chars=true). Faithful to HuggingFace `BertTokenizer` (BasicTokenizer + WordPiece) and
 * verified against the real vocab.txt + ONNX model (token ids matched bert-base-chinese: [CLS]=101,
 * [SEP]=102, [UNK]=100, [PAD]=0).
 *
 * @param vocab token → id (vocab.txt line index)
 */
class BertTokenizer(private val vocab: Map<String, Int>) {

    private val unkId = vocab[UNK] ?: 100
    private val clsId = vocab[CLS] ?: 101
    private val sepId = vocab[SEP] ?: 102

    /** Encode to input ids: [CLS] + wordpiece(tokens) + [SEP], truncated so total ≤ [maxLen]. */
    fun encode(text: String, maxLen: Int = 512): IntArray {
        val pieces = ArrayList<String>()
        for (basic in basicTokenize(text)) {
            pieces.addAll(wordPiece(basic))
        }
        val maxContent = maxLen - 2
        val truncated = if (pieces.size > maxContent) pieces.subList(0, maxContent) else pieces

        val ids = IntArray(truncated.size + 2)
        ids[0] = clsId
        for (i in truncated.indices) ids[i + 1] = vocab[truncated[i]] ?: unkId
        ids[ids.size - 1] = sepId
        return ids
    }

    // MARK: - BasicTokenizer（clean → CJK 单字隔离 → 空白切 → 标点切；do_lower_case=false）

    private fun basicTokenize(text: String): List<String> {
        val cleaned = cleanAndIsolateCjk(text)
        val out = ArrayList<String>()
        for (token in cleaned.split(WHITESPACE_REGEX)) {
            if (token.isEmpty()) continue
            out.addAll(splitOnPunctuation(token))
        }
        return out
    }

    /** 一次 code-point 遍历：去控制字符/空白归一 + 在每个 CJK 汉字前后插空格（单字成 token）。 */
    private fun cleanAndIsolateCjk(text: String): String {
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue
            if (isWhitespace(cp)) {
                sb.append(' ')
            } else if (isChineseChar(cp)) {
                sb.append(' ').appendCodePoint(cp).append(' ')
            } else {
                sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    private fun splitOnPunctuation(token: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            i += Character.charCount(cp)
            if (isPunctuation(cp)) {
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) }
                out.add(String(Character.toChars(cp)))
            } else {
                cur.appendCodePoint(cp)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    // MARK: - WordPiece（贪心最长匹配，## 续接，超长或无法切分 → [UNK]）

    private fun wordPiece(token: String): List<String> {
        if (token.length > MAX_CHARS_PER_WORD) return listOf(UNK)
        val out = ArrayList<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var cur: String? = null
            while (start < end) {
                var sub = token.substring(start, end)
                if (start > 0) sub = "##$sub"
                if (vocab.containsKey(sub)) { cur = sub; break }
                end -= 1
            }
            if (cur == null) return listOf(UNK)
            out.add(cur)
            start = end
        }
        return out
    }

    // MARK: - 字符分类（对齐 BERT 源码）

    private fun isChineseChar(cp: Int): Boolean =
        (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) || (cp in 0x20000..0x2A6DF) ||
            (cp in 0x2A700..0x2B73F) || (cp in 0x2B740..0x2B81F) || (cp in 0x2B820..0x2CEAF) ||
            (cp in 0xF900..0xFAFF) || (cp in 0x2F800..0x2FA1F)

    private fun isWhitespace(cp: Int): Boolean {
        if (cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return true
        return Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()
    }

    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        return when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(),
            Character.SURROGATE.toInt(), Character.PRIVATE_USE.toInt(),
            Character.UNASSIGNED.toInt() -> true
            else -> false
        }
    }

    private fun isPunctuation(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    companion object {
        private const val CLS = "[CLS]"
        private const val SEP = "[SEP]"
        private const val UNK = "[UNK]"
        private const val MAX_CHARS_PER_WORD = 100
        private val WHITESPACE_REGEX = Regex("""\s+""")

        /** Parse a BERT vocab.txt (one token per line; line index = id). */
        fun parseVocab(lines: Sequence<String>): Map<String, Int> {
            val map = HashMap<String, Int>(24000)
            var i = 0
            for (line in lines) {
                // vocab.txt 每行一个 token（line index = id）。只去掉可能残留的 \r，
                // 不做其它 trim（token 本身不含空白）。
                map[line.removeSuffix("\r")] = i
                i++
            }
            return map
        }
    }
}
