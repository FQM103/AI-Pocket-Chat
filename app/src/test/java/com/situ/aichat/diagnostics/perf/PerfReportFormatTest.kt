package com.situ.aichat.diagnostics.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-1（图纸 2026-07-30 性能采集与量尺 §7）：[PerfReportFormat] 导出报告结构。
 *
 * 断言从图纸 §4.4 的**锁定分节顺序与逐字文案**独立反推——分节标题与「备注」三行在这里**重新打字**为
 * 字面量（不引实现常量），这样把实现里的常量改错也照样红。另加一道「与实现常量一致」的双保险。
 */
class PerfReportFormatTest {

    private val header = PerfDeviceHeader(
        model = "23127PN0CC",
        manufacturer = "Xiaomi",
        androidRelease = "16",
        sdkInt = 36,
        appVersionName = "0.1.0",
        refreshHz = 120,
        densityDpi = 440,
        screenWidthPx = 1220,
        screenHeightPx = 2712,
        maxHeapMb = 256,
        isLowRamDevice = false,
        locale = "zh-CN",
    )

    private fun foreground(t: Long, totalMs: Long) = PerfSample.Foreground(
        header = PerfHeader(PERF_SCHEMA_VERSION, t, PerfSampleKind.FOREGROUND),
        totalMs = totalMs,
        passes = listOf(PassTiming(PerfPassNames.ENTRY_MAIN_THREAD, 12), PassTiming(PerfPassNames.WORLD_LINK, 90)),
        scale = ScaleNumbers(3, 1200, 2, 5, 40, 7, 9, 11, 100),
    )

    private fun frames(t: Long, scene: String) = PerfSample.Frames(
        header = PerfHeader(PERF_SCHEMA_VERSION, t, PerfSampleKind.FRAMES),
        scene = scene,
        durationMs = 30_000,
        frameCount = 3600,
        jankCount = 36,
        severeJankCount = 4,
        p50Ms = 6.0,
        p95Ms = 9.5,
        p99Ms = 14.0,
        maxMs = 22.0,
        buckets = listOf(3000, 400, 164, 20, 12, 3, 1, 0),
        refreshHz = 120,
    )

    private val checklist = listOf(
        ChecklistItem("冷启动", 3, 3, true),
        ChecklistItem("回到前台", 4, 10, false),
    )

    @Test
    fun `五个分节按锁定顺序出现`() {
        val text = PerfReportFormat.render(header, listOf(foreground(1_000, 40)), checklist, 2_000)

        val order = listOf("--- 设备 ---", "--- 待采清单 ---", "--- 汇总 ---", "--- 备注 ---", "--- 原始样本（JSONL） ---")
            .map { text.indexOf(it) }
        assertTrue("每个分节标题都必须出现，实为 $order", order.none { it < 0 })
        assertEquals("分节必须按锁定顺序", order.sorted(), order)
        assertTrue("首行必须是锁定标题", text.startsWith("=== AI Pocket Chat 性能报告 ===\n"))
    }

    @Test
    fun `分节标题的字面量与实现常量一致（双保险）`() {
        assertEquals("=== AI Pocket Chat 性能报告 ===", PerfReportFormat.TITLE)
        assertEquals("--- 设备 ---", PerfReportFormat.SECTION_DEVICE)
        assertEquals("--- 待采清单 ---", PerfReportFormat.SECTION_CHECKLIST)
        assertEquals("--- 汇总 ---", PerfReportFormat.SECTION_SUMMARY)
        assertEquals("--- 备注 ---", PerfReportFormat.SECTION_NOTES)
        assertEquals("--- 原始样本（JSONL） ---", PerfReportFormat.SECTION_RAW)
    }

    @Test
    fun `备注三行逐字匹配`() {
        val text = PerfReportFormat.render(header, emptyList(), emptyList(), 2_000)

        assertTrue(text.contains("M5 请另读 Logcat 现成日志「候选=N … 耗时=Xms」（VectorMemoryService）"))
        assertTrue(text.contains("M34 静态判断即可，无需本报告"))
        assertTrue(text.contains("M15 归入故事真机批"))
        assertEquals(3, PerfReportFormat.NOTE_LINES.size)
    }

    @Test
    fun `设备头 12 个字段逐个出现`() {
        val text = PerfReportFormat.render(header, emptyList(), emptyList(), 2_000)

        listOf(
            "model: 23127PN0CC", "manufacturer: Xiaomi", "androidRelease: 16", "sdkInt: 36",
            "appVersionName: 0.1.0", "refreshHz: 120", "densityDpi: 440", "screenWidthPx: 1220",
            "screenHeightPx: 2712", "maxHeapMb: 256", "isLowRamDevice: false", "locale: zh-CN",
        ).forEach { assertTrue("缺字段行：$it", text.contains(it)) }
    }

    @Test
    fun `零样本时结构完整_原始样本节为空`() {
        val text = PerfReportFormat.render(header, emptyList(), checklist, 2_000)

        assertTrue(text.contains("--- 原始样本（JSONL） ---"))
        assertTrue("零样本时采集时段要明说没有", text.contains("采集时段: （无样本）"))
        assertTrue("每个 kind 仍列出 0 条", text.contains("foreground: 0 条"))
        val raw = text.substringAfter("--- 原始样本（JSONL） ---")
        assertEquals("原始样本节应为空", "", raw.trim())
    }

    @Test
    fun `待采清单每项一行_完成打勾未完成打叉`() {
        val text = PerfReportFormat.render(header, emptyList(), checklist, 2_000)

        assertTrue(text.contains("冷启动 已采3/需要3 ✓"))
        assertTrue(text.contains("回到前台 已采4/需要10 ✗"))
    }

