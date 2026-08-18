package com.situ.aichat.ui.pet

/** 状态趋势方向（1:1 iOS `Trend`）。 */
enum class StatusTrend { UP, DOWN, STABLE }

/**
 * 4 项状态趋势快照（上次查看 → 当前）。心情环 [PetMoodRing]（主屏）与详情 sheet 趋势行共用；
 * 旧 Apple-Watch 四同心环（iOS systemColor `PetStatusRingsView`）已由 [PetMoodRing] 取代（Fable-5·S2）。
 */
data class PetStatusTrends(
    val hunger: StatusTrend = StatusTrend.STABLE,
    val cleanliness: StatusTrend = StatusTrend.STABLE,
    val happiness: StatusTrend = StatusTrend.STABLE,
    val health: StatusTrend = StatusTrend.STABLE,
)
