package com.situ.aichat.widget

import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.PetWriteLock
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.pet.PetCareService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 处理宠物小组件操作深链（喂食/摸摸）。1:1 iOS `MainTabView.handlePetActionDeepLink`：
 * 取设置（宠物系统关则忽略）→ 喂食=feed / 摸摸=复用 play → upsert。**不做进化/奖励**（与 iOS widget 路径一致，
 * 留给下次回前台 PetMaintenanceService）。小组件随后由 [PetWidgetSync] 观察到 upsert 自动刷新（= iOS syncToWidget force）。
 *
 * 有意更优：iOS 取「第一只宠物」，安卓按小组件展示的那只 characterUuid 执行——操作对象与所见一致。
 */
@Singleton
class PetWidgetActionHandler @Inject constructor(
    private val petRepository: PetRepository,
    private val petWriteLock: PetWriteLock,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun perform(characterUuid: String, action: String) {
        val settings = settingsRepository.getAppSettings()
        if (!settings.petSystemEnabled) return
        val pet = petRepository.getForCharacter(characterUuid) ?: return
        // D1d：锁内重读最新→护理→写回，与回前台批量维护/详情页护理串行（按 pet uuid）。
        petWriteLock.withPetLock(pet.uuid) {
            val fresh = petRepository.getByUuid(pet.uuid) ?: return@withPetLock
            val updated = when (action) {
                PetWidgetIntents.ACTION_FEED -> PetCareService.feed(fresh, settings)
                PetWidgetIntents.ACTION_PET -> PetCareService.play(fresh, settings) // 摸摸=增加心情，复用 play（1:1 iOS）
                else -> return@withPetLock
            }
            petRepository.upsert(updated)
        }
    }
}
