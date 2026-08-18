package com.situ.aichat.prompt

/**
 * LLM 返回文本的合法性校验（1:1 iOS `GeneratedContentValidator`）。
 *
 * 背景：朋友圈动态 / 朋友圈评论 / 日记正文 / 日记评论 等「内容型」LLM 调用路径过去只做
 * `strippingThinkingTags + isNotBlank`，LLM/代理偶发返回 "Token count: 2937"、
 * `{"error": ...}`、纯数字等非正文字符串时会被原样入库展示。这道最小过滤拦住它们。
 *
 * 聊天路径另有 [ReplyParser.sanitizeAssistantResponse] 等过滤，不走这里。
 *
 * 与 iOS 的对应：iOS 在 5 处 `guard isLikelyValid else throw GeneratedContentValidationError`；
 * 安卓各 generate 函数返回 `String?`（null = 生成失败、不入库），故这里不需异常类——
 * 调用方判 `isLikelyValid` 为假即返回 null（行为等价：脏数据永不入库）。
 */
object GeneratedContentValidator {

    /**
     * 最小有效长度：低于此字数直接视为非正文。
     * iOS 从 8 降到 4，兼容极短但合法的朋友圈（如「今天好冷」4 字心情短句）。
     */
    private const val MIN_VALID_LENGTH = 4

    /**
     * 「前缀黑名单」：文本 trim 后必须**以**这些词开头才算异常（大小写不敏感）。
     * 适用于「只有 provider 异常响应才会这样开头」的 debug 字段——正文里可以讨论
     * 「今天代码报了 Error: xxx」，但整条从 `Error:` / `Token count:` 起手几乎可断定是脏数据。
     */
    private val PREFIX_MARKERS = listOf(
        "token count",       // "Token count: 2937"
        "usage:",            // "usage: { prompt_tokens: ... }"
        "prompt_tokens",     // "prompt_tokens: 500 completion_tokens: 200"
        "completion_tokens",
        "finish_reason",     // "finish_reason: stop"
        "error:",            // "Error: rate limit exceeded"
    )

    /**
     * 「片段黑名单」：文本任意位置包含这些片段就算异常。
     * 只放「正文绝不可能出现」的明显脏数据特征——当前只有 JSON 错误被错放进 content 字段
     * （如 `{"error": "invalid_api_key", "code": 401}`）。
     */
    private val BODY_MARKERS = listOf(
        "{\"error\"",
    )

    /**
     * 判断一段 LLM 返回文本是否看起来是有效的「正文内容」。
     *
     * @param minLength 最短长度门槛。默认 [MIN_VALID_LENGTH]（4 字，兼容短评论/心情短句）；
     *   期望长文的调用方（如朋友圈动态要求 50-150 字）应传更高门槛——2026-07-07 教训：
     *   假模型的聊天腔罐头回复「嗯嗯，刚看到消息。」（9 字）过了 4 字门被当动态入库。
     * @return true = 看起来合法、可入库；false = 疑似非正文、应丢弃。
     */
    fun isLikelyValid(text: String, minLength: Int = MIN_VALID_LENGTH): Boolean {
        val trimmed = text.trim()

        // 太短基本不可能是一条真正的朋友圈/日记
        if (trimmed.length < minLength) return false

        // 纯数字 / 纯标点 / 纯符号 —— 必须至少有一个字母或汉字类字符
        // iOS CharacterSet.letters = Unicode L*（含 CJK Lo）↔ Kotlin Char.isLetter()
        if (trimmed.none { it.isLetter() }) return false

        val lower = trimmed.lowercase()

        // 前缀黑名单：必须以关键词开头才拦（避免误伤正文中讨论错误的内容）
        if (PREFIX_MARKERS.any { lower.startsWith(it) }) return false

        // 片段黑名单：任意位置出现即拦
        if (BODY_MARKERS.any { lower.contains(it) }) return false

        return true
    }

    /** 给出具体的无效原因，便于写入日志排查（对齐 iOS describeInvalidReason）。 */
    fun describeInvalidReason(text: String, minLength: Int = MIN_VALID_LENGTH): String {
        val trimmed = text.trim()
        if (trimmed.length < minLength) {
            return "内容校验失败：文本过短（${trimmed.length} 字）"
        }
        if (trimmed.none { it.isLetter() }) {
            return "内容校验失败：无任何字母或文字字符"
        }
        val lower = trimmed.lowercase()
        PREFIX_MARKERS.firstOrNull { lower.startsWith(it) }?.let {
            return "内容校验失败：以 debug 关键词「$it」开头"
        }
        BODY_MARKERS.firstOrNull { lower.contains(it) }?.let {
            return "内容校验失败：命中脏数据片段「$it」"
        }
        return "内容校验失败：未知原因"
    }
}
