package com.situ.aichat.diagnostics.perf

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 导出文件里的设备头（图纸 §3.2 逐字锁定字段名）。每份报告写一次，不进每条样本。
 * 采集在 [DeviceHealthProbe.deviceHeader]（那边有 Context），这里只负责渲染。
 */
data class PerfDeviceHeader(
    val model: String,
    val manufacturer: String,
    val androidRelease: String,
    val sdkInt: Int,
    val appVersionName: String,
    val refreshHz: Int,
    val densityDpi: Int,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val maxHeapMb: Int,
    val isLowRamDevice: Boolean,
    val locale: String,
)

/**
 * 导出报告渲染（纯函数·图纸 §4.4 分节顺序与「备注」三行逐字锁定）。
 *
 * 报告要同时给人看和给机器解析：前面几节是人读的汇总，末节是原样 JSONL —— 分析时想复算什么都还原得回去，
 * 不必相信汇总。全文只含数字与短枚举串，绝不含聊天正文、日记正文、API 密钥。
 */
object PerfReportFormat {

    /** §4.4 锁定的分节标题（测试按这些逐字断言，改一个字就是改导出格式）。 */
    const val TITLE = "=== AI Pocket Chat 性能报告 ==="
    const val SECTION_DEVICE = "--- 设备 ---"
    const val SECTION_CHECKLIST = "--- 待采清单 ---"
    const val SECTION_SUMMARY = "--- 汇总 ---"
    const val SECTION_NOTES = "--- 备注 ---"
    const val SECTION_RAW = "--- 原始样本（JSONL） ---"

    /** §4.4 锁定的「备注」三行（逐字，含标点）。 */
    val NOTE_LINES = listOf(
        "M5 请另读 Logcat 现成日志「候选=N … 耗时=Xms」（VectorMemoryService）",
        "M34 静态判断即可，无需本报告",
        "M15 归入故事真机批",
    )

    const val DONE_MARK = "✓"
    const val PENDING_MARK = "✗"

    /** 报告文件名（§4.4 锁定 `性能报告_MMdd-HHmm.txt`；`Locale.ROOT` 防区域数字本地化）。 */
    fun fileNameOf(nowMillis: Long): String =
        "性能报告_" + FILE_TIME.format(localDateTime(nowMillis)) + ".txt"

    /**
     * 渲染整份报告。[checklist] 的 `label` 请先换成给人看的文案（纯函数不认字符串资源）。
     */
    fun render(
        header: PerfDeviceHeader,
        samples: List<PerfSample>,
        checklist: List<ChecklistItem>,
        nowMillis: Long,
    ): String = buildString {
        appendLine(TITLE)
        appendLine("schemaVersion: $PERF_SCHEMA_VERSION")
        appendLine("导出时间: " + STAMP.format(localDateTime(nowMillis)))
        val times = samples.map { it.header.tMillis }
        val span = if (times.isEmpty()) {
            "（无样本）"
        } else {
            STAMP.format(localDateTime(times.min())) + " ~ " + STAMP.format(localDateTime(times.max()))
        }
        appendLine("采集时段: $span")
        appendLine()

        appendLine(SECTION_DEVICE)
        deviceLines(header).forEach { appendLine(it) }
        appendLine()

        appendLine(SECTION_CHECKLIST)
        checklist.forEach {
            appendLine("${it.label} 已采${it.collected}/需要${it.required} ${if (it.done) DONE_MARK else PENDING_MARK}")
        }
        appendLine()

        appendLine(SECTION_SUMMARY)
        summaryLines(samples).forEach { appendLine(it) }
        appendLine()

        appendLine(SECTION_NOTES)
        NOTE_LINES.forEach { appendLine(it) }
        appendLine()

        appendLine(SECTION_RAW)
        samples.forEach { appendLine(PerfSampleCodec.encode(perfJson(), it)) }
    }

    // MARK: - 私有

    private fun deviceLines(h: PerfDeviceHeader): List<String> = listOf(
        "model: ${h.model}",
        "manufacturer: ${h.manufacturer}",
        "androidRelease: ${h.androidRelease}",
        "sdkInt: ${h.sdkInt}",
        "appVersionName: ${h.appVersionName}",
        "refreshHz: ${h.refreshHz}",
        "densityDpi: ${h.densityDpi}",
        "screenWidthPx: ${h.screenWidthPx}",
        "screenHeightPx: ${h.screenHeightPx}",
        "maxHeapMb: ${h.maxHeapMb}",
        "isLowRamDevice: ${h.isLowRamDevice}",
        "locale: ${h.locale}",
    )

