package com.situ.aichat.economy

import android.util.Log
import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 角色日程派生经济事件调度（1:1 iOS `Services/CharacterEconomicEventService.swift`）。扫角色某日日程，每个事件过
 * [ScheduleEconomicEventExtractor]，按规则扣款写流水。四条核心规则：
 * 1. **事件级幂等**：每事件最多一条流水，key `schedule_event_{uuid}`。
 * 2. **日级幂等**：`wallet.lastEconomicScanDate`，启动反复调不重扫（也防「昨天没钱的饭、今天发工资补扣」秋后算账）。
 * 3. **同类聚合**：一天同 category 最多扣 [MAX_EVENTS_PER_CATEGORY_PER_DAY]=3（防 LLM 日程 spam），按 startTime 早→晚保留前 3。
 * 4. **余额不足三档降级**：足额扣 / 部分扣到 0 note 追加(余额不足·本应X) / 余额 0 **跳过不写流水**（没钱就没去，与房租相反）。
 *
 * **扫已成事实的过去日**（事件成真才记账）：常规 [processMissedDaysIfNeeded] 补 lastEconomicScanDate 次日→昨天的
 * 全部缺日（R1·封顶 7 天·与日程补算对称），[processYesterdayIfNeeded] 仅扫昨天（保留单日入口）。维护循环串行
 * 调用、每次读钱包 fresh（接 9.1b-4 回前台触发）。
 */
@Singleton
class CharacterEconomicEventService @Inject constructor(
    private val currencyService: CurrencyService,
    private val currencyDao: CurrencyDao,
    private val scheduleDao: ScheduleDao,
) {

    /** 扫角色昨天的日程。返回实际扣款总额（0=全免费/无日程/余额 0/已扫过）。 */
    suspend fun processYesterdayIfNeeded(
        character: CharacterEntity,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val yesterdayStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return processSchedule(character, yesterdayStart, now, zone)
    }

    /**
     * 补扫 [lastEconomicScanDate] 次日到昨天的全部缺日（R1：跨多日不开 app 中间天数此前永久漏扣且被游标封死）。
     * 与日程数据本身已被 [ScheduleCoordinator.backfillMissedDays] 补算 7 天对称——经济作为日程派生应一并补齐，否则
     * 「日程显示这几天有消费但钱一分没扣」内部不一致。封顶最近 [SCAN_BACKFILL_CAP_DAYS] 天（同日程补算）。
     *
     * 逐日升序调 [processSchedule]（各日有日级+事件级幂等天然防重，逐日扣减用当下 fresh 余额=与单日扫一致无新风险）。
     * **lastEconomicScanDate 为 null（从未扫过）→ 只扫昨天**（保留旧行为，不给从未扫过的角色凭空补 7 天扣款）。
     * 返回各日扣款总额。
     */
    suspend fun processMissedDaysIfNeeded(
        character: CharacterEntity,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val yesterdayStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val wallet = currencyDao.getCharacterWallet(character.uuid)
            ?: currencyService.walletForCharacter(character.uuid, now)
        val days = wallet.lastEconomicScanDate?.let { last ->
            missedEconomicScanDays(startOfDayMillis(last, zone), yesterdayStart, zone)
        } ?: listOf(yesterdayStart)
        var total = 0
        for (dayStart in days) total += processSchedule(character, dayStart, now, zone)
        return total
    }

    /** 扫角色指定日期日程并处理经济事件。返回实际扣款总额。 */
    suspend fun processSchedule(
        character: CharacterEntity,
        dateMillis: Long,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val normalizedDate = startOfDayMillis(dateMillis, zone)
        val wallet = currencyDao.getCharacterWallet(character.uuid) ?: currencyService.walletForCharacter(character.uuid, now)

        // 日级幂等：已扫过该日或更晚 → 跳过
        wallet.lastEconomicScanDate?.let { last ->
            if (startOfDayMillis(last, zone) >= normalizedDate) return 0
        }

        val schedule = scheduleDao.scheduleFor(character.uuid, normalizedDate)
        if (schedule == null) {
            // 无日程也标记已扫，避免反复 fetch
            currencyService.setCharacterLastEconomicScanDate(character.uuid, normalizedDate, now)
            return 0
        }

        // 1. 事件按 startTime 升序，过 Extractor + 事件级幂等去重
        val events = scheduleDao.eventsForSchedule(schedule.uuid).sortedBy { it.startTime }
        val impacts = ArrayList<ScheduleEconomicImpact>()
        for (e in events) {
            val impact = ScheduleEconomicEventExtractor.extract(
                activity = e.activity,
                location = e.location,
                relatedCharacterNames = e.relatedCharacterNames,
                startTimeMillis = e.startTime,
                eventId = e.uuid,
                monthlySalary = wallet.monthlySalary,
                occupation = character.occupation,
                zone = zone,
            ) ?: continue
            if (currencyDao.transactionExists(scheduleEventKey(impact.sourceEventId))) continue
            impacts.add(impact)
        }

        // 2. 同类聚合：每 category 最多 3 次（原序）
        val counter = HashMap<ScheduleEconomicCategory, Int>()
        val finalImpacts = ArrayList<ScheduleEconomicImpact>()
        for (impact in impacts) {
            val count = counter.getOrDefault(impact.category, 0)
            if (count >= MAX_EVENTS_PER_CATEGORY_PER_DAY) continue
            counter[impact.category] = count + 1
            finalImpacts.add(impact)
        }

        // 3. 逐条扣款（余额不足三档降级）
        var totalSpent = 0
        for (impact in finalImpacts) totalSpent += spendWithFallback(character.uuid, impact, now)

        // 4. 无论扣没扣都标记已扫
        currencyService.setCharacterLastEconomicScanDate(character.uuid, normalizedDate, now)
        return totalSpent
    }

    /** 三档扣款：余额≥应扣→扣全额 / 0<余额<应扣→扣到 0 + note 追加(余额不足) / 余额 0→跳过不写流水。 */
    private suspend fun spendWithFallback(characterUuid: String, impact: ScheduleEconomicImpact, now: Long): Int {
        val balance = currencyDao.getCharacterWallet(characterUuid)?.coinBalance ?: 0
        val key = scheduleEventKey(impact.sourceEventId)
        return when {
            balance >= impact.amount -> {
                currencyService.spendCoinsFromCharacter(characterUuid, impact.amount, CurrencyTransactionCategory.UNEXPECTED_EXPENSE, impact.note, key, now)
                Log.d(TAG, "日程消费·足额 char=$characterUuid amount=${impact.amount} key=$key")
                impact.amount
            }
            balance > 0 -> {
                currencyService.spendCoinsFromCharacter(characterUuid, balance, CurrencyTransactionCategory.UNEXPECTED_EXPENSE, "${impact.note}(余额不足 · 本应 ${impact.amount})", key, now)
                Log.d(TAG, "日程消费·部分扣(余额不足) char=$characterUuid charged=$balance due=${impact.amount} key=$key")
                balance
            }
            else -> {
                Log.d(TAG, "日程消费·跳过(余额 0) char=$characterUuid due=${impact.amount} key=$key")
                0 // 余额 0：跳过不写流水（没钱就没去）
            }
        }
    }

    private companion object {
        const val TAG = "EconomicEvent"
    }
}