    @Test
    fun `原始样本节逐行是可解析的 JSON_条数与样本数一致`() {
        val samples = listOf(foreground(1_000, 40), frames(2_000, PerfScenes.WORLD_PLANET))

        val text = PerfReportFormat.render(header, samples, checklist, 3_000)

        val raw = text.substringAfter("--- 原始样本（JSONL） ---").trim().lines()
        assertEquals(2, raw.size)
        raw.forEach { line ->
            assertTrue("每行都要能解回样本：$line", PerfSampleCodec.decode(perfJson(), line) != null)
        }
    }

    @Test
    fun `汇总给出掉帧率与 totalMs 分位数`() {
        val samples = listOf(
            foreground(1_000, 40), foreground(2_000, 90),
            frames(3_000, PerfScenes.WORLD_PLANET),
        )

        val text = PerfReportFormat.render(header, samples, checklist, 4_000)

        assertTrue("要给 totalMs 分位数", text.contains("foreground totalMs: p50="))
        assertTrue("要给主线程同步段（M13 看它）", text.contains("主线程同步段 ms:"))
        assertTrue("要给掉帧率百分比", text.contains("frames ${PerfScenes.WORLD_PLANET}:") && text.contains("1.0%"))
        assertTrue("要给刷新率（没有它毫秒数就没参照系）", text.contains("120Hz"))
    }

    @Test
    fun `冷启动标记单独成行_不混进 pass 耗时排行`() {
        val withColdStart = PerfSample.Foreground(
            header = PerfHeader(PERF_SCHEMA_VERSION, 1_000, PerfSampleKind.FOREGROUND),
            totalMs = 40,
            passes = listOf(
                PassTiming(PerfPassNames.COLD_START, 122_261), // 进程启动到首次回前台，不是 pass 耗时
                PassTiming(PerfPassNames.ENTRY_MAIN_THREAD, 2),
            ),
            scale = ScaleNumbers.UNAVAILABLE,
        )

        val text = PerfReportFormat.render(header, listOf(withColdStart), checklist, 2_000)

        assertTrue("冷启动要单独成行", text.contains("冷启动到首次回前台 ms: 122261"))
        assertFalse(
            "混进排行会顶在第一行、读成「有个 pass 跑了两分钟」",
            text.contains("pass ${PerfPassNames.COLD_START}: 最坏"),
        )
        assertTrue("真正的 pass 排行照旧", text.contains("pass ${PerfPassNames.ENTRY_MAIN_THREAD}: 最坏 2ms"))
    }

    /**
     * 「只记数字」的承诺（图纸 §0.3 ②③ + REDLINES §3）：原始样本节的 JSON 键**只许**是锁定的那些。
     * 这么钉而不是黑名单几个词，是因为黑名单挡不住将来新加的字段——白名单才会在有人往样本里塞正文时立刻红。
     */
    @Test
    fun `原始样本的 JSON 键全在锁定白名单内（只记数字的承诺）`() {
        val samples = listOf(
            foreground(1_000, 40),
            frames(2_000, PerfScenes.VOICE_CALL),
            PerfSample.Health(
                header = PerfHeader(PERF_SCHEMA_VERSION, 3_000, PerfSampleKind.HEALTH),
                thermalStatus = 0, thermalName = "none", batteryTempC = 31.5, scene = null,
            ),
            PerfSample.SettingsWrite(
                header = PerfHeader(PERF_SCHEMA_VERSION, 4_000, PerfSampleKind.SETTINGS_WRITE),
                screen = "s", key = "k", writesInGesture = 24, gestureMs = 900, payloadBytes = 4096,
            ),
            PerfSample.BackupProbe(
                header = PerfHeader(PERF_SCHEMA_VERSION, 5_000, PerfSampleKind.BACKUP_PROBE),
                mode = "readonly", fileBytes = 1, maxHeapBytes = 2, peakHeapBytes = 3, bitmapCacheBytes = 4,
                oomCaught = false, stage = "done", mediaEntryCount = 5, manifestChars = 6, elapsedMs = 7,
            ),
        )
        val allowed = setOf(
            "schemaVersion", "tMillis", "kind", "header",
            "totalMs", "passes", "name", "ms", "scale",
            "characters", "messages", "worldResidents", "meetingAppointments", "notificationRecords",
            "diaryEntries", "momentPosts", "storyChapters", "logEntries",
            "scene", "durationMs", "frameCount", "jankCount", "severeJankCount",
            "p50Ms", "p95Ms", "p99Ms", "maxMs", "buckets", "refreshHz",
            "thermalStatus", "thermalName", "batteryTempC",
            "screen", "key", "writesInGesture", "gestureMs", "payloadBytes",
            "mode", "fileBytes", "maxHeapBytes", "peakHeapBytes", "bitmapCacheBytes",
            "oomCaught", "stage", "mediaEntryCount", "manifestChars", "elapsedMs",
        )

        val text = PerfReportFormat.render(header, samples, checklist, 6_000)

        val raw = text.substringAfter("--- 原始样本（JSONL） ---").trim()
        val keys = Regex("\"([A-Za-z][A-Za-z0-9]*)\"\\s*:").findAll(raw).map { it.groupValues[1] }.toSet()
        assertTrue("样本键必须都在白名单内，越界的是：${keys - allowed}", (keys - allowed).isEmpty())
        assertTrue("白名单本身要真被用上（防正则写错成空集）", keys.size > 20)
        assertFalse("绝不许出现密钥字段", raw.contains("apiKey") || raw.contains("api_key"))
    }

    @Test
    fun `报告文件名按锁定格式且用 ROOT 区域`() {
        val name = PerfReportFormat.fileNameOf(1_754_000_000_000L)

        assertTrue("实为 $name", Regex("^性能报告_\\d{4}-\\d{4}\\.txt$").matches(name))
    }
}
