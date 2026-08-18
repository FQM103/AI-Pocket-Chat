package com.situ.aichat.pet

import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.PetDao
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.data.repository.PetWriteLock
import com.situ.aichat.economy.CurrencyService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宠物用品店购买服务（1:1 iOS `Services/PetShopService.swift`）。
 *
 * 核心职责（5 步）：①目录校验 unknownItem ②永久品已拥有 → alreadyOwned（消耗品可重复买）③扣币
 * `spendCoinsFromUser(category=petShop, note="购买:{name}", relatedId=item.id)`，不足 → insufficientCoins
 * ④`inventory.adding` 回写 metadata ⑤价格 ≥ [PetItemCatalog.EXPENSIVE_PURCHASE_THRESHOLD] 追加
 * `recentExpensivePurchases` 并清理 >7 天旧记录 ⑥写回宠物行。
 *
 * **钱路径原子性（仿 GiftSendService）**：整段包在 [AppDatabase.withTransaction]，[CurrencyService.spendCoinsFromUser]
 * 内层事务**合流**到本外层事务（= iOS 单个 `modelContext.save()` 原子落盘）；扣币与库存写回要么全成、要么全回滚，
 * 杜绝「钱扣了但库存没加」的半写。pet 在事务内 **fresh 读**（避免与回前台惰性衰减并发时整行 clobber）。
 *
 * iOS 是可变 @Model 原地改；Android 不可变 Room 行 → `copy` 后 upsert。`now` 注入便于确定性单测。
 */
@Singleton
class PetShopService @Inject constructor(
    private val db: AppDatabase,
    private val currencyService: CurrencyService,
    private val petDao: PetDao,
    private val petWriteLock: PetWriteLock,
) {

    /** 购买结果（iOS `PetShopError` throw 语义改枚举返回，UI 可穷尽分支）。`SaveFailed` 不建模——真正 DB 异常由事务回滚并向上抛。 */
    sealed interface PurchaseOutcome {
        /** 购买成功，[newBalance] 为扣费后用户余额。 */
        data class Success(val newBalance: Int) : PurchaseOutcome

        /** 金币不足（[required] 需要、[current] 当前余额）。 */
        data class InsufficientCoins(val required: Int, val current: Int) : PurchaseOutcome

        /** 永久品已拥有，无需重复购买。 */
        data class AlreadyOwned(val itemId: String) : PurchaseOutcome

        /** 物品 ID 不在目录里（防御性，正常 UI 流程不会进）。 */
        data class UnknownItem(val itemId: String) : PurchaseOutcome

        /** 宠物行不存在（Android 适配：iOS 直接持 live @Model，无此态）。 */
        data object PetNotFound : PurchaseOutcome
    }

    /**
     * 购买一件宠物用品。
     *
     * @param item 要购买的物品（调用方从 [PetItemCatalog.allItems] 取）。
     * @param petUuid 入库的宠物 uuid（事务内 fresh 读，物品进它的 [PetMetadata.petInventory]）。
     * @return [PurchaseOutcome]
     */
    suspend fun purchase(
        item: PetItem,
        petUuid: String,
        now: Long = System.currentTimeMillis(),
    ): PurchaseOutcome = petWriteLock.withPetLock(petUuid) {
        // D1d：先 Mutex 再 Room 事务（固定序 Mutex→SQLite，与 D1b 礼物一致，杜绝锁序反转死锁）；与回前台批量
        // 维护/护理串行，杜绝维护整行写覆盖刚买入的库存。事务内仍 fresh 读宠物（钱+库存原子）。
        db.withTransaction {
            // 1. 目录核验（防御性）
            if (PetItemCatalog.find(item.id) == null) {
                Log.w(TAG, "宠物店购买失败·未知物品 item=${item.id}")
                return@withTransaction PurchaseOutcome.UnknownItem(item.id)
            }

            // fresh 读宠物行（事务内，杜绝并发衰减 clobber）
            val pet = petDao.getByUuid(petUuid) ?: run {
                Log.w(TAG, "宠物店购买失败·宠物不存在 pet=$petUuid item=${item.id}")
                return@withTransaction PurchaseOutcome.PetNotFound
            }
            val inventory = pet.metadata.petInventory

            // 2. 永久品已拥有检查（消耗品可重复买）
            if (item.kind == PetItemKind.EQUIPPABLE && inventory.has(item.id)) {
                Log.d(TAG, "宠物店购买跳过·已拥有 pet=$petUuid item=${item.id}")
                return@withTransaction PurchaseOutcome.AlreadyOwned(item.id)
            }

            // 3. 扣币（关联 item.id 做审计/路由；CurrencyService 内层事务合流；null = 余额不足或极端竞态）
            val newBalance = currencyService.spendCoinsFromUser(
                amount = item.price,
                category = CurrencyTransactionCategory.PET_SHOP,
                note = "购买:${item.name}",
                relatedId = item.id,
                now = now,
            ) ?: run {
                val have = currencyService.userCoinBalance(now)
                Log.w(TAG, "宠物店·余额不足 pet=$petUuid item=${item.id} need=${item.price} have=$have")
                return@withTransaction PurchaseOutcome.InsufficientCoins(item.price, have)
            }

            // 4. 写入库存（不可变语义：adding 返回新实例）
            var metadata = pet.metadata.copy(petInventory = inventory.adding(item.id, 1))

            // 5. 贵价购买 record（≥300 金币）→ 朋友圈/日记联动数据源；并清理 >7 天旧记录
            if (item.price >= PetItemCatalog.EXPENSIVE_PURCHASE_THRESHOLD) {
                metadata = metadata.copy(
                    recentExpensivePurchases = prunedExpensivePurchases(metadata.recentExpensivePurchases, item.id, now),
                )
            }

            // 6. 写回宠物行
            petDao.upsert(pet.copy(petMetadataJson = PetJson.encodeMetadata(metadata)))
            Log.i(TAG, "宠物店购买成功 pet=$petUuid item=${item.id} price=${item.price} newBalance=$newBalance")
            PurchaseOutcome.Success(newBalance)
        }
    }

    companion object {
        private const val TAG = "PetShopService"
        private const val SEVEN_DAYS_MS: Long = 7L * 24 * 3600 * 1000

        /**
         * 追加本次贵价购买记录并剔除 >7 天的旧记录（1:1 iOS：append 新记录 → `removeAll { purchasedAt < cutoff }`）。
         * 新记录 purchasedAt=now ≥ cutoff 必保留。纯函数（internal）便于单测。
         */
        internal fun prunedExpensivePurchases(
            existing: List<PetExpensivePurchaseRecord>,
            itemId: String,
            now: Long,
        ): List<PetExpensivePurchaseRecord> {
            val cutoff = now - SEVEN_DAYS_MS
            return (existing + PetExpensivePurchaseRecord(itemId = itemId, purchasedAt = now))
                .filter { it.purchasedAt >= cutoff }
        }
    }
}
