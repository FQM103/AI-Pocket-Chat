package com.situ.aichat.ui.contextlog

/**
 * 全文查看器分块（D-3 打磨·②·纯函数）：把超长日志文本按行边界切成 ≤[maxChars] 的块，
 * 供 LazyColumn 逐块惰性渲染——全量记录后单条可达 20 万字，塞单个 Text 的段落布局是
 * 秒级卡顿源（Compose 一次性测量整段）。块间以换行重接 = 原文（无字符增删）；唯一例外：
 * 单行超上限被硬切成多块，块界成为视觉换行——病态无换行长串的显示折衷（复制走原字符串不受影响）。
 *
 * 空行语义（复核 R1-🟡1 修正）：块的「空」用 [收过行] 标志判定而非字符长度——空行（""）也是行，
 * 落在块边界时必须占位（否则块间空行被静默吞掉、重接恒等性破裂·fuzz 实证过）。因此块可以是空串
 * 或以换行开头（= 原文里的空行），查看器按原样渲染即天然保住行距。
 */
internal fun splitLogTextBlocks(text: String, maxChars: Int = LOG_TEXT_BLOCK_CHARS): List<String> {
    if (text.length <= maxChars) return listOf(text)
    val blocks = ArrayList<String>(text.length / maxChars + 1)
    val current = StringBuilder(maxChars)
    var blockHasLines = false

    fun flush() {
        if (blockHasLines) {
            blocks.add(current.toString())
            current.setLength(0)
            blockHasLines = false
        }
    }

    for (line in text.lineSequence()) {
        // 单行超块上限（罕见：模型输出无换行长串）→ 硬切，保证块大小上界成立；
        // 切点若劈开增补字符代理对（emoji），高位回退一格（复核 R1-🔵6·块 ≤maxChars 不变量保持）。
        if (line.length > maxChars) {
            flush()
            var start = 0
            while (start < line.length) {
                var end = minOf(start + maxChars, line.length)
                if (end < line.length && end > start + 1 && Character.isHighSurrogate(line[end - 1])) end--
                blocks.add(line.substring(start, end))
                start = end
            }
            continue
        }
        val extra = if (blockHasLines) 1 + line.length else line.length
        if (blockHasLines && current.length + extra > maxChars) flush()
        if (blockHasLines) current.append('\n')
        current.append(line)
        blockHasLines = true
    }
    flush()
    return blocks
}

/** 块大小上限（字符）。约一两屏文字：块内布局便宜，块外靠 LazyColumn 只渲染可见项。 */
internal const val LOG_TEXT_BLOCK_CHARS = 4_000
