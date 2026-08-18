package com.situ.aichat.ui.contextlog

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.LogDao
import com.situ.aichat.data.local.entity.LogEntryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.CallLogRecord
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.FailureRateAlert
import com.situ.aichat.diagnostics.FailureRateAudit
import com.situ.aichat.diagnostics.LogCategory
import com.situ.aichat.diagnostics.LogListRow
import com.situ.aichat.diagnostics.LogToolInfo
import com.situ.aichat.prompt.ContextSegment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import kotlin.math.roundToInt

/** 列表页 UI 状态（批 D·D-3 上下文日志）。[entries] 为轻投影行（性能·见 [LogListRow]）。 */
data class ContextLogUiState(
    val entries: List<LogListRow> = emptyList(),
    val category: LogCategory = LogCategory.ALL,
    val retentionCount: Int = 100,
    val detailEnabled: Boolean = false,
    val loaded: Boolean = false,
    /** 缓存命中率汇总（四小件·2026-07-16）；null = 当前筛选下无缓存数据 → 汇总卡整卡隐身。 */
    val cacheSummary: CacheSummary? = null,
    /** 近 24h 失败率告警（D-3 打磨·③）；空 = 无告警 → 告警条整条隐身（status 家族瞬态非常驻）。 */
    val alerts: List<FailureRateAlert> = emptyList(),
)

/** 缓存命中率汇总（四小件·2026-07-16）：[ratePercent] = 四舍五入整数百分比，[entryCount] = 参与统计的条目数。 */
data class CacheSummary(val ratePercent: Int, val entryCount: Int)

/**
 * 缓存汇总纯函数（四小件·2026-07-16·J5）：只统计 **isSuccess 且 hit+miss>0** 的条目——失败条目即使带
 * 缓存字段也不计（其用量不代表正常请求），无缓存数据的供应商（非 DeepSeek 类）天然被 hit+miss==0 挡掉。
 *
 * 取**加权**聚合 `sum(hit)/sum(hit+miss)` 而非逐条百分比平均：大请求权重天然更高，反映真实 token 成本。
 * 无匹配条目 → null（「健康即隐身」惯例：没数据就不占屏，不摆空态占位）。
 */
internal fun cacheSummaryOf(entries: List<LogListRow>): CacheSummary? {
    var hit = 0L
    var miss = 0L
    var count = 0
    for (e in entries) {
        if (!e.isSuccess) continue
        val h = e.cacheHitTokens
        val m = e.cacheMissTokens
        if (h + m <= 0) continue
        hit += h
        miss += m
        count++
    }
    if (count == 0) return null
    val pct = (hit * 100.0 / (hit + miss)).roundToInt()
    return CacheSummary(ratePercent = pct, entryCount = count)
}

/**
 * 上下文日志列表 + 保留设置 ViewModel（批 D·D-3）。Flow 实时刷新最近 500 条 + 按 [LogCategory] 现算过滤；
 * 保留条数 / detail 开关读 [SettingsRepository]，下调保留数即调 [ContextLogService.enforceRetentionLimit] 立即裁。
 * 界面只经此 VM 读写，绝不直接碰 DAO/DataStore（分层）。
 */
@HiltViewModel
class ContextLogViewModel @Inject constructor(
    private val logDao: LogDao,
    private val settingsRepository: SettingsRepository,
    private val contextLog: ContextLogService,
) : ViewModel() {

    private val category = MutableStateFlow(LogCategory.ALL)

    val state: StateFlow<ContextLogUiState> = combine(
        logDao.recent(RECENT_LIMIT),
        category,
        settingsRepository.appSettings,
    ) { list, cat, settings ->
        buildContextLogUiState(list, cat, settings, nowMillis = System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContextLogUiState())

    fun setCategory(c: LogCategory) { category.value = c }

    fun clearAll() = viewModelScope.launch { logDao.deleteAll() }

    // 走完整入口（SQL 三列 + 工具遥测参数预览重消毒·复核 R1），不再直调 DAO 的 SQL 步。
    fun purgeFullText() = viewModelScope.launch { contextLog.purgeSensitiveText() }

    fun delete(id: Long) = viewModelScope.launch { logDao.deleteById(id) }

    fun setRetentionCount(n: Int) = viewModelScope.launch {
        settingsRepository.setLogRetentionCount(n)
        contextLog.enforceRetentionLimit() // 下调保留数 → 立即裁一次（= iOS enforceRetentionLimit）
    }

    fun setDetailEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setLogDetailEnabled(enabled)
    }

    companion object {
        const val RECENT_LIMIT = 500

        /** 告警条最多列几行来源（对齐 mockup §1；再多也只是重复「后端在抖」的信号）。 */
        const val MAX_ALERT_LINES = 3
    }
}

