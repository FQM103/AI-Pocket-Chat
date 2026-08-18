package com.situ.aichat.ui.world.starmap

import androidx.annotation.StringRes
import com.situ.aichat.R

/**
 * 星图语义 → 资源 id **纯查表**（W10 图纸 §4.7 语义映射表·键名锁死）。返回 `@StringRes` id，由 UI 端
 * `stringResource(...)` 落地——本对象零 Context / 零副作用，可独立单测。分类（分档 / 色彩表内外 / 轨迹）
 * 归 [StarmapModels] 纯函数（[closenessTier]/[colorPhraseOf]），本对象只把语义结果映到资源。
 */
object StarmapStrings {

    /** 13 色彩句键后缀 → 资源 id（[ColorPhrase.Keyed] 专用·后缀只可能是表内 13 值）。 */
    @StringRes
    fun colorResId(keySuffix: String): Int = when (keySuffix) {
        "curious" -> R.string.world_starmap_color_curious
        "kindred" -> R.string.world_starmap_color_kindred
        "grateful" -> R.string.world_starmap_color_grateful
        "protective" -> R.string.world_starmap_color_protective
        "missing" -> R.string.world_starmap_color_missing
        "respect" -> R.string.world_starmap_color_respect
        "awkward" -> R.string.world_starmap_color_awkward
        "rivalry" -> R.string.world_starmap_color_rivalry
        "relieved" -> R.string.world_starmap_color_relieved
        "closer" -> R.string.world_starmap_color_closer
        "distant" -> R.string.world_starmap_color_distant
        "heartbeat" -> R.string.world_starmap_color_heartbeat
        "crush" -> R.string.world_starmap_color_crush
        else -> R.string.world_starmap_color_curious // 不可达（Keyed 后缀恒来自 COLOR_PHRASE_KEYS）
    }

    /** 短四档（人物卡「TA 的来往」行 / 关系卡方向行）·closenessTier 1..4。 */
    @StringRes
    fun shortTierResId(tier: Int): Int = when (tier) {
        1 -> R.string.world_starmap_tier_1
        2 -> R.string.world_starmap_tier_2
        3 -> R.string.world_starmap_tier_3
        else -> R.string.world_starmap_tier_4
    }

    /** 你们四档（人物卡「和你 · …」tag / 列表在你身边行 / 节点 a11y）·closenessTier 1..4。 */
    @StringRes
    fun youTierResId(tier: Int): Int = when (tier) {
        1 -> R.string.world_starmap_youtier_1
        2 -> R.string.world_starmap_youtier_2
        3 -> R.string.world_starmap_youtier_3
        else -> R.string.world_starmap_youtier_4
    }

    /** 轨迹词·warming/cooling/其余→stable（§4.7 表外按 stable）。 */
    @StringRes
    fun trajectoryResId(trajectory: String): Int = when (trajectory) {
        "warming" -> R.string.world_starmap_traj_warming
        "cooling" -> R.string.world_starmap_traj_cooling
        else -> R.string.world_starmap_traj_stable
    }

    /** 相对日四词。 */
    @StringRes
    fun relativeDayResId(day: RelativeDay): Int = when (day) {
        RelativeDay.TODAY -> R.string.world_starmap_day_today
        RelativeDay.YESTERDAY -> R.string.world_starmap_day_yesterday
        RelativeDay.BEFORE -> R.string.world_starmap_day_before
        RelativeDay.RECENT -> R.string.world_starmap_day_recent
    }
}
