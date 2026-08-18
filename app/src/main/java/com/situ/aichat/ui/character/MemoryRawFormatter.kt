package com.situ.aichat.ui.character

// 记忆原文纯格式化（图纸 2026-07-15-资料页三Tab重构 §4.4·V-1）：拆行 / 去行首项目符号 / 识别整行标题。
// 纯显示层美化——无结构依赖、优雅退化（模型漏写标题 → 全部当正文行渲染，不崩）；不再调用 MemorySummarySections.parse。

internal object MemoryRawFormatter {
    private val BULLET_PREFIXES = listOf("- ", "• ", "· ", "* ")   // 仅「符号+空格」·不吃裸负号
    fun lines(blob: String): List<String> =
        blob.split('\n', '\r').map { stripBullet(it.trim()) }.filter { it.isNotEmpty() }
    fun stripBullet(t: String): String {
        for (p in BULLET_PREFIXES) if (t.startsWith(p)) return t.removePrefix(p).trim()
        return t
    }
    // 整行恰为【标题】（内部无嵌套】）才算标题行——纯显示层美化·无结构依赖
    fun isSectionTitle(line: String): Boolean =
        line.length >= 2 && line.startsWith("【") && line.endsWith("】") &&
            !line.substring(1, line.length - 1).contains("】")
}
