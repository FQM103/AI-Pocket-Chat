package com.situ.aichat.pet

import android.util.Log
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.PetWriteLock
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.economy.MilestoneRewardService
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宠物启动批量维护（1:1 iOS `AppBootstrapService.applyPetDecayAndGrowthCheck`）：App 回前台时对所有宠物
 * 执行惰性衰减 + 进化检查，再触发每日宠物日记。由 [com.situ.aichat.ui.AppViewModel] 在 ON_RESUME 调用。
 *
 * 衰减是**惰性时间戳补算**（[PetCareService.applyDecay]，不足 1 小时 no-op、幂等），故无需常驻后台/周期任务
 * （SPEC §3.2：打开即按时间差补算永远正确）——与 iOS bootstrap-only 一致，也最适合 HyperOS 杀后台。
 *
 * 桌面小组件同步 → P11；宠物饿/病提醒（13.7c，超越 iOS `schedulePetNotificationsIfNeeded` 的预测式精确闹钟）
 * 由 [PetReminderSync] App 级观察宠物流自动重排（本服务衰减写库即触发其重算），无需在此显式调度。

 */
@Singleton
class PetMaintenanceService @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val petRepository: PetRepository,
    private val petWriteLock: PetWriteLock,
    private val petDiaryGenerationService: PetDiaryGenerationService,
    private val milestoneRewardService: MilestoneRewardService,
) {
    suspend fun runStartupMaintenance(
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val settings = settingsRepo.getAppSettings()
        if (!settings.petSystemEnabled) return

        // 衰减 → 进化检查（iOS decayAllPets 后 checkAllPets 两遍循环；宠物互相独立，合并为逐只一次写回）。
        // D1d：每只宠物的「锁内重读最新→衰减/进化→写回」串行化，杜绝与用户护理整行写互相覆盖（窗口极窄但真实）。
        val pets = petRepository.getAll()
        var maintained = 0
        for (pet in pets) {
            val written = petWriteLock.withPetLock(pet.uuid) {
                val fresh = petRepository.getByUuid(pet.uuid) ?: return@withPetLock null
                val decayed = PetCareService.applyDecay(fresh, settings, now, zone)
                val evolveResult = PetGrowthService.checkAndEvolve(decayed, now)
                if (evolveResult.pet === fresh) return@withPetLock null // 衰减不足 1 小时且未进化 → 跳过无谓写库
                petRepository.upsert(evolveResult.pet)
                evolveResult
            } ?: continue
            maintained++
            // P9.1c 进化金币奖励（去重入账；启动维护批量进化也发）。在锁外发奖：写用户钱包、不持宠物锁。
            if (written.didEvolve) milestoneRewardService.rewardPetEvolution(written.pet.uuid, written.pet.name, written.newStage, now)
        }
        if (maintained > 0) Log.d(TAG, "宠物批量维护：$maintained/${pets.size} 只有更新")

        // 宠物日记自动生成（每天一次；自带开关/今日去重/API 守卫）。
        petDiaryGenerationService.checkAndAutoGenerate(now, zone)
    }

    private companion object {
        const val TAG = "PetMaintenance"
    }
}
