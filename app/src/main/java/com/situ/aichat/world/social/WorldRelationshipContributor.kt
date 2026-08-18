package com.situ.aichat.world.social

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.world.SettlementWindow
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldSettlementContributor
import com.situ.aichat.world.link.WorldMirrorDeriver
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 关系系统的**结算贡献者**（契约 §8 / W4 图纸 §3.8）：世界懒结算时逐日委托 [WorldRelationshipEngine] 生成
 * 角色↔角色的故事。W4 是第一个真实结算贡献者——`@IntoSet` 挂进 W2 的 `WorldModule` 空集。
 *
 * 门控（护栏#7）：`worldRelationshipsEnabled=false` → 各过各的（零事件/零边/零镜像）；参与者 <2 → 空。
 * 遵守 W2 贡献者契约三条（确定性 / uuid 种子派生 / 日粒度）——引擎所有随机仅出自对·日流。关系事件/边由
 * 引擎经 DAO 直落（幂等门保护）。
 *
 * **W5 D13 闭窗**：world_event 镜像不再由引擎当场返回，改由 [WorldMirrorDeriver] 从**已落库的关系事件重派生**
 * （`world:relw:` 种子 uuid + Coordinator `@Upsert` = 天然幂等）。逐日 settle 后回看 7 天（自愈崩溃 + 窗口
 * 截断残缝·与 `MAX_CATCHUP_DAYS=7` 同宽）派生镜像返回给 Coordinator 落库。
 */
@Singleton
class WorldRelationshipContributor @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val characterDao: CharacterDao,
    private val engine: WorldRelationshipEngine,
    private val mirrorDeriver: WorldMirrorDeriver,
) : WorldSettlementContributor {

    override val id: String = "relationships"

    override suspend fun settle(state: WorldStateEntity, window: SettlementWindow): List<WorldEventEntity> {
        val settings = settingsRepository.appSettings.first()
        if (!settings.worldRelationshipsEnabled) return emptyList() // 开关关 = 各过各的
        val participants = characterDao.getInWorld()
        if (participants.size < 2) return emptyList()
        for (day in window.days) {
            engine.settleDay(state, settings, participants, day)
        }
        // D13：镜像从已落库关系事件重派生（种子 uuid + Upsert = 幂等闭窗）。窗空（首启/冻结）= 无新结算 → 零镜像。
        val firstDay = window.days.firstOrNull() ?: return emptyList()
        val zone = WorldClock.resolveZone(state.userTimezoneId)
        val lowerBound = firstDay.date.atStartOfDay(zone).toInstant().toEpochMilli() - 7 * 86_400_000L
        return mirrorDeriver.deriveSince(lowerBound)
    }
}
