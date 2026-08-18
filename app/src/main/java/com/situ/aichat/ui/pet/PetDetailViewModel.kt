package com.situ.aichat.ui.pet

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.data.repository.PetWriteLock
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.economy.CurrencyService
import com.situ.aichat.economy.MilestoneRewardService
import com.situ.aichat.pet.AdoptionProgress
import com.situ.aichat.pet.PetAdoptionRules
import com.situ.aichat.pet.PetCareService
import com.situ.aichat.pet.PetGrowthService
import com.situ.aichat.pet.PetGrowthStage
import com.situ.aichat.pet.PetJson
import com.situ.aichat.pet.PetMilestones
import com.situ.aichat.pet.PetNeglectPhase
import com.situ.aichat.pet.PetReactionTexts
import com.situ.aichat.pet.PetWalkService
import com.situ.aichat.pet.growthStage
import com.situ.aichat.pet.metadata
import com.situ.aichat.pet.neglectPhase
import com.situ.aichat.pet.personalityType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 宠物详情页 UI 状态（pet 来自 Room 观察；其余为照顾交互的临时 UI 状态）。 */
data class PetDetailUiState(
    val pet: CharacterPetEntity? = null,
    val loading: Boolean = true,
    val characterName: String = "",
    val feedCooldown: Boolean = false,
    val cleanCooldown: Boolean = false,
    val playCooldown: Boolean = false,
    val treatCooldown: Boolean = false,
    val searchCooldown: Boolean = false,
    val reactionText: String? = null,
    val activeParticleEffect: PetParticleEffect? = null,
    val effectStartMillis: Long? = null,
    /** 照顾后的临时动画状态（覆盖默认 animationStateFor，2s 后恢复）。 */
    val careAnimationState: PetSpriteManager.AnimationState? = null,
    /** 顺序 toast 槽（P1-34 queue 化：进化金币 toast 与成就批 toast 共用，由 VM 单消费者协程串行供给，2.5s 停留自动清）。 */
    val milestoneToast: String? = null,
    /** 成就批解锁 success 触觉令牌（P1-34 拍板 A：每批一次，drain 在 toast 展示瞬间 +1——非入队时，防连续批瞬间双震）。 */
    val achievementHapticToken: Int = 0,
    /** 庆祝触觉令牌（pet-logic-3：进化/学技能/治愈/寻回/散步结算播 EVOLVE 粒子时 +1，UI 侧观察变化触发一次 success 触觉）。 */
    val celebrationHapticToken: Int = 0,
    /** 未果 medium 触觉令牌（P1-14：治疗未愈/寻回未果时 +1，=iOS performTreat:546/performSearch:558 else 分支 hapticFeedToken）。 */
    val careMediumHapticToken: Int = 0,
    /** 非 null = 弹散步结算页。 */
    val walkSettlement: PetWalkService.WalkSettlement? = null,
    // 无宠物时的领养进度
    val adoptionProgress: AdoptionProgress? = null,
    val canAdopt: Boolean = false,
)

/**
 * 宠物详情页 VM（1:1 iOS `PetDetailView` 的照顾逻辑）：feed/clean/play/treat/search/startWalk 调用已建
 * 服务（[PetCareService]/[PetGrowthService]/[PetWalkService]）→ upsert（Room 流回推刷新）→ 设冷却(3s)/
 * 临时动画(2s)/粒子(2-3s)/反应气泡(1.5s)。散步完成结算（金币入账 → P9 stub）。无宠物时算领养进度。
 */
