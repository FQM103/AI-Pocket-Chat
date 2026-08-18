package com.situ.aichat.story

/**
 * 故事生成进度的**唯一口径源**（灵动岛卷一图纸 §3.1·全部纯函数·锁定值）。
 *
 * 药丸（前台通知）/ 阅读器遮罩 / 书架卡片三处显示全部经本 object 换算——同一阶段在三处
 * 必然给出同一段号、同一总进度、同一文案，杜绝「三处各算各的」漂移。
 *
 * 纯映射，绝不碰 Flow / DB / 通知（编排在 [StoryGenerationTaskManager]，渲染在 UI 层）。
 * 设 `internal` 便于 T1 单测直接锁死每个魔法值（`StoryProgressModelTest`）。
 */
internal object StoryProgressModel {

    /** 四段权重（锁定 15/60/17/8）：构思 / 撰写 / 整理 / 归档。撰写段独占 60% —— 它是全程唯一的连续真进度。 */
    val SEGMENT_WEIGHTS = intArrayOf(15, 60, 17, 8)

    /** 各段在总进度上的起点（锁定）；[StoryGenPhase.DONE] = 1.0，不在表内。 */
    val SEG_START = doubleArrayOf(0.0, 0.15, 0.75, 0.92)

    /** 撰写段在总进度里占的跨度（= [SEGMENT_WEIGHTS] 第 2 段 / 100）。 */
    private const val WRITING_SPAN = 0.60

    /** 药丸推送最小间隔（锁定 2s）：只闸 binder notify，页内 StateFlow 直读不受闸。 */
    private const val PILL_PUSH_INTERVAL_MS = 2_000L

    /** 药丸推送最小进度增量（锁定 0.01）。 */
    private const val PILL_PUSH_MIN_DELTA = 0.01

    /** 章节预期字数兜底（锁定 2000）：`chapterLengthPreference` 为脏数据（≤0）时用（E8）。 */
    private const val FALLBACK_EXPECTED_CHARS = 2000

    /** 显示段号（0..3）：[StoryGenPhase.ARCHIVING] 与 [StoryGenPhase.DONE] 同占第 4 段（显示只有 4 段）。 */
    fun segIndex(phase: StoryGenPhase): Int = when (phase) {
        StoryGenPhase.PREPARING -> 0
        StoryGenPhase.WRITING -> 1
        StoryGenPhase.FINALIZING -> 2
        StoryGenPhase.ARCHIVING -> 3
        StoryGenPhase.DONE -> 3
    }

    /**
     * 总进度 fraction（0–1·锁定）。只有撰写段随 [writingFraction] 连续推进，其余阶段是定值台阶——
     * 思考模型静默期就诚实地停在构思段 0.0，不假爬（E1）。
     *
     * @param writingFraction 已收正文字数 ÷ 预期字数；超写钳 1.0 停在段尾 0.75（E4）。
     */
    fun overall(phase: StoryGenPhase, writingFraction: Double): Double = when (phase) {
        StoryGenPhase.PREPARING -> 0.0
        StoryGenPhase.WRITING -> SEG_START[1] + WRITING_SPAN * writingFraction.coerceIn(0.0, 1.0)
        StoryGenPhase.FINALIZING -> SEG_START[2]
        StoryGenPhase.ARCHIVING -> SEG_START[3]
        StoryGenPhase.DONE -> 1.0
    }

    /** 阶段文案（锁定·逐字）：页内与通知 contentText 同源。纯 UI 显示，不进任何 prompt。 */
    fun phaseLabel(phase: StoryGenPhase, chapterNumber: Int): String = when (phase) {
        StoryGenPhase.PREPARING -> "正在构思剧情…"
        StoryGenPhase.WRITING -> "正在撰写正文…"
        StoryGenPhase.FINALIZING -> "正在整理成章…"
        StoryGenPhase.ARCHIVING -> "正在记下这段故事…"
        StoryGenPhase.DONE -> "第 $chapterNumber 章写好了"
    }

    /** 二字短词（锁定·逐字）：进药丸的 `shortCriticalText` 位（官方短文案位只容得下两字）。 */
    fun shortLabel(phase: StoryGenPhase): String = when (phase) {
        StoryGenPhase.PREPARING -> "构思"
        StoryGenPhase.WRITING -> "撰写"
        StoryGenPhase.FINALIZING -> "整理"
        StoryGenPhase.ARCHIVING -> "归档"
        StoryGenPhase.DONE -> "完成"
    }

    /** 撰写段预期字数：脏数据（≤0）兜底 [FALLBACK_EXPECTED_CHARS]（E8）。 */
    fun expectedChars(chapterLengthPreference: Int): Int =
        if (chapterLengthPreference <= 0) FALLBACK_EXPECTED_CHARS else chapterLengthPreference

    /**
     * 药丸是否该推送（节流闸·锁定）：**身份变**（换书 / 换章）或阶段变必推；同阶段同一章内须**同时**
     * 满足「距上次 ≥2s」与「进度涨 ≥0.01」。
     *
     * [identityChanged] 这道门治的是「药丸认错主角」：领跑的那本书失败/被取消被移出映射后，接棒者若恰好
     * 处于同一阶段且进度更低（`leadForegroundProgress` 取进度最高一路，换人时进度会**降**），
     * 「涨 ≥0.01」恒假 ⇒ 永不推送 ⇒ 药丸继续显示那本已经不在生成的书（连深链也指向旧书）。
     * 身份 = (storyId, chapterNumber)，由调用方比对。
     *
     * 只作用于推 controller（→ binder notify），页内 `activeGenerations` 直读不受此闸——
     * 页内 150ms 流畅零成本，系统通知不被 6-7 次/秒的流式预览刷爆。
     * 时间源须用 `SystemClock.elapsedRealtime()`（防用户调表）。
     */
    fun shouldPushToPill(
        lastOverall: Double,
        lastPhase: StoryGenPhase,
        lastAtMillis: Long,
        newOverall: Double,
        newPhase: StoryGenPhase,
        nowMillis: Long,
        identityChanged: Boolean = false,
    ): Boolean {
        if (identityChanged || newPhase != lastPhase) return true
        return (nowMillis - lastAtMillis >= PILL_PUSH_INTERVAL_MS) &&
            (newOverall - lastOverall >= PILL_PUSH_MIN_DELTA)
    }
}
