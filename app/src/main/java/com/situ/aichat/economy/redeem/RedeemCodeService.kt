package com.situ.aichat.economy.redeem

import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.RedeemCodeUsageDao
import com.situ.aichat.data.local.entity.RedeemCodeUsageEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.economy.CurrencyService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 兑换码核心服务（14.6c·💰涉钱写·1:1 iOS `RedeemCodeService`）：解码 → 验签 → 检过期 → 检已用 → 入账。
 *
 * 纯函数编解码在 [RedeemCodeCodec]；本服务把它串到钱包 + 去重记录。**去重检查 + 入账 + 写使用记录在同一笔
 * [AppDatabase.withTransaction]**（吸取 14.6b 复核教训：检查与入账跨事务会被并发双花；Room 串行化 withTransaction
 * → 原子幂等）。codeHash 唯一索引 + insert IGNORE 为并发兜底。
 *
 * secret 未配置（空）→ 一律判 [RedeemError.INVALID_CODE]（不暴露「未配置」细节，对齐 iOS）。
 */
@Singleton
class RedeemCodeService @Inject constructor(
    private val db: AppDatabase,
    private val currencyService: CurrencyService,
    private val usageDao: RedeemCodeUsageDao,
) {

    /** 兑换错误（4 类·1:1 iOS RedeemError）。用户文案由 UI 本地化映射。 */
    enum class RedeemError { INVALID_FORMAT, INVALID_CODE, EXPIRED, ALREADY_USED }

    sealed interface Outcome {
        data class Success(val coinsAdded: Int, val newBalance: Int, val expiryMillis: Long) : Outcome
        data class Error(val error: RedeemError) : Outcome
    }

    /**
     * 完整兑换流程（1:1 iOS redeem）。失败返回 [Outcome.Error]，成功返回 [Outcome.Success]。
     * @param secret 测试可注入；生产用 [RedeemCodeSecret.value]。
     */
    suspend fun redeem(
        rawCode: String,
        secret: ByteArray = RedeemCodeSecret.value,
        now: Long = System.currentTimeMillis(),
    ): Outcome {
        if (secret.isEmpty()) {
            Log.w(TAG, "兑换失败·secret 未配置(发码功能未启用)")
            return Outcome.Error(RedeemError.INVALID_CODE)
        }

        // 1. 解码 + 验签
        val payload = try {
            RedeemCodeCodec.decode(rawCode, secret)
        } catch (e: RedeemCodeCodec.CodecError) {
            return Outcome.Error(
                when (e) {
                    is RedeemCodeCodec.CodecError.InvalidFormat -> {
                        Log.w(TAG, "兑换失败·格式无效")
                        RedeemError.INVALID_FORMAT
                    }
                    is RedeemCodeCodec.CodecError.InvalidSignature -> {
                        Log.w(TAG, "兑换失败·验签不通过(可能伪造/篡改)")
                        RedeemError.INVALID_CODE
                    }
                },
            )
        }

        // 2. 过期检查（payload.expiryDateMillis = 当天 UTC 23:59:59）
        if (now > payload.expiryDateMillis) {
            Log.w(TAG, "兑换失败·已过期 expiry=${payload.expiryDateMillis} now=$now")
            return Outcome.Error(RedeemError.EXPIRED)
        }

        // 3. codeHash（decode 已成功 → normalize 不会失败；保险）
        val normalized = RedeemCodeCodec.normalize(rawCode) ?: return Outcome.Error(RedeemError.INVALID_FORMAT)
        val codeHash = RedeemCodeCodec.codeHash(normalized)

        // 4+5. 原子：先以 codeHash 唯一索引「写使用记录」作闸门 → 抢到才入账（同一事务·防并发双兑）。
        // **闸门=DB 唯一约束而非读检查**：insert(IGNORE) 冲突返回 -1 → 判已用、绝不入账。这样幂等性由 SQLite 唯一约束
        // 在 insert 时原子保证，**不依赖事务嵌套是否「内层提前提交」的微妙语义**（复核 HIGH 提此疑虑——经核 addCoinsToUser
        // 是同协程纯 suspend+db.withTransaction 故本就嵌套原子；改 insert-first 后即便将来 CurrencyService 引入上下文切换
        // 也不会双发，更稳）。失败/异常 → 整事务回滚（使用记录与入账同生共死）。
        val amount = payload.coins
        return db.withTransaction {
            val rowId = usageDao.insert(RedeemCodeUsageEntity(codeHash = codeHash, amount = amount, redeemedAt = now))
            if (rowId == -1L) {
                Log.w(TAG, "兑换失败·已使用过 codeHash=$codeHash")
                return@withTransaction Outcome.Error(RedeemError.ALREADY_USED)
            }
            val newBalance = currencyService.addCoinsToUser(
                amount = amount,
                category = CurrencyTransactionCategory.REDEEM_CODE,
                note = "兑换码 +$amount 金币",
                relatedId = "redeem_$codeHash",
                now = now,
            )
            Log.i(TAG, "兑换成功 +$amount 金币 codeHash=$codeHash newBalance=$newBalance")
            Outcome.Success(coinsAdded = amount, newBalance = newBalance, expiryMillis = payload.expiryDateMillis)
        }
    }

    private companion object {
        const val TAG = "RedeemCodeService"
    }
}
