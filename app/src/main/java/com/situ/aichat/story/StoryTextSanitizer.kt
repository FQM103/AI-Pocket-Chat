package com.situ.aichat.story

/**
 * 渲染前最终安全网：清除任何漏过解析器的原始方括号标签（1:1 iOS `StoryTextSanitizer`，
 * `StoryReaderAnimatedBlocks.swift:339-353`）。
 *
 * 匹配 `[xxx:yyy]` / `[xxx]` / `[/xxx]` 三类标签并删除，接着剥掉尾部残留的元数据块
 * （图纸一 C2 第 2 层 · [StoryMetadataParser.stripTrailingMetadata]），再把连续 3 个换行收成 2 个、首尾去空白。
 * 纯逻辑，单测反推 iOS。
 *
 * **只在显示路径生效，绝不写回 DB**：4 个消费点（阅读器块 / 世界书扫描 / 档案摘要 / TXT 导出）一处改动全部受益，
 * 历史已落库的坏章立刻在显示层变干净。不含残渣的文本输出与加这层之前逐字节一致。
 */
internal object StoryTextSanitizer {
    /** 方括号标签：可选 `/` + 字母/下划线开头标识符 + 可选 `:值`。`[^\]]` 含换行，与 iOS NSRegularExpression 一致。 */
    private val tagPattern = Regex("""\[/?[a-zA-Z_][a-zA-Z0-9_]*(?::[^\]]*)?\]""")

    fun sanitize(text: String): String =
        StoryMetadataParser.stripTrailingMetadata(tagPattern.replace(text, ""))
            .replace("\n\n\n", "\n\n")
            .trim()
}
