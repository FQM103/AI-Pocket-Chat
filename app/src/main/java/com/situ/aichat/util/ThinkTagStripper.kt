package com.situ.aichat.util

/**
 * 非流式 LLM 输出的思考标签剥离**单源**（2026-07-11 自 [com.situ.aichat.prompt.memory.MemoryService] /
 * [com.situ.aichat.story.StoryTextCleaning] 收拢；两处原函数保留为转发，调用方无感。流式路径另有
 * `data/remote/llm/ThinkTagParser`，与本工具无关）。
 *
 * 三条规则（未闭合/孤闭合语义 = 2026-07-11 用户拍板；大小写不敏感 = 同日 🔵3 拍板，兜大写变体模型）：
 * 1. **闭合块**：`<think>…</think>` / `<thinking>…</thinking>` 整段移除（跨行非贪婪）。
 * 2. **孤闭合标签**（无配对开标签——R1 类服务端模板把 `<think>` 写进提示词、模型只输出闭合）：
 *    闭合之前全是思考，**连前文一起删**（多个孤闭合取最后一个）。
 * 3. **未闭合开标签**（输出在思考中途被截断，闭合永远没来）：开标签之后全是思考、正文从未产出，**删到串尾**。
 *
 * ⚠️ 规则 2/3 可能把整串剥空——这是「模型只输出了思考、没输出正文」的忠实信号。**调用方必须把空结果
 * 当失败走各自既有的重试/兜底路径**（当场重试、计失败下次再试、退模板……），绝不把空落库或覆盖旧值。
 */
object ThinkTagStripper {

    private val thinkBlock = Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE)
    private val thinkingBlock = Regex("""<thinking>[\s\S]*?</thinking>""", RegexOption.IGNORE_CASE)
    private val unclosedThink = Regex("""<think>[\s\S]*$""", RegexOption.IGNORE_CASE)
    private val unclosedThinking = Regex("""<thinking>[\s\S]*$""", RegexOption.IGNORE_CASE)

    fun strip(s: String): String {
        var result = s
        result = thinkBlock.replace(result, "")
        result = thinkingBlock.replace(result, "")
        // 闭合块清完后仍残留的闭合标签必为孤闭合：取最后一个，连同之前的思考正文一并删（规则 2）。
        val closerEnd = maxOf(
            result.lastIndexOf("</think>", ignoreCase = true).let { if (it >= 0) it + "</think>".length else -1 },
            result.lastIndexOf("</thinking>", ignoreCase = true).let { if (it >= 0) it + "</thinking>".length else -1 },
        )
        if (closerEnd >= 0) result = result.substring(closerEnd)
        result = unclosedThink.replace(result, "")
        result = unclosedThinking.replace(result, "")
        return result.trim()
    }
}
