package com.situ.aichat.diagnostics.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1-4（图纸 2026-07-30 性能采集与量尺 §7）：[PerfChecklist] 待采清单判定。
 *
 * 断言从图纸 §3.6 的规格独立反推：恰 6 项、required 分别 3/10/1/1/1/1、达标翻 done、缺项如实报数。
 */
class PerfChecklistTest {

    private var t = 1_000L

    private fun header(kind: String) = PerfHeader(PERF_SCHEMA_VERSION, t++, kind)

    private fun foreground(coldStart: Boolean = false) = PerfSample.Foreground(
        header = header(PerfSampleKind.FOREGROUND),
        totalMs = 30,
        passes = buildList {
            if (coldStart) add(PassTiming(PerfPassNames.COLD_START, 420))
            add(PassTiming(PerfPassNames.ENTRY_MAIN_THREAD, 8))
        },
        scale = ScaleNumbers.UNAVAILABLE,
    )

    private fun frames(scene: String) = PerfSample.Frames(
        header = header(PerfSampleKind.FRAMES), scene = scene, durationMs = 1000, frameCount = 60,
        jankCount = 1, severeJankCount = 0, p50Ms = 8.0, p95Ms = 9.0, p99Ms = 10.0, maxMs = 12.0,
        buckets = List(8) { 0 }, refreshHz = 120,
    )

    private fun settingsWrite() = PerfSample.SettingsWrite(
        header = header(PerfSampleKind.SETTINGS_WRITE), screen = PerfSettingsSites.SCREEN_CONTEXT_LOG,
        key = PerfSettingsSites.KEY_LOG_RETENTION, writesInGesture = 24, gestureMs = 900, payloadBytes = 4096,
    )

    private fun backupProbe() = PerfSample.BackupProbe(
        header = header(PerfSampleKind.BACKUP_PROBE), mode = "readonly", fileBytes = 100, maxHeapBytes = 1,
        peakHeapBytes = 1, bitmapCacheBytes = 0, oomCaught = false, stage = "done", mediaEntryCount = 0,
        manifestChars = 10, elapsedMs = 5,
    )

    private fun evaluate(samples: List<PerfSample>) = PerfChecklist.evaluate(samples).associateBy { it.label }

    @Test
    fun `恰 6 项_顺序与 required 逐个锁定`() {
        val items = PerfChecklist.evaluate(emptyList())

        assertEquals(6, items.size)
        assertEquals(
            listOf("cold_start", "foreground", "slider", "world_planet", "voice_call", "backup"),
            items.map { it.label },
        )
        assertEquals(listOf(3, 10, 1, 1, 1, 1), items.map { it.required })
    }

    @Test
    fun `零样本时全部未完成且如实报 0`() {
        val items = PerfChecklist.evaluate(emptyList())

        assertTrue(items.all { !it.done && it.collected == 0 })
    }

    @Test
    fun `冷启动只认带 cold_start 标记的那几条`() {
        val samples = List(5) { foreground(coldStart = false) } + List(2) { foreground(coldStart = true) }

        val items = evaluate(samples)

        assertEquals(2, items.getValue(PerfChecklist.ID_COLD_START).collected)
        assertFalse("2 < 3 还没达标", items.getValue(PerfChecklist.ID_COLD_START).done)
        assertEquals("回前台数的是全部 foreground 样本", 7, items.getValue(PerfChecklist.ID_FOREGROUND).collected)
    }

    @Test
    fun `各项达到 required 即翻 done`() {
        val samples = List(3) { foreground(coldStart = true) } +
            List(7) { foreground() } +
            settingsWrite() +
            frames(PerfScenes.WORLD_PLANET) +
            frames(PerfScenes.VOICE_CALL) +
            backupProbe()

        val items = evaluate(samples)

        assertTrue("冷启动 3/3", items.getValue(PerfChecklist.ID_COLD_START).done)
        assertTrue("回前台 10/10", items.getValue(PerfChecklist.ID_FOREGROUND).done)
        assertTrue(items.getValue(PerfChecklist.ID_SLIDER).done)
        assertTrue(items.getValue(PerfChecklist.ID_WORLD).done)
        assertTrue(items.getValue(PerfChecklist.ID_CALL).done)
        assertTrue(items.getValue(PerfChecklist.ID_BACKUP).done)
    }

    @Test
    fun `帧样本按场景分别计入_别的场景不顶数`() {
        val samples = listOf(
            frames(PerfScenes.WORLD_CONTINENT), frames(PerfScenes.STORY_READER),
            frames(PerfScenes.MEMORY_STARFIELD),
        )

        val items = evaluate(samples)

        assertEquals("星球屏没停过就是 0", 0, items.getValue(PerfChecklist.ID_WORLD).collected)
        assertEquals("没打过电话就是 0", 0, items.getValue(PerfChecklist.ID_CALL).collected)
    }

    @Test
    fun `health 样本不影响任何一项判定`() {
        val health = PerfSample.Health(
            header = header(PerfSampleKind.HEALTH), thermalStatus = 0, thermalName = "none",
            batteryTempC = 31.0, scene = PerfScenes.WORLD_PLANET,
        )

        assertEquals(PerfChecklist.evaluate(emptyList()), PerfChecklist.evaluate(listOf(health)))
    }

    @Test
    fun `超额也如实报数不封顶`() {
        val items = evaluate(List(25) { foreground(coldStart = true) })

        assertEquals(25, items.getValue(PerfChecklist.ID_COLD_START).collected)
        assertEquals(25, items.getValue(PerfChecklist.ID_FOREGROUND).collected)
    }
}
