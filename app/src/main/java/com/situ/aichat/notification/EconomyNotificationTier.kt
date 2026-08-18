package com.situ.aichat.notification

/**
 * 角色经济动态通知三档（P1-40·拍板 A+内容可选·安卓超越 iOS：iOS 对发薪/房租/奖金零通知）。
 * ECONOMY 渠道为 IMPORTANCE_LOW 静音（批0 拍板：日程消费近乎每日，出声会扰）——通知价值在「留痕可回看」，
 * 不在打断；维护只在回前台跑（无后台路径），故 tier≠[OFF] 即发、不做前台抑制。
 * 钱包卡「新变动」高亮独立于本档位（[OFF] 时只剩高亮）。
 */
enum class EconomyNotificationTier(val raw: String) {
    /** 详细：每角色一行、含金额（「{角色}：发薪 +500 · 房租 -1200」）。 */
    DETAILED("detailed"),

    /** 简要（**默认**）：不含金额（「N 位角色有工资、房租等新变动」）。 */
    BRIEF("brief"),

    /** 关：不发通知（钱包卡高亮仍在）。 */
    OFF("off");

    companion object {
        /** 未知 / null → 默认简要 [BRIEF]。 */
        fun fromRaw(raw: String?): EconomyNotificationTier =
            entries.firstOrNull { it.raw == raw } ?: BRIEF
    }
}
