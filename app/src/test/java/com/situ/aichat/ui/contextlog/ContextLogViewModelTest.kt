package com.situ.aichat.ui.contextlog

import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.diagnostics.LogCategory
import com.situ.aichat.diagnostics.LogListRow
import com.situ.aichat.diagnostics.LogSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 缓存命中率汇总（四小件·2026-07-16·件②）行为测试。
 *
 * 两层：① [cacheSummaryOf] 的聚合口径（T2-5·E11-E13）；② [buildContextLogUiState] 的装配联动
 * （T2-6·E14）——证明汇总跟着**筛选后**列表走（J5），而非全量列表。
 *
 * 断言从图纸 J5 规格**独立反推**（加权 `sum(hit)/sum(hit+miss)`，非逐条平均），非照搬实现输出。
 * 测纯装配体而非驱动 VM 流：`stateIn(viewModelScope)` 吃 `Dispatchers.Main`，本仓库未接
 * kotlinx-coroutines-test（不为单卷擅自加依赖·PITFALLS 1e），Robolectric 下 runBlocking 收流
 * 与其主线程互锁（实测已验证·见图纸 §11 D-4）。
 */
class ContextLogViewModelTest {

    // ── 造数 ──

    private fun entry(
        id: Long,
        hit: Int = 0,
        miss: Int = 0,
        success: Boolean = true,
        source: String = LogSource.CHAT,
    ) = LogListRow(
        id = id, timestampMillis = 1_000 + id, characterName = "小雨", modelName = "deepseek-chat",
        isSuccess = success, source = source, messageCount = 3,
        cacheHitTokens = hit, cacheMissTokens = miss,
    )

    // ── T2-5：聚合口径 ──

    @Test
    fun 空表_无汇总() {
        assertNull(cacheSummaryOf(emptyList()))
    }

    @Test
    fun 全部无缓存数据_无汇总_卡隐身() {
        // E11：非 DeepSeek 类供应商 → hit+miss 恒 0 → 整卡不渲染。
        assertNull("无缓存数据时汇总为 null", cacheSummaryOf(listOf(entry(1), entry(2), entry(3))))
    }

    @Test
    fun 混合表_只计成功且有缓存的子集() {
        // E12：失败条目即使带缓存字段也不计；成功但无缓存的不计。
        val entries = listOf(
            entry(1, hit = 80, miss = 20), // 计入
            entry(2, hit = 999, miss = 1, success = false), // 失败 → 不计（若误计，命中率会被拉到 ~99%）
            entry(3), // 成功但无缓存 → 不计（若误计 count，条目数会变 3）
        )
        val summary = cacheSummaryOf(entries)
        assertEquals("只 1 条参与统计", 1, summary?.entryCount)
        assertEquals("命中率取自唯一有效条目 80/100", 80, summary?.ratePercent)
    }

    @Test
    fun 加权聚合_大请求权重更高_与逐条平均可区分() {
        // J5 核心判据：大条 0/1000（0%）+ 小条 10/10（100%）。
        // 加权 = 10/1020 ≈ 1%；逐条平均 = (0+100)/2 = 50%。断言 1% 即证明走的是加权口径。
        val entries = listOf(entry(1, hit = 0, miss = 1000), entry(2, hit = 10, miss = 0))
        assertEquals("加权口径而非逐条平均", 1, cacheSummaryOf(entries)?.ratePercent)
    }

    @Test
    fun 加权聚合_九十比十与十比九十得五十() {
        // 图纸 §7 点名用例：两条 90/10 与 10/90 → (90+10)/200 = 50%。
        val entries = listOf(entry(1, hit = 90, miss = 10), entry(2, hit = 10, miss = 90))
        val summary = cacheSummaryOf(entries)
        assertEquals(50, summary?.ratePercent)
        assertEquals(2, summary?.entryCount)
    }

    @Test
    fun 全未命中_显示0并且卡仍渲染() {
        // E13：0% 是有效观测（恰是要看的问题信号），不能因 hit==0 就隐身。
        val summary = cacheSummaryOf(listOf(entry(1, hit = 0, miss = 500)))
        assertEquals("卡要渲染", 1, summary?.entryCount)
        assertEquals(0, summary?.ratePercent)
    }

