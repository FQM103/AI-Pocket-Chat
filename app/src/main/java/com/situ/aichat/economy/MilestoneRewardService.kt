package com.situ.aichat.economy

import android.util.Log
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.pet.PetGrowthStage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 里程碑金币奖励（1:1 iOS `Services/MilestoneRewardService.swift`）：
 * - 宠物进化阶段推进：young 50 / teen 100 / adult 150 / special 300（baby 0 起始不发）。
 * - 关系里程碑达成：统一 200 金币。
 * 去重靠 `CurrencyTransaction.relatedEntityId`（同事件只发一次）。静默入用户钱包；UI toast 由调用方按需展示。
 *
 * 注：iOS 的 100 个宠物成就（PetMilestones）是**纯展示、不发金币**，属 M11 图鉴 UI——本服务只认进化阶段 + 关系里程碑。
 */
@Singleton
class MilestoneRewardService @Inject constructor(
    private val currencyService: CurrencyService,
    private val currencyDao: CurrencyDao,
) {

    /** 宠物进化到新阶段发奖（已发过的同阶段不重复）。返回发放金币（0=已发/baby 无奖）。 */
    suspend fun rewardPetEvolution(
        petUuid: String,
        petName: String,
        stage: PetGrowthStage,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val amount = evolutionReward(stage)
        if (amount <= 0) return 0
        val key = petGrowKey(petUuid, stage)
        if (currencyDao.transactionExists(key)) {
            Log.d(TAG, "宠物进化奖励跳过·已发 pet=$petUuid stage=${stage.raw}")
            return 0
        }
        currencyService.addCoinsToUser(amount, CurrencyTransactionCategory.MILESTONE, "${petName}成长为${stage.displayName}", key, now)
        Log.i(TAG, "宠物进化奖励 +$amount pet=$petUuid stage=${stage.raw} key=$key")
        return amount
    }

    /** 关系里程碑达成发奖（统一 200）。基于 characterUuid + 建立时间秒去重。返回发放金币（0=已发）。 */
    suspend fun rewardRelationshipMilestone(
        characterUuid: String,
        characterName: String,
        relationshipName: String,
        establishedDateMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val key = relMilestoneKey(characterUuid, establishedDateMillis)
        if (currencyDao.transactionExists(key)) {
            Log.d(TAG, "关系里程碑奖励跳过·已发 char=$characterUuid key=$key")
            return 0
        }
        currencyService.addCoinsToUser(RELATIONSHIP_MILESTONE_REWARD, CurrencyTransactionCategory.MILESTONE, "$characterName · $relationshipName", key, now)
        Log.i(TAG, "关系里程碑奖励 +$RELATIONSHIP_MILESTONE_REWARD char=$characterUuid rel=$relationshipName key=$key")
        return RELATIONSHIP_MILESTONE_REWARD
    }

    private companion object {
        const val TAG = "MilestoneReward"
    }
}

// ── 纯函数（internal，单测反推 iOS 奖励值/key 格式） ──────────────────────

const val RELATIONSHIP_MILESTONE_REWARD = 200

/** 进化奖励：baby 0 / young 50 / teen 100 / adult 150 / special 300。 */
internal fun evolutionReward(stage: PetGrowthStage): Int = when (stage) {
    PetGrowthStage.BABY -> 0
    PetGrowthStage.YOUNG -> 50
    PetGrowthStage.TEEN -> 100
    PetGrowthStage.ADULT -> 150
    PetGrowthStage.SPECIAL -> 300
}

/** 进化去重 key：`pet_grow_{uuid}_{stageRaw}`。 */
internal fun petGrowKey(petUuid: String, stage: PetGrowthStage): String = "pet_grow_${petUuid}_${stage.raw}"

/** 关系里程碑去重 key：`rel_milestone_{uuid}_{epochSeconds}`（毫秒/1000，对齐 iOS `Int(timeIntervalSince1970)`）。 */
internal fun relMilestoneKey(characterUuid: String, establishedDateMillis: Long): String =
    "rel_milestone_${characterUuid}_${establishedDateMillis / 1000}"
