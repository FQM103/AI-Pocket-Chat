package com.situ.aichat.widget

import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.pet.toPetWidgetData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宠物小组件的响应式同步桥（P11.3）：观察 Room 宠物流，展示中那只的快照一变就 nudge 小组件重渲染。
 *
 * **为什么这样做**：宠物 upsert 散落在 8+ 处（PetDetailViewModel/ChatViewModel/各服务），逐点触发既侵入又易漏。
 * 在 App 级一处观察 [PetRepository.observeAll]，自动覆盖所有写路径；且数据层不反依赖 widget 层（保持分层）。
 * [drop] 跳过首帧（开 App 时小组件本就自读最新），只在真正变化时刷新；[distinctUntilChanged] 去抖。
 */
@Singleton
class PetWidgetSync @Inject constructor(
    private val petRepository: PetRepository,
    private val updater: PetWidgetUpdater,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    /** 幂等启动；由 [com.situ.aichat.ui.AppViewModel] 在 init 调用一次。 */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            petRepository.observeAll()
                .map { pets -> pets.maxByOrNull { it.lastInteractionDate ?: it.adoptedDate }?.toPetWidgetData() }
                .distinctUntilChanged()
                .drop(1)
                .collect { updater.refresh() }
        }
    }
}
