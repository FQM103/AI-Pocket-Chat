package com.situ.aichat.world.live

import com.situ.aichat.data.model.AppSettings

/**
 * 世界「观察时点亮」两时刻的每日额度池（W12 图纸 §3.5 / §9·决策 43）：偷听 / 首访风物志按鲜活度档位映射 cap，
 * 复用 W5 [com.situ.aichat.world.bulletin.WorldLlmBudget] 事务台账（`tryConsume(类目串, epochDay, cap)`·
 * `cap≤0` 恒拒 = 省档天然关）。**先扣后调**语义由调用方（各 service）与 budget 承担，本对象只提供纯映射。
 *
 * **额度数值与类目串锁死**（图纸 §9 禁改）：偷听 省 0 / 标准 15 / 豪华 50（保险丝）；风物志 省 0 / 标准 6 /
 * 豪华 12（保险丝）。类目串 [EAVES]/[LORE] 与 W5 `"bulletin"` 同框架、同台账不同 category 独立计数、互不干扰。
 * 开机小报的 3/12 仍留在 [com.situ.aichat.world.bulletin.WorldBulletinService]（W5 文件·本块零碰）。
 */
object WorldVividnessPools {

    /** 偷听预算类目串（§9 锁死）。 */
    const val EAVES = "eaves"

    /** 首访风物志预算类目串（§9 锁死）。 */
    const val LORE = "lore"

    /** 偷听每日 cap：lite 0 / standard 15 / rich 50（保险丝）·未知档 → 0（= 省档零额度语义·决策 43①）。 */
    fun eavesdropCap(tier: String): Int = when (tier) {
        AppSettings.WORLD_VIVIDNESS_STANDARD -> EAVES_STANDARD_CAP
        AppSettings.WORLD_VIVIDNESS_RICH -> EAVES_RICH_CAP
        else -> 0 // lite 与任何非法档 = 省档零额度
    }

    /** 首访风物志每日 cap：lite 0 / standard 6 / rich 12（保险丝）·未知档 → 0（决策 43②）。 */
    fun loreCap(tier: String): Int = when (tier) {
        AppSettings.WORLD_VIVIDNESS_STANDARD -> LORE_STANDARD_CAP
        AppSettings.WORLD_VIVIDNESS_RICH -> LORE_RICH_CAP
        else -> 0
    }

    private const val EAVES_STANDARD_CAP = 15 // 决策 43①·§9 禁改
    private const val EAVES_RICH_CAP = 50 // 豪华保险丝·决策 43①·§9 禁改
    private const val LORE_STANDARD_CAP = 6 // 决策 43②·§9 禁改
    private const val LORE_RICH_CAP = 12 // 豪华保险丝·决策 43②·§9 禁改
}