private fun filterByCategory(list: List<LogListRow>, cat: LogCategory): List<LogListRow> = when {
    cat == LogCategory.ALL -> list
    cat == LogCategory.FAILED -> list.filter { !it.isSuccess }
    else -> list.filter { it.source in cat.sources }
}

/**
 * combine 链的纯装配体（四小件·2026-07-16 抽出·**行为零改**）：`(全量列表, 类别, 设置) → UiState`。
 *
 * 抽成顶层 `internal` 纯函数是为了可测——VM 的 `stateIn(viewModelScope)` 吃 `Dispatchers.Main`，
 * 而本仓库未接 `kotlinx-coroutines-test`（不为单卷擅自加依赖·PITFALLS 1e），Robolectric 下用
 * runBlocking 收流会与其主线程互锁。逻辑住哪层就在哪层测：把装配规则搬到纯函数即可确定性断言。
 *
 * 关键语义（J5）：`cacheSummary` 对**筛选后**列表算，跟随类别筛选——「聊天类命中率如何」要能单独看。
 *
 * 告警语义（D-3 打磨·③）：`alerts` 对**全量**列表算（健康是全局体检，不随类别筛选缩小视野），
 * 唯当前已在 [LogCategory.FAILED] 筛选时隐去（告警条的唯一去处就是它，已在就不再引路）；
 * 复用启动 Logcat 审计同一纯函数同阈值（[FailureRateAudit]·24h·失败率≥50% 且失败≥3）。
 * 数据近似口径：基于最近 [ContextLogViewModel.RECENT_LIMIT]=500 条投影而非全表——500 条远超日常
 * 24h 调用量，且告警条是引路标不是审计报表，取列表已有数据零额外查询。
 */
internal fun buildContextLogUiState(
    list: List<LogListRow>,
    cat: LogCategory,
    settings: AppSettings,
    nowMillis: Long,
): ContextLogUiState {
    val filtered = filterByCategory(list, cat)
    val alerts = if (cat == LogCategory.FAILED) {
        emptyList()
    } else {
        FailureRateAudit.computeFailureRateAlerts(
            list.map { CallLogRecord(it.source, it.isSuccess, it.timestampMillis) },
            nowMillis,
        )
            // 复核 R1-🔵4：封顶前按失败率降序——≥4 来源同时告警时先亮最疼的（纯函数输出为来源名升序，
            // 稳定排序保证同率下仍确定；仅影响本告警条，Logcat 审计维持原序）。
            .sortedByDescending { it.percent }
            .take(ContextLogViewModel.MAX_ALERT_LINES)
    }
    return ContextLogUiState(
        entries = filtered,
        category = cat,
        retentionCount = settings.sanitizedLogRetentionCount,
        detailEnabled = settings.logDetailEnabled,
        loaded = true,
        cacheSummary = cacheSummaryOf(filtered),
        alerts = alerts,
    )
}

/**
 * 日志详情 / 分段 / 全文页共用 ViewModel（批 D·D-3）。route 带 id → [SavedStateHandle] 取，按 id 取单条；
 * 分段从 `contextSegmentsJson` 现解（容错空表）。
 */
@HiltViewModel
class ContextLogDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    logDao: LogDao,
    private val json: Json,
) : ViewModel() {

    private val entryId: Long = savedStateHandle.get<String>(ARG_ID)?.toLongOrNull() ?: -1L

    val entry: StateFlow<LogEntryEntity?> = flow { emit(logDao.getById(entryId)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 解码结构化分段（空串/损坏 → 空表，绝不崩）。 */
    fun decodeSegments(jsonStr: String): List<ContextSegment> {
        if (jsonStr.isEmpty()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ContextSegment.serializer()), jsonStr)
        }.getOrDefault(emptyList())
    }

    /** 解码工具遥测（空串=旧行/非聊天来源、损坏 → null，详情页整节隐藏，绝不崩）。 */
    fun decodeToolInfo(jsonStr: String): LogToolInfo? = LogToolInfo.decode(json, jsonStr)

    companion object {
        const val ARG_ID = "id"
    }
}
