package com.situ.aichat.world.notify

import com.situ.aichat.data.model.AppSettings

/**
 * 世界到达通知的纯判定（W8 图纸 §3.3/§3.5·契约 §15/决策 33）：档位放行 / 每日封顶曲线 / 过期判定 /
 * 排队间隔判定——全是无副作用的纯函数，供 [WorldNotifyService] 的八道门与 T1 金标共用。常量按图纸 §9 锁死，
 * 一字不改（改一位 = 改「世界会不会吵你」的物理常数）。
 */
internal object WorldNotifyRules {

    /** 排队让路间隔（§9·门 7）：距上次任何出声通知不足此值 → 世界顺延，绝不连震。 */
    const val PACER_GAP_MS = 120_000L

    /** 过期线（§9·门 4）：到达后超过 12h 的「刚到」是谎言（小报会回顾），不弹。 */
    const val T_STALE_MS = 43_200_000L

    /** 世界自安静阈值（§9·§3.5·决策 33）：连续 ≥14 天没进世界 → 每日封顶降一档。 */
    const val QUIET_AFTER_DAYS = 14

    /** 溢出摘要计数台账的上限（§9）：远超任何单日现实溢出量，只作纯计数器的防爆封顶。 */
    const val OVERFLOW_COUNTER_CAP = 999

    /** 每日封顶两档（§3.5·决策 33·恒 ≥1 永不静音）。 */
    private const val DAILY_CAP_NORMAL = 2
    private const val DAILY_CAP_QUIET = 1

    /**
     * 档位放行（§3.3 门 2·决策 33）：`silent` 全跳；`gentle`（默认）只放「TA 到达你的城」（来访腿），跳「你到达目的地」
     * （用户腿）；`all` 全放。未知档位保守跳（不吵·同 [com.situ.aichat.world.bulletin.WorldBulletinService] 对未知鲜活度档的处理）。
     * @param isUserLeg true = 用户到达目的地腿；false = 角色来访到达腿。
     */
    fun tierAllows(tier: String, isUserLeg: Boolean): Boolean = when (tier) {
        AppSettings.WORLD_NOTIFICATION_ALL -> true
        AppSettings.WORLD_NOTIFICATION_GENTLE -> !isUserLeg // 克制档只放来访腿
        else -> false // silent + 未知档一律不吵
    }

    /**
     * 每日封顶曲线（§3.5·决策 33）：进世界满 [QUIET_AFTER_DAYS] 天没回 → 每日 1 条，否则 2 条；**永不返回 0**
     * （不静音·只降频·回世界即恢复）。摘要不受此影响照发。
     */
    fun dailyCapFor(daysSinceEntered: Long): Int =
        if (daysSinceEntered >= QUIET_AFTER_DAYS) DAILY_CAP_QUIET else DAILY_CAP_NORMAL

    /** 过期判定（§3.3 门 4）：到达至今超过 12h → true（跳）。恰 12h 仍放（闭区间上界，§5 E3）。 */
    fun isStale(now: Long, arriveAt: Long): Boolean = now - arriveAt > T_STALE_MS

    /** 排队判定（§3.3 门 7）：距上次出声不足 120s → true（顺延）。恰 120s 放行（§5 E4）。 */
    fun shouldDefer(now: Long, lastPostAt: Long): Boolean = now - lastPostAt < PACER_GAP_MS
}
