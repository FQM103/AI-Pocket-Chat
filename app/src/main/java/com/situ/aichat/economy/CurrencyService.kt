package com.situ.aichat.economy

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import com.situ.aichat.data.local.entity.UserWalletEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.data.model.CurrencyTransactionKind
import com.situ.aichat.data.model.WalletOwnerType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 货币系统唯一增删入口（1:1 iOS `Services/CurrencyService.swift`）。
 *
 * iOS 是 `@MainActor enum`（主线程串行，天然无竞态）；安卓改 `@Singleton` + 每个增减包
 * [AppDatabase.withTransaction]，在**同一事务内**「读余额→判断→改余额→写流水」，杜绝两笔并发 spend 双花
 * （SPEC §4.1）。`now` 注入便于确定性单测。不可变 Room 行 + `copy`（与 pet/diary 一致）。
 *
 * 行为对齐：`add` 在 `amount<=0` 时不动余额、不写流水，返回当前余额；`spend` 在 `amount<=0` 或余额不足时返回
 * `null` 不扣。流水 `amount` 一律 coerceAtLeast(0)（iOS `init` 的 `max(0,amount)`）。
 *
 * ⚠️ **并发原子性契约（勿破坏）**：这些方法是「纯 suspend + 单个 db.withTransaction + 仅 suspend DAO 调用」，**绝不切协程上下文**
 * （无 withContext(Dispatchers.*)/launch/async/runBlocking）。上层把它们嵌进自己的 withTransaction（如薪资 14.6b、兑换码 14.6c）
 * 时，内层据此**继承同一 TransactionElement → 并入同一事务**，「检查+入账」整体原子且被 Room 单写线程串行化（防 TOCTOU 双发）。
 * 若将来在此引入上下文切换，内层会变成独立事务、提前提交 → 破坏上层原子性（甚至死锁）。改前务必复核所有嵌套调用方。
 */
