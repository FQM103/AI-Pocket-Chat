package com.situ.aichat.ui.perflog

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.BuildConfig
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.perf.BackupHealthProbe
import com.situ.aichat.diagnostics.perf.ChecklistItem
import com.situ.aichat.diagnostics.perf.FakeBackupBuilder
import com.situ.aichat.diagnostics.perf.DeviceHealthProbe
import com.situ.aichat.diagnostics.perf.PerfChecklist
import com.situ.aichat.diagnostics.perf.PerfCollector
import com.situ.aichat.diagnostics.perf.PerfReportFormat
import com.situ.aichat.diagnostics.perf.PerfSample
import com.situ.aichat.diagnostics.perf.PerfSettingsSites
import com.situ.aichat.diagnostics.perf.PerfStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 采集页状态（图纸 §3.6 逐字锁定字段）。 */
data class PerfCollectUiState(
    val enabled: Boolean = false,
    val sampleCounts: Map<String, Int> = emptyMap(),
    val checklist: List<ChecklistItem> = emptyList(),
    val dirBytes: Long = 0L,
    val oldestSampleMillis: Long? = null,
    val newestSampleMillis: Long? = null,
    val busy: Boolean = false,
)

/** 采集页要给用户的一次性反馈（都是短 Toast，文案由屏幕侧取资源）。 */
enum class PerfToast { EXPORT_EMPTY, EXPORT_FAILED, PROBE_DONE, FAKE_BACKUP_DONE, FAKE_BACKUP_FAILED }

/**
 * 采集页 VM（图纸 §2.1）。界面只经这里，绝不直接碰 [PerfStore]（CLAUDE.md §2 分层）。
 *
 * 导出前一定先 [PerfCollector.flushNow]：内存里攒着的样本不落盘，导出的报告就会缺最近这一段——
 * 而最近这一段往往正是用户刚做完想给你看的那件事。
 */
@HiltViewModel
class PerfCollectViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val collector: PerfCollector,
    private val store: PerfStore,
    private val deviceHealthProbe: DeviceHealthProbe,
    private val backupHealthProbe: BackupHealthProbe,
    private val fakeBackupBuilder: FakeBackupBuilder,
) : ViewModel() {

    private val _state = MutableStateFlow(PerfCollectUiState())
    val state: StateFlow<PerfCollectUiState> = _state.asStateFlow()

    private val _toasts = MutableStateFlow<PerfToast?>(null)
    val toasts: StateFlow<PerfToast?> = _toasts.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.appSettings.map { it.perfCollectEnabled }.collect { enabled ->
                _state.value = _state.value.copy(enabled = enabled)
            }
        }
        refresh()
    }

    /** 重新读盘刷新统计（进页、每次动作之后）。 */
    fun refresh() {
        viewModelScope.launch {
            collector.flushNow()
            applySamples(store.readAll(), store.totalBytes())
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPerfCollectEnabled(enabled)
            // 尺 4 的第二处埋点：开关本身也是一次设置写盘（一次点击 = 一次全量重写）。
            collector.recordSettingsWrite(
                screen = PerfSettingsSites.SCREEN_PERF_COLLECT,
                key = PerfSettingsSites.KEY_PERF_COLLECT_ENABLED,
                writesInGesture = 1,
                gestureMs = 0L,
            )
            refresh()
        }
    }

    /**
     * 渲染导出文本。零样本 → null（调用方提示「还没采到数据」，**不生成空文件**·§5 E2）。
     * 返回 (文件名, 正文)。
     */
    suspend fun buildReport(labelOf: (String) -> String): Pair<String, String>? {
        collector.flushNow()
        val samples = store.readAll()
        if (samples.isEmpty()) {
            _toasts.value = PerfToast.EXPORT_EMPTY
            return null
        }
        val now = System.currentTimeMillis()
        val checklist = PerfChecklist.evaluate(samples).map { it.copy(label = labelOf(it.label)) }
        val text = PerfReportFormat.render(
            header = deviceHealthProbe.deviceHeader(BuildConfig.VERSION_NAME),
            samples = samples,
            checklist = checklist,
            nowMillis = now,
        )
        return PerfReportFormat.fileNameOf(now) to text
    }

    /** 备份体检（只读）。[uri] = 用户挑的备份文件。 */
    fun probeBackup(uri: Uri) = runBusy {
        backupHealthProbe.probe(uri)
        _toasts.value = PerfToast.PROBE_DONE
    }

    /** 造假包并顺手体检它（造出来不体检等于没造）。 */
    fun buildAndProbeFakeBackup(targetBytes: Long) = runBusy {
        val file = fakeBackupBuilder.build(targetBytes)
        if (file == null) {
            _toasts.value = PerfToast.FAKE_BACKUP_FAILED
        } else {
            backupHealthProbe.probe(file)
            _toasts.value = PerfToast.FAKE_BACKUP_DONE
        }
    }

    fun clearAll() = runBusy {
        store.clear()
        fakeBackupBuilder.clear()
    }

    fun consumeToast() {
        _toasts.value = null
    }

    // MARK: - 私有

    /** busy 期间动作幂等：已经在跑就直接忽略，避免连点造出两份假包 / 两次体检。 */
    private fun runBusy(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            runCatching { block() }
            collector.flushNow()
            applySamples(store.readAll(), store.totalBytes())
            _state.value = _state.value.copy(busy = false)
        }
    }

    private fun applySamples(samples: List<PerfSample>, dirBytes: Long) {
        val times = samples.map { it.header.tMillis }
        _state.value = _state.value.copy(
            sampleCounts = samples.groupingBy { it.header.kind }.eachCount(),
            checklist = PerfChecklist.evaluate(samples),
            dirBytes = dirBytes,
            oldestSampleMillis = times.minOrNull(),
            newestSampleMillis = times.maxOrNull(),
        )
    }

    companion object {
        /** 假包默认目标大小（先造一个「明显偏大但不至于必崩」的量级，不够再加）。 */
        const val FAKE_BACKUP_TARGET_BYTES = 64L * 1024 * 1024
    }
}
