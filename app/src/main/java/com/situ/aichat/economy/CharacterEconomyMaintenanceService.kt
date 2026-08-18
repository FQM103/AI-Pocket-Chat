package com.situ.aichat.economy

import android.util.Log
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterWalletEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.EconomyNotificationTier
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 角色真经济维护编排（1:1 iOS `AppBootstrapService.runCharacterSalaryMaintenance` + `runCharacterEconomicEventMaintenance`）。
 * P1-39 起对齐 iOS 真实**两段式**结构（AppBootstrapService.swift:156-179）：
 * - **同步段**（本地瞬时·`running` 锁内）：对**全部角色**逐只跑 ① 发薪 ② 入职储蓄（iOS :162-163 序）
 *   ③ 季度奖金 ④ 房租 ⑤ 昨日日程消费；顺路收集 needsInfer（`salaryInferred == false`，iOS :167 filter）。
 * - **异步段**（[launchInferencePass]·服务自有 scope·独立防叠跑）：仅 needsInfer 角色**串行** LLM 推断
 *   （防 rate limit，iOS :171）+ 推断成功后**只补入职储蓄**——绝不当场 payoutIfDue，当月工资等下次维护
 *   同步段再发（=iOS :177 异步段不补发薪；修正旧一段式「推断完立即发薪」的 💰 时序分叉）。
 *   runMaintenance 返回即跑送礼维护，不被推断卡住（=iOS 送礼 Task 不等推断 Task）。
 *
 * 顺序对齐 iOS「先收入后支出」（奖金→房租→日程）。`currencySystemEnabled` 关 → 整块跳过；`scheduleSystemEnabled`
 * 仅 gate 日程消费（房租/奖金照发）。全靠幂等 key + `lastSalaryDate`/`lastEconomicScanDate` 防重复 → 回前台反复调安全。
 * 重入锁防多次 ON_RESUME 叠跑（iOS 仅启动跑一次无此问题；安卓每次回前台跑，锁是必要加固）。
 * 月薪推断复用 CHAT 路由（同 iOS）；config 循环外解析一次（已登记等价：iOS 每次推断内部解析）。
 *
 * P1-40 经济可见性：在各调用点配对收集「真发生事件」（kind 调用点已知 + 返回的入/扣账额），段尾经
 * [EconomySummaryNotifier] 聚合发 1 条（三档设置·失败不影响维护本体；异步段的入职储蓄单独聚合一条，
 * 固定 id 替换式）。**纯旁路**——零金额/写路径/幂等改动。
 */
