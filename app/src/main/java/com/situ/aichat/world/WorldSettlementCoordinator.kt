package com.situ.aichat.world

import android.util.Log
import com.situ.aichat.data.repository.WorldRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 懒结算编排（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §7 / W2 图纸 §3.4）：开 app 时把「离开期间」一次性
 * 算成窗口 → 逐贡献者产出世界事件 → 落库 → 推单调锚（**只进不退**）。全程 [Mutex] 串行（E7 防并发）。
 *
 * **本块不接线**：`ensureSettled` 当前只有测试调用；首个消费者（W5 小报 / W8 通知 / W11 世界卡）落地时才接。
 * **零 LLM、零网络**（§7.A 骨架恒零 LLM）；贡献者集合首落地为空（`WorldModule` `@Multibinds`）——对现有
 * App 行为零改变。事件先落、锚后推：中途进程死 = 事件已按种子派生 uuid upsert、锚未动 → 重跑同 uuid 覆盖
 * 自身（幂等），无重复。单个贡献者抛异常只吞掉记日志，不拖垮别家、不拦锚推进（世界永不死机）。
 */
@Singleton
class WorldSettlementCoordinator @Inject constructor(
    private val worldRepository: WorldRepository,
    private val contributors: Set<@JvmSuppressWildcards WorldSettlementContributor>,
) {
    private val mutex = Mutex()

    /** 确保结算到 [now]（epoch ms）。串行、幂等、只进不退；返回本次窗口（供消费者/测试观察）。 */
    suspend fun ensureSettled(now: Long): SettlementWindow = mutex.withLock {
        val state = worldRepository.ensureState()
        val zone = WorldClock.resolveZone(state.userTimezoneId)
        val window = computeWindow(seed = state.seed, anchor = state.lastSettledAt, now = now, zone = zone)

        for (contributor in contributors) {
            val events = try {
                contributor.settle(state, window)
            } catch (e: Exception) {
                // 单个贡献者失败 = 吞掉记日志、别家照跑、锚照推（图纸 §3.4 step3 / §9）。
                Log.w(TAG, "contributor '${contributor.id}' failed during settlement", e)
                emptyList()
            }
            events.forEach { worldRepository.recordEvent(it) } // @Upsert + 种子派生 uuid = 幂等
        }

        // 收尾恒调：MAX() 保只进不退——回拨场景等于没推（图纸 §3.4 step4 / §9）。
        worldRepository.advanceSettledAt(now)
        window
    }

    /** 窗口规则（锁死·图纸 §3.4 step2 / §9）：首启 / 冻结 / 正常+缺席封顶三分支。 */
    private fun computeWindow(seed: Long, anchor: Long, now: Long, zone: ZoneId): SettlementWindow {
        // 首启（锚 == 0）：不补假历史，只落锚。
        if (anchor == 0L) {
            return SettlementWindow(days = emptyList(), truncatedDays = 0, absenceMs = 0L, firstRun = true)
        }
        // 冻结（now <= 锚·设备时间回拨）：世界冻结，锚经 MAX() 不动。
        if (now <= anchor) {
            return SettlementWindow(days = emptyList(), truncatedDays = 0, absenceMs = 0L, firstRun = false)
        }
        // 正常：含两端、升序的本地日；超 MAX_CATCHUP_DAYS 只留最后 7 天（缺席封顶·契约 §7）。
        val startEpochDay = WorldClock.localDateOf(anchor, zone).toEpochDay()
        val endEpochDay = WorldClock.localDateOf(now, zone).toEpochDay()
        val allEpochDays = (startEpochDay..endEpochDay).toList()
        val truncatedDays = (allEpochDays.size - MAX_CATCHUP_DAYS).coerceAtLeast(0)
        val days = allEpochDays.takeLast(MAX_CATCHUP_DAYS).map { epochDay ->
            SettlementDay(
                date = LocalDate.ofEpochDay(epochDay),
                epochDay = epochDay,
                daySeed = WorldSeeds.derive(seed, "day", epochDay),
            )
        }
        return SettlementWindow(
            days = days,
            truncatedDays = truncatedDays,
            absenceMs = (now - anchor).coerceAtLeast(0L),
            firstRun = false,
        )
    }

    companion object {
        /** 缺席 catch-up 上限（契约 §7 缺席封顶·图纸 §9 禁改）。 */
        const val MAX_CATCHUP_DAYS = 7
        private const val TAG = "WorldSettlement"
    }
}