@Singleton
class CurrencyService @Inject constructor(
    private val db: AppDatabase,
    private val dao: CurrencyDao,
) {

    // ── 用户钱包（单例） ─────────────────────────────────────────────

    /** 取或建用户钱包单例（对齐 iOS `userWallet(in:)`；AppViewModel 启动也会保证存在）。 */
    suspend fun ensureUserWallet(now: Long = System.currentTimeMillis()): UserWalletEntity = db.withTransaction {
        dao.getUserWallet() ?: UserWalletEntity(createdAt = now).also { dao.insertUserWallet(it) }
    }

    suspend fun userCoinBalance(now: Long = System.currentTimeMillis()): Int = ensureUserWallet(now).coinBalance

    /** 响应式用户余额（礼物店/红包 UI 用，1:1 iOS `@Query userWallets.first?.coinBalance ?? 100`，未建钱包回退 100 初始）。 */
    fun observeUserCoinBalance(): Flow<Int> = dao.observeUserWallet().map { it?.coinBalance ?: 100 }

    /** 响应式用户侧全部流水（我的钱包屏账本·14.6a，timestamp 降序；1:1 iOS `@Query ownerTypeRaw=="user" .reverse`）。 */
    fun observeUserTransactions(): Flow<List<CurrencyTransactionEntity>> = dao.observeUserTransactions()

    /** 直接设置用户余额（负值截 0）。谨慎用，一般走 add/spend（对齐 iOS `setUserCoinBalance`）。 */
    suspend fun setUserCoinBalance(newBalance: Int, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val wallet = dao.getUserWallet() ?: UserWalletEntity(createdAt = now).also { dao.insertUserWallet(it) }
            dao.updateUserWallet(wallet.copy(coinBalance = clampBalance(newBalance)))
        }
    }

    /** 给用户加金币 + 写 earn 流水。`amount<=0` 不动余额/不写流水。返回新余额。 */
    suspend fun addCoinsToUser(
        amount: Int,
        category: CurrencyTransactionCategory,
        note: String = "",
        relatedId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Int = db.withTransaction {
        val wallet = dao.getUserWallet() ?: UserWalletEntity(createdAt = now).also { dao.insertUserWallet(it) }
        if (amount <= 0) return@withTransaction wallet.coinBalance
        val updated = wallet.copy(coinBalance = wallet.coinBalance + amount, totalEarned = wallet.totalEarned + amount)
        dao.updateUserWallet(updated)
        dao.insertTransaction(
            txn(WalletOwnerType.USER, "", CurrencyTransactionKind.EARN, category, amount, updated.coinBalance, relatedId, note, now),
        )
        updated.coinBalance
    }

    /** 从用户钱包扣金币（余额不足或非正返回 `null`，不扣）。返回新余额或 `null`。 */
    suspend fun spendCoinsFromUser(
        amount: Int,
        category: CurrencyTransactionCategory,
        note: String = "",
        relatedId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Int? = db.withTransaction {
        val wallet = dao.getUserWallet() ?: UserWalletEntity(createdAt = now).also { dao.insertUserWallet(it) }
        val newBalance = spendableBalance(wallet.coinBalance, amount) ?: return@withTransaction null
        val updated = wallet.copy(coinBalance = newBalance, totalSpent = wallet.totalSpent + amount)
        dao.updateUserWallet(updated)
        dao.insertTransaction(
            txn(WalletOwnerType.USER, "", CurrencyTransactionKind.SPEND, category, amount, newBalance, relatedId, note, now),
        )
        newBalance
    }

    // ── 角色钱包 ─────────────────────────────────────────────────────

    /** 取或建角色钱包（对齐 iOS `wallet(for:in:)`）。 */
    suspend fun walletForCharacter(characterUuid: String, now: Long = System.currentTimeMillis()): CharacterWalletEntity =
        db.withTransaction {
            dao.getCharacterWallet(characterUuid)
                ?: CharacterWalletEntity(characterUuid = characterUuid, createdAt = now).also { dao.insertCharacterWallet(it) }
        }

    /** 给角色加金币 + 写 earn 流水。`amount<=0` 不动余额/不写流水。返回新余额。 */
    suspend fun addCoinsToCharacter(
        characterUuid: String,
        amount: Int,
        category: CurrencyTransactionCategory,
        note: String = "",
        relatedId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Int = db.withTransaction {
        val wallet = dao.getCharacterWallet(characterUuid)
            ?: CharacterWalletEntity(characterUuid = characterUuid, createdAt = now).also { dao.insertCharacterWallet(it) }
        if (amount <= 0) return@withTransaction wallet.coinBalance
        val updated = wallet.copy(coinBalance = wallet.coinBalance + amount, totalEarned = wallet.totalEarned + amount)
        dao.updateCharacterWallet(updated)
        dao.insertTransaction(
            txn(WalletOwnerType.CHARACTER, characterUuid, CurrencyTransactionKind.EARN, category, amount, updated.coinBalance, relatedId, note, now),
        )
        updated.coinBalance
    }

    /** 从角色钱包扣金币（不足或非正返回 `null`）。返回新余额或 `null`。 */
    suspend fun spendCoinsFromCharacter(
        characterUuid: String,
        amount: Int,
        category: CurrencyTransactionCategory,
        note: String = "",
        relatedId: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Int? = db.withTransaction {
        val wallet = dao.getCharacterWallet(characterUuid)
            ?: CharacterWalletEntity(characterUuid = characterUuid, createdAt = now).also { dao.insertCharacterWallet(it) }
        val newBalance = spendableBalance(wallet.coinBalance, amount) ?: return@withTransaction null
        val updated = wallet.copy(coinBalance = newBalance, totalSpent = wallet.totalSpent + amount)
        dao.updateCharacterWallet(updated)
        dao.insertTransaction(
            txn(WalletOwnerType.CHARACTER, characterUuid, CurrencyTransactionKind.SPEND, category, amount, newBalance, relatedId, note, now),
        )
        newBalance
    }

    // ── 角色钱包字段写回（薪资系统用；保持钱包写入单一入口） ──

    /** 写回月薪推断结果（monthlySalary + salaryInferred=true）。对齐 iOS inferAndWriteBack 的 wallet 写回。 */
    suspend fun setCharacterSalary(characterUuid: String, monthlySalary: Int, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val wallet = dao.getCharacterWallet(characterUuid)
                ?: CharacterWalletEntity(characterUuid = characterUuid, createdAt = now).also { dao.insertCharacterWallet(it) }
            dao.updateCharacterWallet(wallet.copy(monthlySalary = monthlySalary, salaryInferred = true))
        }
    }

    /**
     * 用户手动编辑角色月薪 + 发薪日（14.6b·1:1 iOS CharacterWalletEditSheet.save 的 wallet 写回）：
     * monthlySalary clamp[0,50000] + salaryInferred=true（手动设过=等价推断完成·启动不再批量覆盖）+ salaryDay clamp[1,28]。
     * 返回写回后的钱包（供调用方接着喂 [CharacterSalaryPayoutService.onboardingIfNeeded] 发 0.5 月薪入职储蓄）。
     */
    suspend fun setCharacterSalaryManual(
        characterUuid: String,
        monthlySalary: Int,
        salaryDay: Int,
        now: Long = System.currentTimeMillis(),
    ): CharacterWalletEntity = db.withTransaction {
        val wallet = dao.getCharacterWallet(characterUuid)
            ?: CharacterWalletEntity(characterUuid = characterUuid, createdAt = now).also { dao.insertCharacterWallet(it) }
        val updated = wallet.copy(
            monthlySalary = clampSalary(monthlySalary),
            salaryInferred = true,
            salaryDay = salaryDay.coerceIn(1, 28),
        )
        dao.updateCharacterWallet(updated)
        updated
    }

    /**
     * 清除月薪推断标记（salaryInferred=false），用于角色职业变更后让下次 [CharacterEconomyMaintenanceService]
     * 按新职业重推月薪（其 `!wallet.salaryInferred` 才推断）。对齐 iOS save() .edit 的
     * `character.wallet?.salaryInferred = false`：**无钱包则不动**（不为此建钱包，同 [setCharacterLastSalaryDate]）。
     */
    suspend fun clearSalaryInferred(characterUuid: String) {
        db.withTransaction {
            val wallet = dao.getCharacterWallet(characterUuid) ?: return@withTransaction
            dao.updateCharacterWallet(wallet.copy(salaryInferred = false))
        }
    }

    /** 记录发薪日期戳（lastSalaryDate）。无钱包则不动（发薪流程已先 addCoins 建好钱包）。 */
    suspend fun setCharacterLastSalaryDate(characterUuid: String, date: Long) {
        db.withTransaction {
            val wallet = dao.getCharacterWallet(characterUuid) ?: return@withTransaction
            dao.updateCharacterWallet(wallet.copy(lastSalaryDate = date))
        }
    }

    /** 记录日程经济事件已扫日期戳（lastEconomicScanDate，日级幂等用）。无钱包则取或建后写。 */
    suspend fun setCharacterLastEconomicScanDate(characterUuid: String, date: Long, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val wallet = dao.getCharacterWallet(characterUuid)
                ?: CharacterWalletEntity(characterUuid = characterUuid, createdAt = now).also { dao.insertCharacterWallet(it) }
            dao.updateCharacterWallet(wallet.copy(lastEconomicScanDate = date))
        }
    }

    /** 累加角色「用户送我」累积心意值（送礼路径用；不可消费，与 coinBalance 独立）。对齐 iOS `wallet.affinityFromUser += gain`。 */
    suspend fun addAffinityFromUser(characterUuid: String, delta: Int, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val wallet = dao.getCharacterWallet(characterUuid)
                ?: CharacterWalletEntity(characterUuid = characterUuid, createdAt = now).also { dao.insertCharacterWallet(it) }
            dao.updateCharacterWallet(wallet.copy(affinityFromUser = wallet.affinityFromUser + delta))
        }
    }

    /**
     * 角色主动送礼后记账（1:1 iOS `ProactiveGiftExecutor` 第 10 步）：`affinityToUser += gain` + `lastProactiveGiftDate = now`，
     * **不动余额**。钱包内 fresh 读（嵌套在执行器的 withTransaction 内时，读到的是刚 spendCoins 后的余额，只改这两字段不覆盖）。
     */
    suspend fun recordProactiveGiftAffinity(characterUuid: String, gain: Int, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            val wallet = dao.getCharacterWallet(characterUuid)
                ?: CharacterWalletEntity(characterUuid = characterUuid, createdAt = now).also { dao.insertCharacterWallet(it) }
            dao.updateCharacterWallet(wallet.copy(affinityToUser = wallet.affinityToUser + gain, lastProactiveGiftDate = now))
        }
    }

    /**
     * 直接写一条角色流水（**不改余额**），用于房租 `balance==0` 的 0 元欠租留痕——spend guard 会挡 amount=0，
     * 但房租「赖不掉」要留痕驱动叙事（对齐 iOS chargeRent 直接 `context.insert(tx)`）。一般记账走 add/spend。
     */
    suspend fun recordCharacterTransaction(
        characterUuid: String,
        kind: CurrencyTransactionKind,
        category: CurrencyTransactionCategory,
        amount: Int,
        balanceAfter: Int,
        note: String,
        relatedId: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        dao.insertTransaction(txn(WalletOwnerType.CHARACTER, characterUuid, kind, category, amount, balanceAfter, relatedId, note, now))
    }

    // ── 能否买得起（对齐 iOS userCanAfford / characterCanAfford，取或建钱包后比较） ──

    suspend fun userCanAfford(amount: Int, now: Long = System.currentTimeMillis()): Boolean =
        userCoinBalance(now) >= amount

    suspend fun characterCanAfford(characterUuid: String, amount: Int, now: Long = System.currentTimeMillis()): Boolean =
        walletForCharacter(characterUuid, now).coinBalance >= amount

    private fun txn(
        owner: WalletOwnerType,
        characterUuid: String,
        kind: CurrencyTransactionKind,
        category: CurrencyTransactionCategory,
        amount: Int,
        balanceAfter: Int,
        relatedId: String?,
        note: String,
        now: Long,
    ) = CurrencyTransactionEntity(
        timestamp = now,
        ownerTypeRaw = owner.raw,
        characterUuid = characterUuid,
        kindRaw = kind.raw,
        categoryRaw = category.raw,
        amount = amount.coerceAtLeast(0),
        balanceAfter = balanceAfter,
        relatedEntityId = relatedId,
        note = note,
    )
}

// ── 纯函数守卫（internal，便于确定性单测，断言反推 iOS guard） ──────────────

/** 扣款守卫（1:1 iOS `guard amount > 0, balance >= amount`）：返回扣后余额，非正/不足返回 `null`。 */
internal fun spendableBalance(balance: Int, amount: Int): Int? =
    if (amount > 0 && balance >= amount) balance - amount else null

/** 余额钳位（1:1 iOS `setUserCoinBalance` 的 `max(0, newBalance)`）。 */
internal fun clampBalance(newBalance: Int): Int = newBalance.coerceAtLeast(0)
