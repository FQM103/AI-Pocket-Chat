package com.situ.aichat.world

import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import java.time.LocalDate

/**
 * 懒结算的数据类型与贡献者契约（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §7 / W2 图纸 §3.3）。
 *
 * 「离开期间」被摊成一串本地日（[SettlementDay]），编排见 [WorldSettlementCoordinator]；本块只定义
 * 「一天」与「一个结算窗口」长什么样 + 内容贡献者（谁遇见谁 / 关系事件·属 W3/W4/W6）往这里挂的接口。
 * 本块**不写任何具体世界事件文案**——贡献者集合首落地时为空（`WorldModule` 的 `@Multibinds`）。
 */

/** 需结算的一个本地日：日期 + epochDay + 该日的确定性种子（`WorldSeeds.derive(seed, "day", epochDay)`）。 */
data class SettlementDay(
    val date: LocalDate,
    val epochDay: Long,
    val daySeed: Long,
)

/**
 * 一次结算的窗口（[WorldSettlementCoordinator.ensureSettled] 的产物·图纸 §3.3/§3.4）。
 *
 * @property days 需结算的本地日（按时间升序·可空——首启/冻结时为空）。
 * @property truncatedDays 被缺席封顶丢弃的更早天数（0 = 未截断·契约 §7 `MAX_CATCHUP_DAYS`）。
 * @property absenceMs `max(0, now - 锚)`；首启/冻结 = 0。
 * @property firstRun 首次结算（锚 == 0）：不补历史，只落锚。
 */
data class SettlementWindow(
    val days: List<SettlementDay>,
    val truncatedDays: Int,
    val absenceMs: Long,
    val firstRun: Boolean,
)

/**
 * 结算内容贡献者（W3/W4/W6 各自 `@IntoSet` 挂进 `WorldModule` 的空集）。
 *
 * 【贡献者契约·锁死·图纸 §3.3/§9】
 * ① **确定性**：产出只由 `(state.seed, window)` 决定，同输入必同输出——禁 `System.currentTimeMillis` /
 *    `Random()`（无种子）；随机一律走 `WorldSeeds.randomOf(day.daySeed)`。
 * ② **uuid 必须种子派生**（天然幂等）：
 *    `UUID.nameUUIDFromBytes("world:${id}:${day.daySeed}:${序号}".toByteArray()).toString()`
 *    ——中途进程死后重跑 = 同 uuid upsert 覆盖自身（W1 `upsertEvent` 是 `@Upsert`），无重复。
 * ③ **只按「日」粒度产出**：禁依赖「当日已过多少小时」——同日多次开 app 必须同产出。
 */
interface WorldSettlementContributor {
    /** 稳定短 id（入事件 uuid 派生串·契约②）。 */
    val id: String

    /** 就该窗口产出世界事件（遵守上面三条契约）；不落库——落库由 Coordinator 统一做。 */
    suspend fun settle(state: WorldStateEntity, window: SettlementWindow): List<WorldEventEntity>
}
