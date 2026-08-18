package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.repository.PetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宠物饿/病提醒的响应式重排桥（13.7c）。App 级一处观察 [PetRepository.observeAll]，提醒相关字段一变就
 * 经 [PetReminderScheduler.rescheduleAll] 重算精确闹钟。
 *
 * **为什么这样做**（同 [com.situ.aichat.widget.PetWidgetSync] 的理由）：宠物 upsert 散落在 8+ 处
 * （PetDetailViewModel 喂食/护理/治疗、ChatViewModel 聊天加成、各服务衰减/维护、小组件 feed），逐点接线既
 * 侵入又易漏。在 App 级一处观察自动覆盖所有写路径，且数据层不反依赖。**不 drop 首帧**——开 App 第一帧就
 * 据当前状态把闹钟建/对齐好；[distinctUntilChanged] 去抖（仅提醒相关字段变化才重排，避免每次无关 upsert 空跑）。
 *
 * 开机后无 UI 进程的重排由 [com.situ.aichat.work.NotificationRescheduleWorker] 兜（精确闹钟不跨重启）。
 */
@Singleton
class PetReminderSync @Inject constructor(
    private val petRepository: PetRepository,
    private val scheduler: PetReminderScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    /** 幂等启动；由 [com.situ.aichat.ui.AppViewModel] 在 init 调用一次。 */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            petRepository.observeAll()
                .map { pets -> pets.map { reminderSignature(it) } }
                .distinctUntilChanged()
                .collect { scheduler.rescheduleAll() }
        }
    }

    /** 影响饿/病预测的字段签名——仅这些变才重排（hunger / 互动时间 / 忽略相位 / 衰减基准）。 */
    private fun reminderSignature(pet: CharacterPetEntity): List<String> = listOf(
        pet.characterUuid,
        pet.hunger.toString(),
        pet.lastInteractionDate.toString(),
        pet.neglectPhaseRaw,
        pet.metadata.lastDecayDate.toString(),
    )
}