// ── 纯函数（internal，单测） ──────────────────────────────────────────────

const val MAX_EVENTS_PER_CATEGORY_PER_DAY = 3

/** 经济日程补扫封顶天数（R1）。与日程补算 [ScheduleCoordinator.backfillMissedDays] 的 7 天对称。 */
const val SCAN_BACKFILL_CAP_DAYS = 7

/** 事件级幂等 key：`schedule_event_{eventId}`。 */
internal fun scheduleEventKey(eventId: String): String = "schedule_event_$eventId"

internal fun startOfDayMillis(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * 经济补扫缺日清单（纯函数·R1，与日程 `backfillDateMillis` 同语义）：给「最后已扫日 0 点」[lastScanDayStartMillis]
 * 与「昨天 0 点」[yesterdayStartMillis]，返回需补扫的各日 0 点毫秒（升序）：(lastScan 次日 … 昨天]，超
 * [SCAN_BACKFILL_CAP_DAYS] 天只留最近 [SCAN_BACKFILL_CAP_DAYS] 天。lastScan>=yesterday（含已扫到今天/未来）→ 空。
 * 用 [zone] 做 DST 安全的按日推进。
 */
internal fun missedEconomicScanDays(
    lastScanDayStartMillis: Long,
    yesterdayStartMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<Long> {
    val lastScan = Instant.ofEpochMilli(lastScanDayStartMillis).atZone(zone).toLocalDate()
    val yesterday = Instant.ofEpochMilli(yesterdayStartMillis).atZone(zone).toLocalDate()
    if (!lastScan.isBefore(yesterday)) return emptyList()
    val dates = mutableListOf<Long>()
    var current = lastScan.plusDays(1)
    while (!current.isAfter(yesterday)) {
        dates.add(current.atStartOfDay(zone).toInstant().toEpochMilli())
        current = current.plusDays(1)
    }
    return if (dates.size > SCAN_BACKFILL_CAP_DAYS) dates.takeLast(SCAN_BACKFILL_CAP_DAYS) else dates
}