    @Test
    fun 全命中_显示100() {
        assertEquals(100, cacheSummaryOf(listOf(entry(1, hit = 500, miss = 0)))?.ratePercent)
    }

    @Test
    fun 取整为四舍五入() {
        // §9 锁定：roundToInt。2/3 = 66.67% → 67（截断口径会给 66）；1/3 = 33.33% → 33。
        assertEquals(67, cacheSummaryOf(listOf(entry(1, hit = 2, miss = 1)))?.ratePercent)
        assertEquals(33, cacheSummaryOf(listOf(entry(1, hit = 1, miss = 2)))?.ratePercent)
    }

    // ── T2-6：装配联动（E14） ──

    private val all = listOf(
        entry(1, hit = 80, miss = 20, source = LogSource.CHAT),
        entry(2, hit = 10, miss = 90, source = LogSource.VOICE_CALL),
    )

    @Test
    fun 筛选ALL_汇总覆盖全部条目() {
        val state = buildContextLogUiState(all, LogCategory.ALL, AppSettings(), nowMillis = FIXED_NOW)
        assertEquals("两条都参与", 2, state.cacheSummary?.entryCount)
        assertEquals("加权 (80+10)/200 = 45%", 45, state.cacheSummary?.ratePercent)
    }

    @Test
    fun 筛选联动_汇总只算筛选后的子集() {
        // E14 的要害：若误把**全量**列表喂给 cacheSummaryOf，这里会得到 45% / 2 条。
        val state = buildContextLogUiState(all, LogCategory.CHAT, AppSettings(), nowMillis = FIXED_NOW)
        assertEquals("CHAT 只剩 1 条", 1, state.entries.size)
        assertEquals("汇总只算聊天条目", 1, state.cacheSummary?.entryCount)
        assertEquals("汇总 = 80% 而非全量的 45%", 80, state.cacheSummary?.ratePercent)
    }

    @Test
    fun 筛选联动_子集无缓存数据时汇总为null() {
        // 「健康即隐身」在筛选态同样成立：语音类无缓存数据 → 切过去汇总卡消失。
        val entries = listOf(
            entry(1, hit = 80, miss = 20, source = LogSource.CHAT),
            entry(2, source = LogSource.VOICE_CALL), // 无缓存数据
        )
        val state = buildContextLogUiState(entries, LogCategory.VOICE_CALL, AppSettings(), nowMillis = FIXED_NOW)
        assertEquals("语音类只剩 1 条", 1, state.entries.size)
        assertNull("该子集无缓存数据 → 汇总卡隐身", state.cacheSummary)
    }

    @Test
    fun 筛选FAILED_失败条目恒不计入汇总_卡隐身() {
        // E12 与筛选的交叉：FAILED 视图下全是失败条目 → 即使带缓存字段也一条不计 → 卡隐身。
        val entries = listOf(entry(1, hit = 999, miss = 1, success = false))
        val state = buildContextLogUiState(entries, LogCategory.FAILED, AppSettings(), nowMillis = FIXED_NOW)
        assertEquals("FAILED 视图有 1 条", 1, state.entries.size)
        assertNull("失败条目不参与汇总 → 卡隐身", state.cacheSummary)
    }

    @Test
    fun 装配体既有字段不受影响() {
        // 「只搬不改」回归钉：抽出纯装配体后，既有四个字段的装配行为逐一不变。
        val state = buildContextLogUiState(all, LogCategory.ALL, AppSettings(), nowMillis = FIXED_NOW)
        assertEquals(LogCategory.ALL, state.category)
        assertEquals(AppSettings().sanitizedLogRetentionCount, state.retentionCount)
        assertEquals(AppSettings().logDetailEnabled, state.detailEnabled)
        assertEquals("loaded 恒 true（装配即已加载）", true, state.loaded)
        assertEquals("entries 原样透传", all, state.entries)
        assertEquals("造数时间戳远在 24h 窗外 → 告警恒空（既有用例不受 ③ 影响）", 0, state.alerts.size)
    }

    // ── ③ 失败率告警装配（D-3 打磨·断言从 mockup §1 规格反推：24h 窗 · ≥50% 且失败 ≥3 · 封顶 3 行） ──

