package com.situ.aichat.economy

import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 角色发薪 + 入职储蓄（1:1 iOS `Services/CharacterSalaryPayoutService.swift`）。
 * - [payoutIfDue]：今天 ≥ 发薪日(clamp 1-28) 且当月未发 → 入账月薪。一月一次幂等（key `salary_{uuid}_{YYYYMM}`），**不补发历史漏薪**。
 * - [onboardingIfNeeded]：角色首次推断完月薪 → 给 0.5 月薪「入职储蓄」（一次性，key `onboarding_{uuid}`，`max(1, …)` 防 0）。
 * - [applyManualSalaryEdit]：用户手动改月薪（14.6b）→ 写回 + 入职储蓄**一笔原子事务**。
 *
 * 幂等 = 「`transactionExists` 检查 + `addCoins` 入账」必须在**同一 [AppDatabase.withTransaction]** 内（14.6b 复核修 HIGH）：
 * iOS 靠 @MainActor 串行，安卓无此约束——手动编辑路径与维护循环可并发（rapid double-tap / 维护 worker 同时跑），
 * 检查与入账若跨两笔事务存在 TOCTOU 窗口会双发储蓄。Room 把并发 `withTransaction` 块串行化（单写连接），故包进一笔即原子。
 */
@Singleton
class CharacterSalaryPayoutService @Inject constructor(
    private val db: AppDatabase,
    private val currencyService: CurrencyService,
    private val dao: CurrencyDao,
) {

    /** 到发薪日且当月未发 → 入账月薪 + 记 lastSalaryDate。返回入账额（0=跳过：未到日/已发/月薪 0）。检查+入账同一事务（防 TOCTOU）。 */
    suspend fun payoutIfDue(wallet: CharacterWalletEntity, now: Long = System.currentTimeMillis()): Int = db.withTransaction {
        if (wallet.monthlySalary <= 0) return@withTransaction 0
        val parts = salaryDateParts(now)
        if (!isPayday(wallet.salaryDay, parts.day)) return@withTransaction 0
        val key = salaryKey(wallet.characterUuid, parts.year, parts.month)
        if (dao.transactionExists(key)) {
            Log.d(TAG, "发薪跳过·本月已发 char=${wallet.characterUuid} key=$key")
            return@withTransaction 0
        }
        currencyService.addCoinsToCharacter(
            characterUuid = wallet.characterUuid,
            amount = wallet.monthlySalary,
            category = CurrencyTransactionCategory.SALARY,
            note = "${parts.year} 年 ${parts.month} 月工资",
            relatedId = key,
            now = now,
        )
        currencyService.setCharacterLastSalaryDate(wallet.characterUuid, now)
        Log.i(TAG, "发薪 char=${wallet.characterUuid} amount=${wallet.monthlySalary} key=$key")
        wallet.monthlySalary
    }

    /** 首次推断完月薪 → 0.5 月薪入职储蓄（一次性）。返回入账额（0=跳过：未推断/已 onboard/月薪 0）。检查+入账同一事务（防 TOCTOU·14.6b 复核修）。 */
    suspend fun onboardingIfNeeded(wallet: CharacterWalletEntity, now: Long = System.currentTimeMillis()): Int = db.withTransaction {
        if (!wallet.salaryInferred) return@withTransaction 0
        if (wallet.monthlySalary <= 0) return@withTransaction 0
        val key = onboardingKey(wallet.characterUuid)
        if (dao.transactionExists(key)) return@withTransaction 0
        val amount = onboardingAmount(wallet.monthlySalary)
        currencyService.addCoinsToCharacter(
            characterUuid = wallet.characterUuid,
            amount = amount,
            category = CurrencyTransactionCategory.SALARY,
            note = "入职储蓄",
            relatedId = key,
            now = now,
        )
        Log.i(TAG, "入职储蓄 char=${wallet.characterUuid} amount=$amount key=$key")
        amount
    }

    /**
     * 用户手动保存月薪 + 发薪日（14.6b·💰涉钱写）：把「写回钱包字段」与「首次入职储蓄」合并为**一笔原子事务**
     * （1:1 iOS save() 的单次 modelContext.save·复核修 HIGH「两笔分离事务」）。返回入职储蓄入账额（0=非首次/月薪 0）。
     * 内层 [CurrencyService.setCharacterSalaryManual] / [onboardingIfNeeded] 各自的 withTransaction 嵌套即并入本事务。
     */
    suspend fun applyManualSalaryEdit(
        characterUuid: String,
        monthlySalary: Int,
        salaryDay: Int,
        now: Long = System.currentTimeMillis(),
    ): Int = db.withTransaction {
        val updated = currencyService.setCharacterSalaryManual(characterUuid, monthlySalary, salaryDay, now)
        onboardingIfNeeded(updated, now)
    }

    companion object {
        private const val TAG = "SalaryPayout"
        const val ONBOARDING_RATIO = 0.5
    }
}

// ── 纯函数（internal，单测反推 iOS key 格式/边界） ──────────────────────────

/** 本月工资去重 key：`salary_{uuid}_{YYYYMM}`（`%04d%02d`·钉 Locale.ROOT——幂等 key 绝不随设备语言变，否则重复发薪）。 */
internal fun salaryKey(characterUuid: String, year: Int, month: Int): String =
    "salary_%s_%04d%02d".format(Locale.ROOT, characterUuid, year, month)

/** 入职储蓄去重 key：`onboarding_{uuid}`。 */
internal fun onboardingKey(characterUuid: String): String = "onboarding_$characterUuid"

/** 是否到发薪日：今天 ≥ clamp(salaryDay,1,28)（老数据/手改可能越界，保险钳位）。 */
internal fun isPayday(salaryDay: Int, dayOfMonth: Int): Boolean = dayOfMonth >= salaryDay.coerceIn(1, 28)

/** 入职储蓄金额：`max(1, (salary*0.5).toInt())`（防月薪 1 算出 0）。 */
internal fun onboardingAmount(monthlySalary: Int): Int = maxOf(1, (monthlySalary * 0.5).toInt())

internal data class SalaryDateParts(val year: Int, val month: Int, val day: Int)

internal fun salaryDateParts(now: Long, zone: ZoneId = ZoneId.systemDefault()): SalaryDateParts {
    val d = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return SalaryDateParts(d.year, d.monthValue, d.dayOfMonth)
}
