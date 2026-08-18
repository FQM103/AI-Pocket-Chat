package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import com.situ.aichat.data.local.entity.UserWalletEntity
import kotlinx.coroutines.flow.Flow

/**
 * 货币读写（M10）。三表（user_wallet 单例 / character_wallet 每角色 / currency_transaction 流水）共一 DAO，
 * 因 [com.situ.aichat.economy.CurrencyService] 是唯一增减入口、转账需跨表原子（withTransaction）。
 */
@Dao
interface CurrencyDao {

    // 用户钱包（单例）
    @Query("SELECT * FROM user_wallet LIMIT 1")
    suspend fun getUserWallet(): UserWalletEntity?

    @Query("SELECT * FROM user_wallet LIMIT 1")
    fun observeUserWallet(): Flow<UserWalletEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserWallet(wallet: UserWalletEntity)

    @Update
    suspend fun updateUserWallet(wallet: UserWalletEntity)

    // 角色钱包
    @Query("SELECT * FROM character_wallet WHERE characterUuid = :characterUuid LIMIT 1")
    suspend fun getCharacterWallet(characterUuid: String): CharacterWalletEntity?

    @Query("SELECT * FROM character_wallet WHERE characterUuid = :characterUuid LIMIT 1")
    fun observeCharacterWallet(characterUuid: String): Flow<CharacterWalletEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCharacterWallet(wallet: CharacterWalletEntity)

    @Update
    suspend fun updateCharacterWallet(wallet: CharacterWalletEntity)

    // 流水
    @Insert
    suspend fun insertTransaction(transaction: CurrencyTransactionEntity)

    /** 全部流水（备份导出 R2：随余额整表搬运幂等台账，防恢复后重发/重扣）。 */
    @Query("SELECT * FROM currency_transaction")
    suspend fun getAllTransactions(): List<CurrencyTransactionEntity>

    /**
     * 批量按主键 REPLACE 插入流水（仅备份恢复用 R2）。**不走 CurrencyService 增减入口、不动任何钱包余额**——
     * 余额由 wallet 段单独恢复；本方法只把幂等台账原样搬回（原 uuid 冲突即覆盖，同设备覆盖导入幂等）。
     * 常规记账仍用 [insertTransaction]（append-only 无 REPLACE）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun restoreTransactions(transactions: List<CurrencyTransactionEntity>)

    /** 幂等去重：某 relatedEntityId 是否已有流水（发薪/房租/送礼/兑换/进化等，对齐 iOS transactionExists fetchLimit=1）。 */
    @Query("SELECT EXISTS(SELECT 1 FROM currency_transaction WHERE relatedEntityId = :relatedId LIMIT 1)")
    suspend fun transactionExists(relatedId: String): Boolean

    /**
     * 删某角色的全部角色侧流水（14.7d：删角色时清孤儿账本行；1:1 iOS cleanupOrphanedRecords 对 CurrencyTransaction
     * ownerType==.character 的清理）。CharacterWallet 有 FK CASCADE 自走、但流水字符串关联无外键 → 不删则永久孤儿堆积。
     * **仅删该角色侧流水**（ownerTypeRaw='character'），用户钱包流水(ownerTypeRaw='user')不受影响；不改任何余额=零钱算。
     */
    @Query("DELETE FROM currency_transaction WHERE ownerTypeRaw = 'character' AND characterUuid = :characterUuid")
    suspend fun deleteCharacterTransactions(characterUuid: String)

    /** 某角色在 [since, ∞) 的流水（欠租检测：调用方 Kotlin 侧过滤 note 含「欠租」=iOS 行为，避免对 emoji 用 SQL LIKE）。 */
    @Query("SELECT * FROM currency_transaction WHERE ownerTypeRaw = 'character' AND characterUuid = :characterUuid AND timestamp >= :since")
    suspend fun characterTransactionsSince(characterUuid: String, since: Long): List<CurrencyTransactionEntity>

    /** Flow 版某角色 [since, ∞) 流水（资料页钱包卡实时刷新），timestamp 降序。 */
    @Query("SELECT * FROM currency_transaction WHERE ownerTypeRaw = 'character' AND characterUuid = :characterUuid AND timestamp >= :since ORDER BY timestamp DESC")
    fun observeCharacterTransactionsSince(characterUuid: String, since: Long): Flow<List<CurrencyTransactionEntity>>

    /** Flow 版用户侧全部流水（我的钱包屏账本·14.6a），timestamp 降序（1:1 iOS @Query ownerTypeRaw=="user" sort .reverse）。 */
    @Query("SELECT * FROM currency_transaction WHERE ownerTypeRaw = 'user' ORDER BY timestamp DESC")
    fun observeUserTransactions(): Flow<List<CurrencyTransactionEntity>>

    /**
     * 主动送礼月上限计数（1:1 iOS `ProactiveGiftScheduler.hasReachedMonthlyLimit` 的 fetchCount）：某角色在 [since, ∞)
     * 的「spend + gift 品类」流水条数。仅 category=gift（红包品类 redPacket 不计入，与 iOS 一致）。
     */
    @Query(
        "SELECT COUNT(*) FROM currency_transaction WHERE ownerTypeRaw = 'character' " +
            "AND characterUuid = :characterUuid AND timestamp >= :since AND kindRaw = 'spend' AND categoryRaw = 'gift'",
    )
    suspend fun countCharacterGiftSpends(characterUuid: String, since: Long): Int

    /**
     * 最近 [since, ∞) 的宠物商店（petShop）流水，降序、最多 [limit] 条（宠物状态 prompt 的「最近 24h 买的东西」，M11）。
     * 1:1 iOS `PromptBuilder+Pet.recentPetShopItems` 的 FetchDescriptor（category=petShop、timestamp≥oneDayAgo、
     * fetchLimit=10、desc）。relatedEntityId 即 item.id，调用方路由回 PetItemCatalog 物品名。
     */
    @Query("SELECT * FROM currency_transaction WHERE categoryRaw = 'petShop' AND timestamp >= :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentPetShopTransactions(since: Long, limit: Int): List<CurrencyTransactionEntity>
}
