package com.situ.aichat.economy

import android.util.Log
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.data.model.CurrencyTransactionKind
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 角色固定经济事件（1:1 iOS `Services/CharacterFixedEconomicEventService.swift`）：脱离日程的「经济事实」——
 * 房租 + 季度奖金。
 * - [processRentIfDue]：每月 salaryDay+5（clamp 月底）扣一次房租（占月薪 30-40%，min 50）。**房租赖不掉**：余额不足扣到 0、
 *   余额为 0 仍写 0 元「欠租」流水留痕（与日程消费「没钱就没去」相反）。category=unexpectedExpense，note 必含「欠租」（欠租检测依赖）。
 * - [processQuarterlyBonusIfDue]：3/6/9/12 月的 salaryDay+1 发季度奖金（占月薪 50-200%，min 100），category=**salary**（奖金=工资延伸）。
 *
 * 金额确定性：seed=`{uuid}_{YYYYMM}_rent` / `{uuid}_{YYYY}Q{q}_bonus` 哈希到区间内一点（同角色同月/季固定）。
 * 安卓用 Kotlin `String.hashCode`（确定性、跨重启稳定；值与 iOS 不同无妨，靠幂等 key 防重算）。月薪 0 全跳过。
 * 维护循环串行调用、**读钱包余额前 bonus 应先于 rent**（先收入后支出），故这里每次读钱包 fresh。
 */
@Singleton
class CharacterFixedEconomicEventService @Inject constructor(
    private val currencyService: CurrencyService,
    private val dao: CurrencyDao,
) {

    /**
     * 到房租日且本月未扣 → 扣房租（三档：足额/部分欠租/0 元欠租留痕）。返回 [RentCharge]（null=本月无事：
     * 未到日/已扣过/月薪 0/无钱包）。P1-40 把出参从「实扣额 Int」改为可区分形态——旧返回 0 无法区分
     * 「0 元欠租留痕（确有事件）」与「无事发生」，会漏报欠租；**写入逻辑一字未动**。
     */
    suspend fun processRentIfDue(characterUuid: String, now: Long = System.currentTimeMillis()): RentCharge? {
        val wallet = dao.getCharacterWallet(characterUuid) ?: return null
        if (wallet.monthlySalary <= 0) return null
        val parts = economicDateParts(now)
        val rentDay = calculateRentDay(wallet.salaryDay, parts.year, parts.month)
        if (parts.day < rentDay) return null
        val key = rentKey(characterUuid, parts.year, parts.month)
        if (dao.transactionExists(key)) return null

        val amount = computeRentAmount(wallet.monthlySalary, characterUuid, parts.year, parts.month)
        val balance = wallet.coinBalance
        return when {
            balance >= amount -> {
                currencyService.spendCoinsFromCharacter(characterUuid, amount, CurrencyTransactionCategory.UNEXPECTED_EXPENSE, "🏠 房租", key, now)
                Log.i(TAG, "房租扣款 char=$characterUuid amount=$amount key=$key")
                RentCharge(charged = amount, due = amount)
            }
            balance > 0 -> {
                currencyService.spendCoinsFromCharacter(characterUuid, balance, CurrencyTransactionCategory.UNEXPECTED_EXPENSE, rentArrearsNote(amount), key, now)
                Log.w(TAG, "房租部分欠租 char=$characterUuid charged=$balance due=$amount key=$key")
                RentCharge(charged = balance, due = amount)
            }
            else -> {
                // balance == 0：房租赖不掉，写 0 元欠租流水留痕
                currencyService.recordCharacterTransaction(
                    characterUuid, CurrencyTransactionKind.SPEND, CurrencyTransactionCategory.UNEXPECTED_EXPENSE,
                    amount = 0, balanceAfter = 0, note = rentArrearsNote(amount), relatedId = key, now = now,
                )
                Log.w(TAG, "房租全欠 char=$characterUuid due=$amount (余额 0·写 0 元欠租留痕) key=$key")
                RentCharge(charged = 0, due = amount)
            }
        }
    }

    /** 季度末月(3/6/9/12)的 salaryDay+1 起、本季度未发 → 发季度奖金（category=salary）。返回入账额（0=跳过）。 */
    suspend fun processQuarterlyBonusIfDue(characterUuid: String, now: Long = System.currentTimeMillis()): Int {
        val wallet = dao.getCharacterWallet(characterUuid) ?: return 0
        if (wallet.monthlySalary <= 0) return 0
        val parts = economicDateParts(now)
        val quarter = quarterNumber(parts.month)
        if (quarter <= 0) return 0
        if (parts.day < bonusDay(wallet.salaryDay)) return 0
        val key = bonusKey(characterUuid, parts.year, quarter)
        if (dao.transactionExists(key)) return 0

        val amount = computeBonusAmount(wallet.monthlySalary, characterUuid, parts.year, quarter)
        currencyService.addCoinsToCharacter(
            characterUuid = characterUuid,
            amount = amount,
            category = CurrencyTransactionCategory.SALARY, // 奖金语义上是工资延伸
            note = "💰 ${parts.year}年Q$quarter 季度奖金",
            relatedId = key,
            now = now,
        )
        Log.i(TAG, "季度奖金 char=$characterUuid amount=$amount Q$quarter key=$key")
        return amount
    }

    private companion object {
        const val TAG = "FixedEconomicEvent"
    }
}

