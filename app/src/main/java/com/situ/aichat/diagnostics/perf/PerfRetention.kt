package com.situ.aichat.diagnostics.perf

/** 一个日文件的名字与字节数（[PerfRetention] 的纯数据入参·不含 File 便于单测）。 */
data class PerfFileInfo(val name: String, val bytes: Long)

/**
 * 采集目录容量轮转算法（纯函数·照 [com.situ.aichat.diagnostics.LogRetention] 范式抽出便于 T1）。
 *
 * 规矩（图纸 §3.3 + §5 E4/E5/E8）：
 * - 目录总字节没超帽 → 一个都不删。
 * - 超帽 → **按文件名日期升序删最旧的整个日文件**，直到不超为止（文件名形如 `perf-yyyyMMdd.jsonl`，
 *   定长日期段 ⇒ 字典序 = 日期序）。
 * - **当天文件永不进删除列表**，哪怕它自己就撑爆帽 —— 那种情况改由写入侧停止当天追加并累计 dropped 计数。
 */
object PerfRetention {

    /**
     * @param files 目录里现有的全部样本文件。
     * @param capBytes 目录总字节上限。
     * @param todayName 当天文件名（永不删）。
     * @return 该删的文件名，按删除顺序（最旧在前）。
     */
    fun filesToDelete(files: List<PerfFileInfo>, capBytes: Long, todayName: String): List<String> {
        var running = files.sumOf { it.bytes }
        if (running <= capBytes) return emptyList()
        val victims = ArrayList<String>()
        for (file in files.filter { it.name != todayName }.sortedBy { it.name }) {
            if (running <= capBytes) break
            victims += file.name
            running -= file.bytes
        }
        return victims
    }
}