    /** 窗内一小时前的行（告警造数专用；[at] 可拨到窗外验排除）。 */
    private fun rowAt(id: Long, source: String, success: Boolean, at: Long = FIXED_NOW - 3_600_000L) =
        LogListRow(id = id, timestampMillis = at, isSuccess = success, source = source)

    private fun failCluster(source: String, fails: Int, oks: Int, idBase: Long): List<LogListRow> =
        (0 until fails).map { rowAt(idBase + it, source, success = false) } +
            (0 until oks).map { rowAt(idBase + 100 + it, source, success = true) }

    @Test
    fun 告警_三失败过阈值_成条目并带比例() {
        val state = buildContextLogUiState(
            failCluster(LogSource.STORY_GENERATION, fails = 3, oks = 2, idBase = 1),
            LogCategory.ALL, AppSettings(), nowMillis = FIXED_NOW,
        )
        assertEquals(1, state.alerts.size)
        assertEquals(LogSource.STORY_GENERATION, state.alerts[0].source)
        assertEquals(3, state.alerts[0].failures)
        assertEquals(5, state.alerts[0].total)
        assertEquals("3/5 = 60%", 60, state.alerts[0].percent)
    }

    @Test
    fun 告警_不足三次失败不成条() {
        // 2 失败/2 总量 = 100% 但失败次数未达 3 → 不告警（防单次抖动就挂横幅）。
        val state = buildContextLogUiState(
            failCluster(LogSource.STORY_GENERATION, fails = 2, oks = 0, idBase = 1),
            LogCategory.ALL, AppSettings(), nowMillis = FIXED_NOW,
        )
        assertEquals(0, state.alerts.size)
    }

    @Test
    fun 告警_窗外旧失败不计() {
        val stale = (0 until 5).map {
            rowAt(it.toLong(), LogSource.STORY_GENERATION, success = false, at = FIXED_NOW - 25 * 3_600_000L)
        }
        val state = buildContextLogUiState(stale, LogCategory.ALL, AppSettings(), nowMillis = FIXED_NOW)
        assertEquals("25 小时前的失败不进 24h 体检", 0, state.alerts.size)
    }

    @Test
    fun 告警_按全量列表算_不随类别筛选缩水() {
        // 站在 CHAT 筛选下，故事的失败簇仍要亮（健康是全局体检）。
        val state = buildContextLogUiState(
            failCluster(LogSource.STORY_GENERATION, fails = 3, oks = 0, idBase = 1) + rowAt(50, LogSource.CHAT, success = true),
            LogCategory.CHAT, AppSettings(), nowMillis = FIXED_NOW,
        )
        assertEquals(1, state.alerts.size)
        assertEquals(LogSource.STORY_GENERATION, state.alerts[0].source)
    }

    @Test
    fun 告警_FAILED筛选下隐身() {
        // 告警条的唯一去处就是「失败」筛选，已在就不再引路。
        val state = buildContextLogUiState(
            failCluster(LogSource.STORY_GENERATION, fails = 3, oks = 0, idBase = 1),
            LogCategory.FAILED, AppSettings(), nowMillis = FIXED_NOW,
        )
        assertEquals(0, state.alerts.size)
    }

    @Test
    fun 告警_超过三个来源封顶三行() {
        val rows = failCluster(LogSource.STORY_GENERATION, 3, 0, 1) +
            failCluster(LogSource.DIARY_GENERATION, 3, 0, 200) +
            failCluster(LogSource.SCHEDULE_GENERATION, 3, 0, 400) +
            failCluster(LogSource.MOMENT_POST, 3, 0, 600)
        val state = buildContextLogUiState(rows, LogCategory.ALL, AppSettings(), nowMillis = FIXED_NOW)
        assertEquals("封顶 MAX_ALERT_LINES=3", ContextLogViewModel.MAX_ALERT_LINES, state.alerts.size)
    }

    private companion object {
        /** 固定 now（告警 24h 窗判定确定性；既有造数 timestamp=1_000+id 远在窗外恒不告警）。 */
        const val FIXED_NOW = 2_000_000_000_000L
    }
}