/**
 * 房租扣款结果（P1-40 出参）：[charged]=实扣额（0=0 元欠租留痕），[due]=本应扣额；charged < due 即欠租。
 */
data class RentCharge(val charged: Int, val due: Int)

// ── 纯函数（internal，单测反推 iOS） ──────────────────────────────────────

const val RENT_RATE_MIN = 0.3
const val RENT_RATE_MAX = 0.4
const val BONUS_RATE_MIN = 0.5
const val BONUS_RATE_MAX = 2.0
const val RENT_MIN_AMOUNT = 50
const val BONUS_MIN_AMOUNT = 100

// key/seed 格式化一律钉 Locale.ROOT：阿拉伯语系设备的 %d 会输出本地化数字，幂等 key 失配 = 房租重复扣、
// 奖金重复发；seed 被改写 = stableRate 的 hashCode 变 → 金额随设备语言跳变（2026-07-12 性能线程专项 K1）。
internal fun rentKey(characterUuid: String, year: Int, month: Int): String =
    "rent_%s_%04d%02d".format(Locale.ROOT, characterUuid, year, month)

internal fun bonusKey(characterUuid: String, year: Int, quarter: Int): String =
    "bonus_%s_%04dQ%d".format(Locale.ROOT, characterUuid, year, quarter)

internal fun rentArrearsNote(amount: Int): String = "🏠 房租(欠租 · 本应 $amount)"

/** 确定性 rate：seed 哈希映射到 [min,max]（abs 用 Long 避免 Int.MIN 溢出；值跨平台不同但本平台稳定）。 */
internal fun stableRate(min: Double, max: Double, seed: String): Double {
    val normalized = (abs(seed.hashCode().toLong()) % 1000) / 1000.0
    return min + (max - min) * normalized
}

internal fun computeRentAmount(monthlySalary: Int, characterUuid: String, year: Int, month: Int): Int {
    val rate = stableRate(RENT_RATE_MIN, RENT_RATE_MAX, "%s_%04d%02d_rent".format(Locale.ROOT, characterUuid, year, month))
    return maxOf(RENT_MIN_AMOUNT, (maxOf(0, monthlySalary) * rate).roundToInt())
}

internal fun computeBonusAmount(monthlySalary: Int, characterUuid: String, year: Int, quarter: Int): Int {
    val rate = stableRate(BONUS_RATE_MIN, BONUS_RATE_MAX, "%s_%04dQ%d_bonus".format(Locale.ROOT, characterUuid, year, quarter))
    return maxOf(BONUS_MIN_AMOUNT, (maxOf(0, monthlySalary) * rate).roundToInt())
}

/** 房租日 = clamp(salaryDay,1,28)+5，超月底则 clamp 到当月最后一天。 */
internal fun calculateRentDay(salaryDay: Int, year: Int, month: Int): Int {
    val target = salaryDay.coerceIn(1, 28) + 5
    val lastDay = runCatching { YearMonth.of(year, month).lengthOfMonth() }.getOrDefault(28)
    return minOf(target, lastDay)
}

/** 季度奖金发放日 = clamp(salaryDay,1,28)+1（Q 月份 ≥30 天，不需 clamp 月底）。 */
internal fun bonusDay(salaryDay: Int): Int = salaryDay.coerceIn(1, 28) + 1

/** 月份对应季度号（3→1 / 6→2 / 9→3 / 12→4，其他 0 跳过）。 */
internal fun quarterNumber(month: Int): Int = when (month) {
    3 -> 1
    6 -> 2
    9 -> 3
    12 -> 4
    else -> 0
}

internal data class EconomicDateParts(val year: Int, val month: Int, val day: Int)

internal fun economicDateParts(now: Long, zone: ZoneId = ZoneId.systemDefault()): EconomicDateParts {
    val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return EconomicDateParts(d.year, d.monthValue, d.dayOfMonth)
}