@Singleton
class CharacterEconomyMaintenanceService @Inject constructor(
    private val characterRepo: CharacterRepository,
    private val currencyService: CurrencyService,
    private val salaryInference: CharacterSalaryInferenceService,
    private val salaryPayout: CharacterSalaryPayoutService,
    private val fixedEconomic: CharacterFixedEconomicEventService,
    private val scheduleEconomic: CharacterEconomicEventService,
    private val apiConfigRepo: ApiConfigRepository,
    private val settingsRepo: SettingsRepository,
    private val economyNotifier: EconomySummaryNotifier,
) {
    private val running = AtomicBoolean(false)

    // 异步推断段：服务自有 scope（仿 BusyReplyService 范式·不随 ViewModel 生命周期取消）+ 独立防叠跑
    // （每次 ON_RESUME 都会触发 runMaintenance，推断可达数十秒，必须独立于同步段的 running 锁）。
    private val inferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inferring = AtomicBoolean(false)

    suspend fun runMaintenance(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        if (!running.compareAndSet(false, true)) return
        try {
            val settings = settingsRepo.getAppSettings()
            if (!settings.currencySystemEnabled) return
            val characters = characterRepo.getAll()
            if (characters.isEmpty()) return
            // 月薪推断走 CHAT 路由（未配置 API → config 为 null，异步段整体跳过，其余仍跑）
            val config = apiConfigRepo.resolveConfigValues(ApiFunction.CHAT)
            val events = mutableListOf<EconomyEvent>()
            val wallets = mutableListOf<CharacterWalletEntity>()
            // ── 同步段（本地瞬时·对全部角色）─────────────────────────────────
            for (character in characters) runCatching {
                // 批2 复核修（MED#3 顺手同包）：角色被删窗口（毫秒级）的 FK 违反不崩维护，跳过该角色继续（=iOS try?）。
                // 确保钱包存在（幂等，=iOS :156-158 建钱包段）
                val w = currencyService.walletForCharacter(character.uuid, now)
                wallets += w
                // ① 发薪 ② 入职储蓄（iOS :162-163 序·均幂等、不读余额，同一快照安全）
                val paid = salaryPayout.payoutIfDue(w, now)
                if (paid > 0) events += EconomyEvent(EconomyEventKind.SALARY, character.uuid, character.name, paid, now)
                val onboarded = salaryPayout.onboardingIfNeeded(w, now)
                if (onboarded > 0) events += EconomyEvent(EconomyEventKind.ONBOARDING, character.uuid, character.name, onboarded, now)
                // ③ 季度奖金（收入先于支出） ④ 房租（服务内各自读钱包 fresh）
                val bonus = fixedEconomic.processQuarterlyBonusIfDue(character.uuid, now)
                if (bonus > 0) events += EconomyEvent(EconomyEventKind.BONUS, character.uuid, character.name, bonus, now)
                val rent = fixedEconomic.processRentIfDue(character.uuid, now)
                if (rent != null) {
                    // 欠租（含 0 元留痕）报「本应额」叙事值；足额报实扣额。
                    events += if (rent.charged >= rent.due) {
                        EconomyEvent(EconomyEventKind.RENT, character.uuid, character.name, rent.charged, now)
                    } else {
                        EconomyEvent(EconomyEventKind.RENT_ARREARS, character.uuid, character.name, rent.due, now)
                    }
                }
                // ⑤ 过去日日程消费（仅 scheduleSystemEnabled）：补扫缺日（R1·跨多日不开 app 中间天数补齐·封顶 7 天·
                //    与日程补算对称；逐日幂等防重）。日程补算先于本段（ScheduleGenerationWorker：backfill→ensure）使缺日
                //    日程已就绪；某缺日若日程尚未生成则该日标记已扫不扣款（=单日扫旧行为，非本次新增）。
                if (settings.scheduleSystemEnabled) {
                    val spent = scheduleEconomic.processMissedDaysIfNeeded(character, now, zone)
                    if (spent > 0) events += EconomyEvent(EconomyEventKind.SCHEDULE_SPEND, character.uuid, character.name, spent, now)
                }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "同步段单角色失败（跳过继续）：${character.name}", it)
            }
            if (events.isNotEmpty()) {
                Log.i(TAG, "经济可见性：${events.size} 条事件，tier=${settings.economyNotificationTier}")
                runCatching {
                    economyNotifier.notifySummary(events, EconomyNotificationTier.fromRaw(settings.economyNotificationTier))
                }.onFailure { Log.w(TAG, "经济通知失败（不影响维护）", it) }
            }
            // ── 异步段（仅 needsInfer·不阻塞 runMaintenance 返回）────────────────
            val needsInferUuids = selectNeedsInference(wallets, hasConfig = config != null)
            if (needsInferUuids.isNotEmpty() && config != null) {
                val targets = characters.filter { it.uuid in needsInferUuids }
                launchInferencePass(targets, config, settings.economyNotificationTier)
            }
            Log.d(TAG, "角色经济维护同步段完成：${characters.size} 个角色（待推断 ${needsInferUuids.size}）")
        } finally {
            running.set(false)
        }
    }

    /**
     * P1-43 创建角色即时推断（=iOS CharacterDetailView+Actions.swift:215-233 create 块的异步 Task）：
     * 建钱包（幂等·=iOS :225）→ 复用 [launchInferencePass]（串行推断 → 成功后只补入职储蓄，绝不
     * payoutIfDue=P1-39 红线·iOS 创建路径同样无发薪——当月工资等下次 ON_RESUME 维护同步段）。
     * 跑在服务自有 [inferenceScope]（save 即 dismiss，viewModelScope 会取消数十秒的 LLM 推断）；
     * 维护异步段正占用 inferring 守卫时本次静默跳过——salaryInferred=false 自愈，下次 ON_RESUME 兜底。
     * 登记有意分叉：iOS 创建路径漏 currencySystemEnabled 守卫（AppBootstrapService.swift:146 自注
     * 「Sub D 修复 Sub C 遗漏」而创建块同属 Sub C 未修）——安卓按书面意图补 gate，与维护段口径一致。
     */
    fun runForNewCharacter(characterUuid: String) {
        inferenceScope.launch {
            runCatching {
                val settings = settingsRepo.getAppSettings()
                if (!settings.currencySystemEnabled) return@launch
                val character = characterRepo.get(characterUuid) ?: return@launch
                // 即使无 API 配置也先建钱包（幂等），与 iOS :225 顺序一致；推断段缺 config 则止步于此。
                val wallet = currencyService.walletForCharacter(characterUuid)
                if (wallet.salaryInferred) return@launch
                val config = apiConfigRepo.resolveConfigValues(ApiFunction.CHAT) ?: return@launch
                Log.i(TAG, "新建角色即时推断启动：${character.name}")
                launchInferencePass(listOf(character), config, settings.economyNotificationTier)
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "新建角色即时推断失败（salaryInferred=false 下次维护重试）", it)
            }
        }
    }

    /**
     * 异步推断段（=iOS AppBootstrapService.swift:170-179 的 `Task {}`）：串行推断 + 成功后**只补入职储蓄**。
     * 每角色用调用时刻的 now（=iOS onboardingIfNeeded 默认 `Date()`；推断可达数十秒，沿用同步段 now 会让
     * 流水时间戳倒挂——金额与幂等不受影响，onboarding key 无日期成分）。
     */
    private fun launchInferencePass(
        targets: List<CharacterEntity>,
        config: ApiConfigValues,
        tierRaw: String,
    ) {
        if (!inferring.compareAndSet(false, true)) return
        inferenceScope.launch {
            try {
                val events = mutableListOf<EconomyEvent>()
                for (character in targets) {
                    // 批2 复核修（MED#3）：per-character 吞错=iOS try? 语义——推断窗口（数十秒）内角色被删时
                    // walletForCharacter「取或建」会违 FK 抛 SQLiteConstraintException，scope 无 CEH 直接崩进程；
                    // CancellationException 重抛不吞取消。
                    runCatching {
                        // 批2 复核修（LOW#4）：先 fresh 复核——双前台窗口重叠时快照可能过期，已推断（含期间
                        // 手动设薪 applyManualSalaryEdit）的角色跳过，防冗余 LLM 调用与覆写月薪。
                        val pre = currencyService.walletForCharacter(character.uuid, System.currentTimeMillis())
                        if (pre.salaryInferred) return@runCatching
                        // 推断失败静默保持 salaryInferred=false，下次维护重试（=iOS）。
                        salaryInference.inferAndWriteBack(character, config, System.currentTimeMillis())
                        val now = System.currentTimeMillis()
                        val fresh = currencyService.walletForCharacter(character.uuid, now)
                        val onboarded = salaryPayout.onboardingIfNeeded(fresh, now)
                        if (onboarded > 0) events += EconomyEvent(EconomyEventKind.ONBOARDING, character.uuid, character.name, onboarded, now)
                        // 绝不在此 payoutIfDue：当月工资等下次维护同步段（=iOS :177 异步段只补 onboarding）。
                    }.onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        Log.w(TAG, "推断段单角色失败（跳过继续，=iOS try? 吞错）：${character.name}", it)
                    }
                }
                if (events.isNotEmpty()) {
                    runCatching {
                        economyNotifier.notifySummary(events, EconomyNotificationTier.fromRaw(tierRaw))
                    }.onFailure { Log.w(TAG, "推断段经济通知失败（不影响推断）", it) }
                }
                Log.i(TAG, "月薪推断异步段完成：${targets.size} 个角色")
            } finally {
                inferring.set(false)
            }
        }
    }

    private companion object {
        const val TAG = "EconomyMaintenance"
    }
}

/**
 * 同步段顺路筛出待推断角色 uuid（纯函数·P1-39）：`salaryInferred == false` 即待推断（iOS :167
 * `($0.wallet?.salaryInferred ?? true) == false`——iOS 的「无钱包视作已推断」在安卓不可达，同步段已确保
 * 钱包存在）；无 API 配置（[hasConfig]=false）→ 整段跳过（=iOS 推断内部「无配置返回 nil」的提前短路）。
 */
internal fun selectNeedsInference(
    wallets: List<CharacterWalletEntity>,
    hasConfig: Boolean,
): Set<String> {
    if (!hasConfig) return emptySet()
    return wallets.filter { !it.salaryInferred }.map { it.characterUuid }.toSet()
}