@HiltViewModel
class PetDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val petRepository: PetRepository,
    private val petWriteLock: PetWriteLock,
    private val characterRepository: CharacterRepository,
    private val messageDao: MessageDao,
    private val settingsRepo: SettingsRepository,
    private val milestoneRewardService: MilestoneRewardService,
    private val currencyService: CurrencyService,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    val characterUuid: String = savedStateHandle.get<String>(ARG_CHARACTER_UUID) ?: ""

    private val _state = MutableStateFlow(PetDetailUiState())
    val state: StateFlow<PetDetailUiState> = _state.asStateFlow()

    private var settings = AppSettings()
    private var careToken = 0
    private var reactionToken = 0
    private var settlingWalk = false

    /** toast 队列元素：text=已本地化文案（不含 🏆——渲染处 PetDetailScreen 已硬编码前缀，资源带了会双奖杯）；
     *  successHaptic=展示瞬间触发一次 success 触觉（成就批=true；进化金币=false，进化触觉已由 celebrationHapticToken 发）。 */
    private data class PetToastItem(val text: String, val successHaptic: Boolean)

    /** P1-34 queue 化：原单 String 槽进化金币与成就同帧会撞；queue 拥有槽生命周期（删除原 careToken 对 toast 的
     *  守卫=顺手修掉「2.5s 停留期内再次 care 使 token 失配→clear 被跳过→toast 永久滞留」的边角 bug）。 */
    private val toastQueue = Channel<PetToastItem>(Channel.UNLIMITED)

    init {
        viewModelScope.launch { settings = settingsRepo.getAppSettings() }
        viewModelScope.launch {
            characterRepository.get(characterUuid)?.let { c -> _state.update { it.copy(characterName = c.name) } }
        }
        viewModelScope.launch {
            petRepository.observeForCharacter(characterUuid).collect { pet ->
                _state.update { it.copy(pet = pet, loading = false) }
                if (pet == null) refreshAdoptionStatus() else checkWalkCompletion()
            }
        }
        // P1-34 toast 单消费者：串行展示（0.5s 入队延迟由生产方控制=iOS 节奏）→2.5s 停留→清→250ms 间隙防粘连。
        // 挂 viewModelScope：退宠物页未播完的排队 toast 随 VM 销毁丢弃（页面级 UI 可接受）。
        viewModelScope.launch {
            for (item in toastQueue) {
                _state.update {
                    it.copy(
                        milestoneToast = item.text,
                        achievementHapticToken = if (item.successHaptic) it.achievementHapticToken + 1 else it.achievementHapticToken,
                    )
                }
                kotlinx.coroutines.delay(TOAST_DWELL_MS)
                _state.update { it.copy(milestoneToast = null) }
                kotlinx.coroutines.delay(TOAST_GAP_MS)
            }
        }
    }

    // MARK: - 照顾（喂食/清洁/玩耍）

    enum class CareKind { FEED, CLEAN, PLAY }

    fun care(kind: CareKind) {
        val pet = _state.value.pet ?: return
        if (cooldownActive(kind)) return
        viewModelScope.launch {
            // D1d：锁内重读最新宠物→护理→进化→写回，与回前台批量维护/其它护理串行（防整行覆盖）。
            var learnedTrick: String? = null
            var unlocked: List<PetMilestones.Milestone>? = null
            val evolve = petWriteLock.withPetLock(pet.uuid) {
                val fresh = petRepository.getByUuid(pet.uuid) ?: return@withPetLock null
                var p = when (kind) {
                    CareKind.FEED -> PetCareService.feed(fresh, settings)
                    CareKind.CLEAN -> PetCareService.clean(fresh, settings)
                    CareKind.PLAY -> PetCareService.play(fresh, settings)
                }
                if (kind == CareKind.PLAY) {
                    val tr = PetCareService.learnTrickIfUnlocked(p)
                    p = tr.pet
                    learnedTrick = tr.learnedName // pet-logic-3：捕获学会的技能名（原 `.pet` 丢弃了 learnedName）
                }
                val ev = PetGrowthService.checkAndEvolve(p)
                // P1-34 成就 diff：old=持久化基线（非 fresh 现算——treat/散步纪念品/聊天加成/小组件/后台进化/
                // 天数流逝的解锁迟到到下一次 care() 弹出、绝不丢）；new=ev.pet 现算；基线并入同一次 upsert
                // （锁内原子，防 D1d 整行覆盖回归）。null 基线→静默 seed 不弹（newlyUnlocked 返回 null）。
                val newSet = achievedSet(ev.pet, System.currentTimeMillis())
                unlocked = PetMilestones.newlyUnlocked(fresh.metadata.lastComputedAchievedIds?.toSet(), newSet)
                val md = ev.pet.metadata.copy(lastComputedAchievedIds = newSet.sorted())
                val finalPet = ev.pet.copy(petMetadataJson = PetJson.encodeMetadata(md))
                petRepository.upsert(finalPet)
                ev.copy(pet = finalPet)
            } ?: return@launch
            // P1-34 成就批 toast（拍板 A：app 内 toast+success 触觉每批一次·无金币无推送；iOS 成就解锁零反馈=安卓超越）。
            unlocked?.let { u ->
                val text = if (u.size == 1) {
                    appContext.getString(R.string.pet_achievement_unlock_single, u.first().name)
                } else {
                    appContext.getString(R.string.pet_achievement_unlock_multi, u.size)
                }
                toastQueue.trySend(PetToastItem(text, successHaptic = true))
            }
            val p = evolve.pet
            // P9.1c 进化金币奖励：去重入账到用户钱包（锁外发奖，钱已入账本）。
            // pet-logic-2：捕获发奖金额供 toast 显示（原丢弃返回值；**仅捕获、不改发奖逻辑＝不动钱**）。
            val coinReward = if (evolve.didEvolve) {
                milestoneRewardService.rewardPetEvolution(p.uuid, p.name, evolve.newStage)
            } else {
                0
            }

            val token = ++careToken
            val anim = when (kind) {
                CareKind.FEED -> PetSpriteManager.AnimationState.EAT
                CareKind.CLEAN -> PetSpriteManager.AnimationState.CLEAN
                CareKind.PLAY -> PetSpriteManager.AnimationState.HAPPY
            }
            val effect = when (kind) {
                CareKind.FEED -> PetParticleEffect.FEED
                CareKind.CLEAN -> PetParticleEffect.CLEAN
                CareKind.PLAY -> PetParticleEffect.PLAY
            }
            _state.update {
                it.copy(
                    careAnimationState = anim,
                    feedCooldown = if (kind == CareKind.FEED) true else it.feedCooldown,
                    cleanCooldown = if (kind == CareKind.CLEAN) true else it.cleanCooldown,
                    playCooldown = if (kind == CareKind.PLAY) true else it.playCooldown,
                    activeParticleEffect = effect,
                    effectStartMillis = System.currentTimeMillis(),
                )
            }
            showReaction(p.personalityType, careReaction(kind))

            // 进化/学技能后延迟播庆祝粒子（1:1 iOS：进化 OR 仅学会技能都进庆祝；延迟/时长区分；进化额外弹里程碑金币 toast + 触觉）。
            if (evolve.didEvolve || learnedTrick != null) {
                launch {
                    val celebrateDelay = if (evolve.didEvolve) 2200L else CARE_ANIMATION_MS // 进化跟在护理动画后，技能用护理动画时长
                    val celebrateDuration = if (evolve.didEvolve) 3000L else 2000L
                    kotlinx.coroutines.delay(celebrateDelay)
                    if (careToken != token) return@launch
                    // pet-logic-3：学会技能(未进化)也播 EVOLVE 庆祝粒子 + 触觉（原仅 didEvolve 才播）。
                    _state.update {
                        it.copy(
                            activeParticleEffect = PetParticleEffect.EVOLVE,
                            effectStartMillis = System.currentTimeMillis(),
                            celebrationHapticToken = it.celebrationHapticToken + 1,
                        )
                    }
                    // pet-logic-2：进化粒子约 0.5s 后弹「成长里程碑 +N 金币」，2.5s 停留（1:1 iOS milestoneToast）。
                    // P1-34 迁入队列：展示/清除交 drain 串行管理（同帧成就 toast 不撞槽）；两个 delay 保留=本协程
                    // 后续粒子清除时点零变；careToken 对 toast 的守卫删除（queue 拥有生命周期，修滞留边角 bug）。
                    if (evolve.didEvolve && coinReward > 0) {
                        kotlinx.coroutines.delay(500)
                        toastQueue.trySend(
                            PetToastItem(appContext.getString(R.string.pet_evolution_milestone_toast, coinReward), successHaptic = false),
                        )
                        kotlinx.coroutines.delay(2500)
                    }
                    kotlinx.coroutines.delay(celebrateDuration)
                    if (careToken == token) _state.update { it.copy(activeParticleEffect = null, effectStartMillis = null) }
                }
            } else {
                launch {
                    kotlinx.coroutines.delay(CARE_ANIMATION_MS)
                    if (careToken == token) _state.update { it.copy(activeParticleEffect = null, effectStartMillis = null) }
                }
            }
            // 动画恢复（2s）
            launch {
                kotlinx.coroutines.delay(CARE_ANIMATION_MS)
                if (careToken == token) _state.update { it.copy(careAnimationState = null) }
            }
            // 冷却恢复（3s，按 kind 独立，不受新动作 token 影响）
            launch {
                kotlinx.coroutines.delay(CARE_COOLDOWN_MS)
                _state.update {
                    when (kind) {
                        CareKind.FEED -> it.copy(feedCooldown = false)
                        CareKind.CLEAN -> it.copy(cleanCooldown = false)
                        CareKind.PLAY -> it.copy(playCooldown = false)
                    }
                }
            }
        }
    }

    /**
     * pet-ui-1：点击逗宠——只播开心动画、无冷却/不持久/不进化（1:1 iOS onTapGesture：lastCareAction=.happy + 1.5s 复位）。
     * 复用 careToken，故与正式护理动画互斥（后触发者赢）；缩放弹跳 + 重触觉在 UI 侧。
     */
    fun teasePet() {
        if (_state.value.pet == null) return
        val token = ++careToken
        _state.update { it.copy(careAnimationState = PetSpriteManager.AnimationState.HAPPY) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            if (careToken == token) _state.update { it.copy(careAnimationState = null) }
        }
    }

    private fun cooldownActive(kind: CareKind): Boolean = when (kind) {
        CareKind.FEED -> _state.value.feedCooldown
        CareKind.CLEAN -> _state.value.cleanCooldown
        CareKind.PLAY -> _state.value.playCooldown
    }

    private fun careReaction(kind: CareKind): PetReactionTexts.ReactionAction = when (kind) {
        CareKind.FEED -> PetReactionTexts.ReactionAction.FEED
        CareKind.CLEAN -> PetReactionTexts.ReactionAction.CLEAN
        CareKind.PLAY -> PetReactionTexts.ReactionAction.PLAY
    }

    // MARK: - 治疗（生病）/ 寻找（离家出走）

    fun treat() {
        val pet = _state.value.pet ?: return
        if (_state.value.treatCooldown || pet.neglectPhase != PetNeglectPhase.SICK) return
        viewModelScope.launch {
            val p = petWriteLock.withPetLock(pet.uuid) {
                val fresh = petRepository.getByUuid(pet.uuid) ?: return@withPetLock null
                val treated = PetCareService.treat(fresh, settings)
                petRepository.upsert(treated)
                treated
            } ?: return@launch
            val token = ++careToken
            _state.update { it.copy(treatCooldown = true, careAnimationState = PetSpriteManager.AnimationState.CLEAN) }
            // P1-14：治愈→celebration(success)；未愈→medium（=iOS :546 if cured {triggerCelebration()} else {hapticFeedToken+=1}）。
            if (p.neglectPhase == PetNeglectPhase.NONE) {
                triggerCelebration()
            } else {
                _state.update { it.copy(careMediumHapticToken = it.careMediumHapticToken + 1) }
            }
            showReaction(p.personalityType, PetReactionTexts.ReactionAction.TREAT)
            launch { kotlinx.coroutines.delay(CARE_ANIMATION_MS); if (careToken == token) _state.update { it.copy(careAnimationState = null) } }
            launch { kotlinx.coroutines.delay(CARE_COOLDOWN_MS); _state.update { it.copy(treatCooldown = false) } }
        }
    }

    fun search() {
        val pet = _state.value.pet ?: return
        if (_state.value.searchCooldown || pet.neglectPhase != PetNeglectPhase.RAN_AWAY) return
        viewModelScope.launch {
            val result = petWriteLock.withPetLock(pet.uuid) {
                val fresh = petRepository.getByUuid(pet.uuid) ?: return@withPetLock null
                val r = PetCareService.searchForPet(fresh)
                petRepository.upsert(r.pet)
                r
            } ?: return@launch
            _state.update { it.copy(searchCooldown = true) }
            // P1-14：寻回→celebration(success)；未果→medium（=iOS :558 同模式）。
            if (result is PetCareService.SearchResult.Found) {
                triggerCelebration()
            } else {
                _state.update { it.copy(careMediumHapticToken = it.careMediumHapticToken + 1) }
            }
            showReaction(result.pet.personalityType, PetReactionTexts.ReactionAction.SEARCH)
            launch { kotlinx.coroutines.delay(CARE_COOLDOWN_MS); _state.update { it.copy(searchCooldown = false) } }
        }
    }

    // MARK: - 散步

    fun startWalk() {
        val pet = _state.value.pet ?: return
        viewModelScope.launch {
            petWriteLock.withPetLock(pet.uuid) {
                val fresh = petRepository.getByUuid(pet.uuid) ?: return@withPetLock
                val p = PetWalkService.startWalk(fresh) ?: return@withPetLock
                petRepository.upsert(p)
            }
        }
    }

    /** 散步完成结算（pet 加载 + 倒计时归零时调；幂等：结算后 walkStartTime=null → 不再触发）。 */
    fun checkWalkCompletion() {
        val pet = _state.value.pet ?: return
        if (settlingWalk) return
        if (PetWalkService.walkState(pet) !is PetWalkService.WalkState.Completed) return
        settlingWalk = true
        viewModelScope.launch {
            // D1d：锁内重读最新→结算（先 upsert 清 walkStartTime）→ 锁外发奖（D12 顺序不变：清后发，防双算）。
            val s = petWriteLock.withPetLock(pet.uuid) {
                val fresh = petRepository.getByUuid(pet.uuid) ?: return@withPetLock null
                val settle = PetWalkService.checkAndSettle(fresh, settings) ?: return@withPetLock null
                petRepository.upsert(settle.pet)
                settle
            }
            if (s != null) {
                // 散步拾金入账（带流水，1:1 iOS：category=petWalk，note「{名}散步拾到的金币」，relatedId=null）。
                currencyService.addCoinsToUser(
                    amount = s.coinsReward,
                    category = CurrencyTransactionCategory.PET_WALK,
                    note = "${pet.name}散步拾到的金币",
                )
                _state.update { it.copy(walkSettlement = s) }
                triggerCelebration()
            }
            settlingWalk = false
        }
    }

    fun dismissWalkSettlement() = _state.update { it.copy(walkSettlement = null) }

    // MARK: - 改名 / 趋势快照

    fun rename(newName: String) {
        val pet = _state.value.pet ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            petWriteLock.withPetLock(pet.uuid) {
                val fresh = petRepository.getByUuid(pet.uuid) ?: return@withPetLock
                petRepository.upsert(fresh.copy(name = trimmed))
            }
        }
    }

    /** 离开页面时记录当前状态快照（下次进入算趋势箭头）。1:1 iOS saveTrendSnapshot。 */
    fun saveTrendSnapshot() {
        val pet = _state.value.pet ?: return
        viewModelScope.launch {
            petWriteLock.withPetLock(pet.uuid) {
                val fresh = petRepository.getByUuid(pet.uuid) ?: return@withPetLock
                val m = fresh.metadata
                val changed = m.lastViewedHunger != fresh.hunger || m.lastViewedCleanliness != fresh.cleanliness ||
                    m.lastViewedHappiness != fresh.happiness || m.lastViewedHealth != fresh.health
                if (!changed) return@withPetLock
                val nm = m.copy(
                    lastViewedHunger = fresh.hunger,
                    lastViewedCleanliness = fresh.cleanliness,
                    lastViewedHappiness = fresh.happiness,
                    lastViewedHealth = fresh.health,
                )
                petRepository.upsert(fresh.copy(petMetadataJson = PetJson.encodeMetadata(nm)))
            }
        }
    }

    /** 当前 vs 上次查看 → 趋势箭头（饱食度取反，1:1 iOS statusTrends）。 */
    fun statusTrends(pet: CharacterPetEntity): PetStatusTrends {
        val m = pet.metadata
        fun trend(current: Int, last: Int?): StatusTrend = when {
            last == null -> StatusTrend.STABLE
            current > last -> StatusTrend.UP
            current < last -> StatusTrend.DOWN
            else -> StatusTrend.STABLE
        }
        return PetStatusTrends(
            hunger = trend(100 - pet.hunger, m.lastViewedHunger?.let { 100 - it }),
            cleanliness = trend(pet.cleanliness, m.lastViewedCleanliness),
            happiness = trend(pet.happiness, m.lastViewedHappiness),
            health = trend(pet.health, m.lastViewedHealth),
        )
    }

    // MARK: - 内部

    private fun showReaction(personality: com.situ.aichat.pet.PetPersonalityType, action: PetReactionTexts.ReactionAction) {
        showCustomReaction(PetReactionTexts.randomReaction(personality, action))
    }

    /**
     * 在宠物头顶弹一条字面文本反应气泡 1.5s（pet-ui-2，对齐 iOS showCustomReaction）：
     * 背包用消耗品/换装后回到详情页时显示「好好吃的X!」「戴上X，好看吗?」等，背包传回的是字面串（非 PetReactionTexts）。
     * 与 showReaction 共用 reactionToken/REACTION_MS（最新一条胜出）。
     */
    fun showCustomReaction(text: String) {
        val token = ++reactionToken
        _state.update { it.copy(reactionText = text) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(REACTION_MS)
            if (reactionToken == token) _state.update { it.copy(reactionText = null) }
        }
    }

    /** 成就快照（P1-34：与成就图鉴卡 PetDetailScreen 同公式；daysSinceAdoption 无 +1 口径）。 */
    private fun achievedSet(p: CharacterPetEntity, nowMillis: Long): Set<String> {
        val m = p.metadata
        return PetMilestones.achievedIDs(
            daysSinceAdoption = PetMilestones.daysSinceAdoption(p.adoptedDate, nowMillis),
            totalInteractions = p.totalInteractions,
            tricksCount = m.learnedTricks.size,
            souvenirCount = m.souvenirs.size,
            isSpecial = p.growthStage == PetGrowthStage.SPECIAL,
            playCount = m.playCount,
            growthPoints = p.growthPoints,
        )
    }

    private fun triggerCelebration() {
        val token = ++careToken
        // P1-14 修漏：补 celebrationHapticToken bump（=iOS Helpers:84 无条件 hapticEvolveToken += 1）——
        // 原先治愈/寻回/散步结算只有粒子无触觉；care() 进化路径自带 bump 不走此函数，无双发。
        _state.update {
            it.copy(
                activeParticleEffect = PetParticleEffect.EVOLVE,
                effectStartMillis = System.currentTimeMillis(),
                celebrationHapticToken = it.celebrationHapticToken + 1,
            )
        }
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            if (careToken == token) _state.update { it.copy(activeParticleEffect = null, effectStartMillis = null) }
        }
    }

    private fun refreshAdoptionStatus() {
        viewModelScope.launch {
            val character = characterRepository.get(characterUuid) ?: return@launch
            val messageCount = messageDao.countAllForCharacter(characterUuid)
            val result = PetAdoptionRules.evaluate(character.relationshipQuality, character.creationDate, messageCount)
            _state.update { it.copy(adoptionProgress = result.progress, canAdopt = result.canAdopt) }
        }
    }

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
        private const val CARE_COOLDOWN_MS = 3_000L
        private const val CARE_ANIMATION_MS = 2_000L
        private const val REACTION_MS = 1_500L
        /** toast 停留 2.5s（=iOS PetDetailView sleep 2_500_000_000）。 */
        private const val TOAST_DWELL_MS = 2_500L
        /** 连播槽间隙（安卓 queue 化新增：两条 toast 之间留空防视觉粘连）。 */
        private const val TOAST_GAP_MS = 250L
    }
}