    /** 每个 kind 一行条数，另加各尺最该一眼看到的那个数。零样本时只列条数 0。 */
    private fun summaryLines(samples: List<PerfSample>): List<String> = buildList {
        val byKind = samples.groupBy { it.header.kind }
        listOf(
            PerfSampleKind.FOREGROUND, PerfSampleKind.FRAMES, PerfSampleKind.HEALTH,
            PerfSampleKind.SETTINGS_WRITE, PerfSampleKind.BACKUP_PROBE,
        ).forEach { add("$it: ${byKind[it]?.size ?: 0} 条") }

        samples.filterIsInstance<PerfSample.Foreground>().takeIf { it.isNotEmpty() }?.let { fg ->
            val totals = fg.map { it.totalMs }.sorted()
            add("foreground totalMs: p50=${percentile(totals, 0.50)} p95=${percentile(totals, 0.95)} max=${totals.last()}")
            val entry = fg.flatMap { s -> s.passes.filter { it.name == PerfPassNames.ENTRY_MAIN_THREAD } }
                .map { it.ms }.sorted()
            if (entry.isNotEmpty()) {
                add("主线程同步段 ms: p50=${percentile(entry, 0.50)} p95=${percentile(entry, 0.95)} max=${entry.last()}")
            }
            // cold_start 不是 pass 耗时而是「进程启动到首次回前台」的标记值，混进排行会顶在第一行读成
            // 「有个 pass 跑了两分钟」——单独列出来。
            fg.flatMap { s -> s.passes.filter { it.name == PerfPassNames.COLD_START } }
                .map { it.ms }.sorted().takeIf { it.isNotEmpty() }
                ?.let { add("冷启动到首次回前台 ms: ${it.joinToString(" / ")}（共 ${it.size} 次冷启动）") }
            // 各 pass 的最坏一次——治本先治最慢那个。
            fg.flatMap { it.passes }
                .filter { it.name != PerfPassNames.COLD_START }
                .groupBy { it.name }.entries
                .sortedByDescending { e -> e.value.maxOf { it.ms } }
                .forEach { (name, list) -> add("  pass $name: 最坏 ${list.maxOf { it.ms }}ms（${list.size} 次）") }
            fg.lastOrNull()?.scale?.let { add("规模数（最近一条）: $it") }
        }

        samples.filterIsInstance<PerfSample.Frames>().groupBy { it.scene }.forEach { (scene, list) ->
            val frames = list.sumOf { it.frameCount }
            val jank = list.sumOf { it.jankCount }
            val severe = list.sumOf { it.severeJankCount }
            val rate = if (frames == 0) 0.0 else jank * 100.0 / frames
            add(
                "frames $scene: ${list.size} 段 / $frames 帧 / 掉帧 $jank（${format1(rate)}%）/ 严重 $severe" +
                    " / p95 最坏 ${format1(list.maxOf { it.p95Ms })}ms / ${list.first().refreshHz}Hz",
            )
        }

        samples.filterIsInstance<PerfSample.Health>().takeIf { it.isNotEmpty() }?.let { hs ->
            val temps = hs.map { it.batteryTempC }.filter { !it.isNaN() }
            val tempText = if (temps.isEmpty()) "未取到" else "${format1(temps.min())}~${format1(temps.max())}℃"
            add("health: 电池温度 $tempText / 最高热档 ${hs.maxOf { it.thermalStatus }}")
        }

        samples.filterIsInstance<PerfSample.SettingsWrite>().takeIf { it.isNotEmpty() }?.let { ws ->
            add("settings_write: 一趟手势最多变更 ${ws.maxOf { it.writesInGesture }} 次 / 设置文件 ${ws.last().payloadBytes} 字节")
        }

        samples.filterIsInstance<PerfSample.BackupProbe>().forEach {
            add("backup_probe ${it.mode}: ${it.fileBytes} 字节 / 阶段 ${it.stage} / OOM ${it.oomCaught} / 峰值堆 ${it.peakHeapBytes}（上限 ${it.maxHeapBytes}）")
        }
    }

    /** 已排序列表的分位数（最近秩法·空列表 → 0）。 */
    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0L
        val idx = Math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[idx]
    }

    private fun format1(v: Double): String = String.format(Locale.ROOT, "%.1f", v)

    private fun localDateTime(millis: Long) =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()

    /** 时间格式化一律 `Locale.ROOT`（工程既有教训：区域数字本地化会写出非 ASCII 数字）。 */
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MMdd-HHmm", Locale.ROOT)

}
