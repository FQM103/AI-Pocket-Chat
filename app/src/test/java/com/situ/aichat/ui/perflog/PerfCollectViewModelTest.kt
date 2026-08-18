package com.situ.aichat.ui.perflog

import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.perf.BackupHealthProbe
import com.situ.aichat.diagnostics.perf.DeviceHealthProbe
import com.situ.aichat.diagnostics.perf.FakeBackupBuilder
import com.situ.aichat.diagnostics.perf.PerfChecklist
import com.situ.aichat.diagnostics.perf.PerfCollector
import com.situ.aichat.diagnostics.perf.PerfDeviceHeader
import com.situ.aichat.diagnostics.perf.PerfHeader
import com.situ.aichat.diagnostics.perf.PerfSample
import com.situ.aichat.diagnostics.perf.PerfSampleKind
import com.situ.aichat.diagnostics.perf.PerfStore
import com.situ.aichat.diagnostics.perf.ScaleNumbers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T2-6（图纸 2026-07-30 性能采集与量尺 §7）：[PerfCollectViewModel] 的导出 / 忙碌 / 统计编排。
 *
 * 断言从图纸 §3.6 与 §5 E2 的规格独立反推：
 * - 零样本导出返回 null 并给「还没采到数据」信号，**不生成空文件**；
 * - 导出前必先 flush（内存里攒着的样本不落盘，报告就缺最近这一段）；
 * - busy 期间动作幂等（连点不会造两份假包）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PerfCollectViewModelTest {

    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val collector = mockk<PerfCollector>(relaxed = true)
    private val store = mockk<PerfStore>(relaxed = true)
    private val deviceHealthProbe = mockk<DeviceHealthProbe>()
    private val backupHealthProbe = mockk<BackupHealthProbe>(relaxed = true)
    private val fakeBackupBuilder = mockk<FakeBackupBuilder>(relaxed = true)

    private val deviceHeader = PerfDeviceHeader(
        model = "m", manufacturer = "x", androidRelease = "16", sdkInt = 36, appVersionName = "0.1.0",
        refreshHz = 120, densityDpi = 440, screenWidthPx = 1, screenHeightPx = 2, maxHeapMb = 256,
        isLowRamDevice = false, locale = "zh-CN",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settingsRepository.appSettings } returns flowOf(AppSettings())
        every { deviceHealthProbe.deviceHeader(any()) } returns deviceHeader
        coEvery { store.readAll() } returns emptyList()
        coEvery { store.totalBytes() } returns 0L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = PerfCollectViewModel(
        settingsRepository, collector, store, deviceHealthProbe, backupHealthProbe, fakeBackupBuilder,
    )

    private fun foreground(t: Long) = PerfSample.Foreground(
        header = PerfHeader(1, t, PerfSampleKind.FOREGROUND),
        totalMs = 30,
        passes = emptyList(),
        scale = ScaleNumbers.UNAVAILABLE,
    )

    @Test
    fun `零样本导出返回 null 并给出空数据信号（E2）`() = runTest {
        val model = vm()

        val report = model.buildReport { it }

        assertNull("绝不生成空文件", report)
        assertEquals(PerfToast.EXPORT_EMPTY, model.toasts.value)
    }

    @Test
    fun `导出前一定先 flush（否则报告缺最近这一段）`() = runTest {
        coEvery { store.readAll() } returns listOf(foreground(1_000))
        val model = vm()

        model.buildReport { it }

        coVerify(atLeast = 1) { collector.flushNow() }
    }

    @Test
    fun `有样本时导出给出文件名与正文`() = runTest {
        coEvery { store.readAll() } returns listOf(foreground(1_000))
        val model = vm()

        val (fileName, text) = model.buildReport { id -> "标签-$id" }!!

        assertTrue("实为 $fileName", Regex("^性能报告_\\d{4}-\\d{4}\\.txt$").matches(fileName))
        assertTrue(text.startsWith("=== AI Pocket Chat 性能报告 ==="))
        assertTrue("清单标签要换成人读文案", text.contains("标签-${PerfChecklist.ID_COLD_START}"))
    }

    @Test
    fun `进页即读盘_统计与清单落进状态`() = runTest {
        coEvery { store.readAll() } returns listOf(foreground(1_000), foreground(2_000))
        coEvery { store.totalBytes() } returns 4_096L

        val state = vm().state.value

        assertEquals(mapOf(PerfSampleKind.FOREGROUND to 2), state.sampleCounts)
        assertEquals(4_096L, state.dirBytes)
        assertEquals(1_000L, state.oldestSampleMillis)
        assertEquals(2_000L, state.newestSampleMillis)
        assertEquals(6, state.checklist.size)
    }

    @Test
    fun `开关写设置并把这次点击也记成一次设置写盘`() = runTest {
        val model = vm()

        model.setEnabled(true)

        coVerify(exactly = 1) { settingsRepository.setPerfCollectEnabled(true) }
        coVerify(exactly = 1) {
            collector.recordSettingsWrite("perf_collect", "perf_collect_enabled", 1, 0L)
        }
    }

    @Test
    fun `清空会把假包也一起清掉（否则缓存里留着几十兆）`() = runTest {
        val model = vm()

        model.clearAll()

        coVerify(exactly = 1) { store.clear() }
        coVerify(exactly = 1) { fakeBackupBuilder.clear() }
    }

    @Test
    fun `动作跑完 busy 复位`() = runTest {
        val model = vm()

        model.buildAndProbeFakeBackup(1024)

        assertFalse("跑完必须复位，否则四个按钮永久禁用", model.state.value.busy)
        coVerify(exactly = 1) { fakeBackupBuilder.build(1024) }
    }
}
