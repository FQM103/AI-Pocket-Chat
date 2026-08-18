package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.situ.aichat.data.local.entity.RedeemCodeUsageEntity

/** 兑换码使用记录 DAO（14.6c）。去重探测 + 插入（IGNORE 兜底并发重入）+ 备份导出/导入。 */
@Dao
interface RedeemCodeUsageDao {

    /** 该 codeHash 是否已用过（fetchLimit=1 等价·1:1 iOS alreadyUsed 检查）。 */
    @Query("SELECT EXISTS(SELECT 1 FROM redeem_code_usage WHERE codeHash = :codeHash LIMIT 1)")
    suspend fun existsByCodeHash(codeHash: String): Boolean

    /** 插入使用记录；codeHash 唯一冲突时 IGNORE（并发同码兜底·与事务内 exists 检查双保险）。返回 rowId（-1=被忽略）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(usage: RedeemCodeUsageEntity): Long

    /** 备份导出：全部使用记录。 */
    @Query("SELECT * FROM redeem_code_usage")
    suspend fun getAll(): List<RedeemCodeUsageEntity>

    /**
     * 备份导入恢复。REPLACE 在**任一唯一约束**（PK uuid 或唯一索引 codeHash）冲突时覆盖 → 实际去重键是 **codeHash**
     * （同码不同 uuid 也会被去重，正是「一次性」语义所需）。多次导入/多份备份重叠 → 末次按 codeHash 胜出（restore 语义正确）。
     * 仅恢复「用过哪些码」的去重账，**不碰钱包余额**（余额由 userWallet 段单独还原），故重复导入不会重复发币。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(usages: List<RedeemCodeUsageEntity>)
}
