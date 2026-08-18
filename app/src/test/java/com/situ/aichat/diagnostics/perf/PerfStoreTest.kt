package com.situ.aichat.diagnostics.perf

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * T2-2（图纸 2026-07-30 性能采集与量尺 §7）：[PerfStore] 落盘 / 读回 / 静默降级。
 *
 * 断言从图纸 §3.3 与 §5 E3/E7/E9/E23 规格独立反推。用 Robolectric 是因为要真 `filesDir` 与真文件系统。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerfStoreTest {

    private val app = RuntimeEnvironment.getApplication()

    private lateinit var store: PerfStore
    private val perfDir: File get() = File(app.filesDir, PerfStore.DIR_NAME)

    @Before
    fun setUp() {
        perfDir.deleteRecursively()
        store = PerfStore(app)
    }

    private fun header(kind: String, t: Long = 1_754_000_000_000L) = PerfHeader(PERF_SCHEMA_VERSION, t, kind)

    private fun foreground(totalMs: Long) = PerfSample.Foreground(
        header = header(PerfSampleKind.FOREGROUND),
        totalMs = totalMs,
        passes = listOf(PassTiming(PerfPassNames.ENTRY_MAIN_THREAD, 12)),
        scale = ScaleNumbers(1, 2, 3, 4, 5, 6, 7, 8, 9),
    )

    @Test
    fun `写入后可原样读回（字段与顺序保真）`() = runBlocking {
        store.append(listOf(foreground(40), foreground(41)))

        val read = store.readAll()
        assertEquals(2, read.size)
        val first = read[0] as PerfSample.Foreground
        assertEquals(PERF_SCHEMA_VERSION, first.header.schemaVersion)
        assertEquals(PerfSampleKind.FOREGROUND, first.header.kind)
        assertEquals(40L, first.totalMs)
        assertEquals(listOf(PassTiming(PerfPassNames.ENTRY_MAIN_THREAD, 12)), first.passes)
        assertEquals(ScaleNumbers(1, 2, 3, 4, 5, 6, 7, 8, 9), first.scale)
        assertEquals(41L, (read[1] as PerfSample.Foreground).totalMs)
    }

    @Test
    fun `落盘是一行一条 JSONL 且文件名按本地日期`() = runBlocking {
        store.append(listOf(foreground(1), foreground(2), foreground(3)), nowMillis = 1_754_000_000_000L)

        val expectedName = PerfStore.fileNameFor(1_754_000_000_000L)
        assertTrue("文件名须形如 perf-yyyyMMdd.jsonl，实为 $expectedName", Regex("perf-\\d{8}\\.jsonl").matches(expectedName))
        val lines = File(perfDir, expectedName).readLines()
        assertEquals(3, lines.size)
        lines.forEach { assertTrue("每行都必须是完整 JSON 对象", it.startsWith("{") && it.endsWith("}")) }
    }

    @Test
    fun `最后一行被截断时跳过该行读回其余（E7）`() = runBlocking {
        store.append(listOf(foreground(10), foreground(20)))
        val file = perfDir.listFiles()!!.single()
        // 模拟写盘中被 force-stop：追加半行 JSON。
        file.appendText("{\"header\":{\"schemaVersion\":1,\"tMill")

        val read = store.readAll()
        assertEquals(2, read.size)
        assertEquals(listOf(10L, 20L), read.map { (it as PerfSample.Foreground).totalMs })
    }

    @Test
    fun `无法识别 kind 的行被跳过而不是整份报废`() = runBlocking {
        store.append(listOf(foreground(10)))
        val file = perfDir.listFiles()!!.single()
        file.appendText("{\"header\":{\"schemaVersion\":1,\"tMillis\":1,\"kind\":\"future_kind\"},\"x\":1}\n")
        file.appendText("完全不是 JSON 的一行\n")

        assertEquals(1, store.readAll().size)
    }

    @Test
    fun `目录位置被文件占住时全部操作静默降级不抛（E3）`() = runBlocking {
        // 把 perf 这个名字先做成普通文件 → mkdirs 必失败，模拟「建不出目录 / 无写权限」。
        perfDir.deleteRecursively()
        perfDir.writeText("occupied")

        store.append(listOf(foreground(10)))   // 不抛
        assertEquals(emptyList<PerfSample>(), store.readAll())
        assertEquals(0L, store.totalBytes())
        store.clear()                          // 不抛
    }

    @Test
    fun `电池温度 NaN 能编码也能读回（E23 的落盘保障）`() = runBlocking {
        val sample = PerfSample.Health(
            header = header(PerfSampleKind.HEALTH),
            thermalStatus = -1,
            thermalName = "unknown",
            batteryTempC = Double.NaN,
            scene = null,
        )
        store.append(listOf(sample))

        val read = store.readAll().single() as PerfSample.Health
        assertTrue("取不到温度时须原样保留 NaN", read.batteryTempC.isNaN())
        assertEquals("unknown", read.thermalName)
    }

    @Test
    fun `当天文件撑爆容量帽后停止追加并累计 dropped（E5）`() = runBlocking {
        perfDir.mkdirs()
        val todayName = PerfStore.fileNameFor(System.currentTimeMillis())
        File(perfDir, todayName).writeText("x".repeat((PerfStore.DIR_BYTE_CAP + 1).toInt()))

        store.append(listOf(foreground(1), foreground(2)))

        assertEquals(2, store.droppedSamples)
        // 当天文件没被删（只是不再追加）。
        assertTrue(File(perfDir, todayName).exists())
    }

    @Test
    fun `清空后目录里没有样本文件且占用归零`() = runBlocking {
        store.append(listOf(foreground(1)))
        assertTrue(store.totalBytes() > 0)

        store.clear()

        assertEquals(0L, store.totalBytes())
        assertEquals(emptyList<PerfSample>(), store.readAll())
    }

    @Test
    fun `空样本列表不建目录也不写文件`() = runBlocking {
        store.append(emptyList())
        assertTrue("空批次不该创建 perf 目录", !perfDir.exists())
    }
}
