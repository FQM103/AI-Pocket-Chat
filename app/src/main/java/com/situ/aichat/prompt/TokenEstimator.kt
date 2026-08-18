package com.situ.aichat.prompt

/**
 * Estimates token count by CJK / non-CJK ratio. Used for log segment stats only — NOT for trimming.
 *
 * 批4 4-6 校准（原 iOS 系数 CJK=1.5 token/字为老分词器口径）：现代 BPE 分词器（DeepSeek/Qwen/GPT-4o 系）
 * 中文约 0.6–1.0 token/字，取 1.0 作偏保守上界——上下文日志页数字从「高估约 1.5–2.5 倍」回到量级可信。
 * 非 CJK 维持 ≈0.25 token/char（英文 BPE ~4 字符/token）。
 */
object TokenEstimator {

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0

        var cjkCount = 0
        var otherCount = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isCJK(cp)) cjkCount++ else otherCount++
            i += Character.charCount(cp)
        }

        val estimated = cjkCount + maxOf(otherCount / 4, if (otherCount > 0) 1 else 0)
        return maxOf(estimated, 1)
    }

    private fun isCJK(v: Int): Boolean = when {
        v in 0x2E80..0x9FFF -> true   // 部首扩展 + 康熙部首 + 扩展A + 统一汉字
        v in 0xF900..0xFAFF -> true   // 兼容汉字
        v in 0xAC00..0xD7AF -> true   // 韩文音节
        v in 0xFF00..0xFFEF -> true   // 全角字符
        v in 0x20000..0x2FA1F -> true // 扩展 B-F
        v in 0x3040..0x30FF -> true   // 平假名 + 片假名
        else -> false
    }
}
