package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.model.AppSettings

/**
 * 宠物饿/病提醒的「到点真叫」预测器（13.7c；安卓超越 iOS·用户拍板）。
 *
 * iOS（`AppBootstrapService.schedulePetNotificationsIfNeeded`）是**反应式**的：你开 App 那刻若宠物已
 * `hunger≥70` 或已 `sick`，就排一条 30 分钟后的提醒；你不开 App 它永远不叫。安卓改成**预测式精确闹钟**：
 * 按 [PetCareService.applyDecay] 的衰减模型算出「预计几点会饿到 70 / 几点进 sick」的绝对时刻，提前排闹钟，
 * 于是哪怕你两天没开 App，它也会在该饿的点准时叫你。喂食/护理改变状态后由 [PetReminderScheduler] 取消重排。
 *
 * 纯函数（entity + settings + now → plan 或 null），便于确定性单测（断言反推 iOS 阈值 70/5 天 + 衰减速率）。
 * 类别选择 1:1 iOS：`sick` 优先于 `hungry`（iOS 命中 sick 分支即不再看 hunger）；这里用「取最早触发时刻、
 * 并列时 sick 优先」自然复刻——已 sick → sickAt=now+30min 最早 → sick 胜；未 sick 时 hungry（小时级）早于
 * sick（最多 5 天）→ hungry 胜。
 */
object PetReminderPredictor {

    /** 一条预测的提醒计划：类别（pet_hungry / pet_sick）+ 绝对触发时刻（epoch millis）。 */
    data class Plan(val category: String, val fireAtMillis: Long)

    const val CATEGORY_HUNGRY = "pet_hungry"
    const val CATEGORY_SICK = "pet_sick"

    /** 1:1 iOS：hunger ≥ 70 触发「饿」提醒。 */
    const val HUNGRY_THRESHOLD = 70

    /** 1:1 iOS：连续 5 天（updateNeglectPhase `days >= 5`）无互动进入 sick。 */
    const val SICK_DAYS = 5

    private const val HOUR_MILLIS = 3_600_000L
    private const val DAY_MILLIS = 86_400_000L

    /** 已饿/已病时给一个自然的提醒窗口（= iOS `UNTimeIntervalNotificationTrigger(timeInterval: 30*60)`）。 */
    const val WARMUP_MILLIS = 30 * 60 * 1000L

    /**
     * 算出该宠物下一条「饿/病」提醒（或 null=暂无需提醒）。
     * @param now 现在（epoch millis；注入便于单测）。
     */
    fun computePlan(pet: CharacterPetEntity, settings: AppSettings, now: Long): Plan? {
        // 从未互动过 → 无衰减基线（与 iOS applyDecay 早退一致）。
        val lastInteraction = pet.lastInteractionDate ?: return null
        val phase = PetNeglectPhase.fromRaw(pet.neglectPhaseRaw)
        // 衰减增量基准：hunger 的存储值是「截至 lastDecayDate」的快照，预测要从该基准往后推（比从 now 推更准——
        // 它把「自上次衰减以来已悄悄累积但还没补算」的部分也算进去）。回前台维护刚跑过 applyDecay 时 ≈ now。
        val decayBase = pet.metadata.lastDecayDate ?: lastInteraction

        // sick 候选
        val sickAt: Long? = when (phase) {
            PetNeglectPhase.SICK -> now + WARMUP_MILLIS // 已病 → 尽快提醒（iOS 30min 窗口）
            PetNeglectPhase.RAN_AWAY -> null // 已越过 sick（离家出走），不预测 sick
            else -> { // NONE/UNHAPPY/UPSET → 预测第 5 天进 sick
                val t = lastInteraction + SICK_DAYS * DAY_MILLIS
                if (t > now) t else null // 非 sick 相位下若已过 5 天属脏数据，保守不排（下次维护会修相位）
            }
        }

        // hungry 候选——sick 优先：iOS 命中 sick 分支即不看 hunger。
        val hungryAt: Long? = if (phase == PetNeglectPhase.SICK) {
            null
        } else {
            val rate = settings.petHungerDecayPerHour
            when {
                pet.hunger >= HUNGRY_THRESHOLD -> now + WARMUP_MILLIS // 已饿 → 尽快提醒
                rate <= 0 -> null // 不衰减 → 永不饿
                else -> {
                    // 连续反演衰减曲线求跨 70 时刻；iOS applyDecay 按整数小时步进，故本式最多早 <1h（rate=2 时恒 0.5h）。
                    // 有意接受（复核裁定）：方向良性（只早不晚）、落在 30min WARMUP 自带容差同阶、贴 iOS 整数网格反而是
                    // 「把更准的安卓写法降级贴 iOS 采样伪影」（铁律#1 iOS=地板非天花板）。
                    val hoursToHungry = (HUNGRY_THRESHOLD - pet.hunger).toDouble() / rate
                    val t = decayBase + (hoursToHungry * HOUR_MILLIS).toLong()
                    if (t > now) t else now + WARMUP_MILLIS // 算出在过去（快照偏旧）→ 视为已饿，尽快提醒
                }
            }
        }

        // 取最早触发时刻；并列时 sick 优先（复刻 iOS sick 优先于 hungry）。
        return when {
            sickAt != null && (hungryAt == null || sickAt <= hungryAt) -> Plan(CATEGORY_SICK, sickAt)
            hungryAt != null -> Plan(CATEGORY_HUNGRY, hungryAt)
            else -> null
        }
    }
}
