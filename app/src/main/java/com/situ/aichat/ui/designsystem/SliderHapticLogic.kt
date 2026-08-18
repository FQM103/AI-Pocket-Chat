package com.situ.aichat.ui.designsystem

/*
 * 滑块触觉的判定逻辑（乙 1·H3）——独立成文件的理由：它是**纯逻辑**（无 Compose、无框架），
 * 抽出来才能被 SliderHapticLogicTest 脱离设备逐条钉死边界规则；AppSlider.kt 那边则保持
 * 「只管画一条滑块」的单一职责（CLAUDE.md §2）。
 */

/** 滑块触觉的三种结果（乙 1·H3）：不震 / 逐格「嗒」/ 到头「咚」。 */
internal enum class SliderHapticEffect { None, Detent, Edge }

/**
 * 滑块触觉的判定快照：落在第几格（[notch]，连续模式恒 -1 = 不记格）+ 是否贴着值域端点（[atEdge]）。
 * 抽成纯数据 + 纯函数，是为了让「格变恰一记 / 贴边只响一次 / 连续模式中途零震」这些
 * 边界规则能脱离设备被单测钉死（§7 T1-B1）。
 */
internal data class SliderHapticSnapshot(val notch: Int, val atEdge: Boolean)

/** 把一个滑块值折算成判定快照。[steps]>0 才有吸附格（M3 语义：steps 指端点之间的**格点**数，故段数 = steps+1）。 */
internal fun sliderHapticSnapshot(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): SliderHapticSnapshot {
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span > 0f) ((value - valueRange.start) / span).coerceIn(0f, 1f) else 0f
    // 用 <=/>= 而非 ==：滑到头的浮点值可能差最后一个 ulp，== 会让「撞墙」偶发不响。
    return SliderHapticSnapshot(
        notch = if (steps > 0) Math.round(fraction * (steps + 1)) else -1,
        atEdge = value <= valueRange.start || value >= valueRange.endInclusive,
    )
}

/**
 * 两个快照之间该震哪一下。**撞墙优先于格变**：滑到最后一格时两者同时成立，
 * 此时「到头了」是更要紧的信息，且一次事件只许响一记（E8）。
 * 贴边→贴边（反复微调）不再响（E9）；连续模式 [steps]==0 时 notch 恒 -1 → 中途永不 Detent。
 */
internal fun sliderHapticEffect(
    prev: SliderHapticSnapshot,
    next: SliderHapticSnapshot,
): SliderHapticEffect = when {
    next.atEdge && !prev.atEdge -> SliderHapticEffect.Edge
    next.notch >= 0 && next.notch != prev.notch -> SliderHapticEffect.Detent
    else -> SliderHapticEffect.None
}
