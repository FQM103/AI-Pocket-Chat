package com.situ.aichat.data.model

/**
 * 红包金额规则（1:1 iOS `Services/RedPacketAmountCatalog`）。
 *
 * ## 金额范围
 * - 最小 1 金币（防手滑发 0 红包）、最大 20000 金币（防手滑超大额，另受钱包余额限制）。
 *
 * ## 吉利数字预设（按心意档位三组，UI chip / 角色 LLM 按经济档位挑）
 * - 小心意：8 / 18 / 28（日常）
 * - 用心的选择：66 / 88 / 168 / 188（节日常用）
 * - 珍贵的心意：520 / 666 / 888 / 1314（表白 / 仪式感满格）
 */
object RedPacketAmountCatalog {

    // ── 硬范围 ──
    const val MIN_AMOUNT = 1
    const val MAX_AMOUNT = 20000

    /** 金额是否在合法范围 [MIN_AMOUNT, MAX_AMOUNT] 内（1:1 iOS `isValidAmount`）。 */
    fun isValidAmount(amount: Int): Boolean = amount in MIN_AMOUNT..MAX_AMOUNT

    // ── 吉利数字（按档位分组，1:1 iOS smallAmounts/mediumAmounts/preciousAmounts） ──

    /** 小心意档（≤50 金币，日常红包首选）。 */
    val SMALL_AMOUNTS = listOf(8, 18, 28)

    /** 用心档（51-200，节日红包核心区间）。 */
    val MEDIUM_AMOUNTS = listOf(66, 88, 168, 188)

    /** 珍贵档（>200，重要节日 / 纪念日）。 */
    val PRECIOUS_AMOUNTS = listOf(520, 666, 888, 1314)

    /** 全部吉利数字，按价格升序（三组拼接，1:1 iOS `auspiciousAmounts`）。 */
    val auspiciousAmounts: List<Int> get() = SMALL_AMOUNTS + MEDIUM_AMOUNTS + PRECIOUS_AMOUNTS

    // ── 档位归类 ──

    /**
     * 把任意金额归到三档之一（1:1 iOS `tier(for:)`，与 [GiftCardData.tier] 对齐，保证分档文案统一）。
     * - `<51` → 小心意；`51..200` → 用心的选择；`>200` → 珍贵的心意。
     *
     * llmRepresentation 里红包不露分档（也不露数字），此函数供 UI / 日志 / 决策文案用。
     */
    fun tier(amount: Int): String = when {
        amount < 51 -> "小心意"
        amount <= 200 -> "用心的选择"
        else -> "珍贵的心意"
    }

    /** 是否为吉利数字（UI 上有 chip 的那些，1:1 iOS `isAuspicious`）。 */
    fun isAuspicious(amount: Int): Boolean = amount in auspiciousAmounts
}
