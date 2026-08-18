package com.situ.aichat.economy

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.notification.EconomyNotificationTier
import com.situ.aichat.notification.Notifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 角色经济动态通知编排（P1-40·仿 MomentNewPostNotifier）：维护循环收集到的事件 → 纯函数决策
 * （[EconomyNotificationPlanner]）→ 本地化文案 → [Notifier.postEconomySummary]（ECONOMY 静音渠道，
 * 固定 id 替换式聚合 1 条）。**只读展示层**——事件在账已落库之后多投一条通知，不碰任何钱。
 */
@Singleton
class EconomySummaryNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun notifySummary(events: List<EconomyEvent>, tier: EconomyNotificationTier) {
        when (val plan = EconomyNotificationPlanner.plan(events, tier)) {
            EconomyNotificationPlanner.Plan.None -> return
            is EconomyNotificationPlanner.Plan.Brief -> Notifier.postEconomySummary(
                context,
                title = context.getString(R.string.notif_economy_title),
                body = context.getString(R.string.notif_economy_brief, plan.characterCount),
            )
            is EconomyNotificationPlanner.Plan.Detailed -> Notifier.postEconomySummary(
                context,
                title = context.getString(R.string.notif_economy_title),
                body = plan.lines.joinToString("\n") { line ->
                    context.getString(
                        R.string.notif_economy_detail_line,
                        line.characterName,
                        line.events.joinToString(" · ") { eventText(it) },
                    )
                },
            )
        }
    }

    private fun eventText(event: EconomyEvent): String = when (event.kind) {
        EconomyEventKind.SALARY -> context.getString(R.string.econ_kind_salary, event.amount)
        EconomyEventKind.ONBOARDING -> context.getString(R.string.econ_kind_onboarding, event.amount)
        EconomyEventKind.BONUS -> context.getString(R.string.econ_kind_bonus, event.amount)
        EconomyEventKind.RENT -> context.getString(R.string.econ_kind_rent, event.amount)
        EconomyEventKind.RENT_ARREARS -> context.getString(R.string.econ_kind_rent_arrears, event.amount)
        EconomyEventKind.SCHEDULE_SPEND -> context.getString(R.string.econ_kind_schedule, event.amount)
    }
}
