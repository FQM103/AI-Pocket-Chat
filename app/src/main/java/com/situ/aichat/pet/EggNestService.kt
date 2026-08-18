package com.situ.aichat.pet

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.PetDao
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.repository.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 家的蛋巢之约读写 + 巢态响应式单源（W12.5 图纸 §2/§3·决策 42）。
 *
 * [observeState] combine：之约双键流 × 之约角色的 petDao 流 × 角色行 × eligibility。eligibility 输入
 * （RelationshipQuality/creationDate/messageCount）与 [com.situ.aichat.ui.pet.PetDetailViewModel] 调
 * [PetAdoptionRules.evaluate] 的既有装配**同源**（禁止重算口径）：`character.relationshipQuality` +
 * `character.creationDate` + `messageDao.countAllForCharacter(uuid)`。自愈清键在 derive 判定处触发（幂等·§3）：
 * 角色被删 / 无宠→有宠（已出壳或绕道领养）均清键回空巢，永不出「幽灵蛋」。**零 LLM、零新表**。
 */
@Singleton
class EggNestService @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val characterDao: CharacterDao,
    private val petDao: PetDao,
    private val messageDao: MessageDao,
) {

    /** 巢态响应式流（图纸 §3 派生矩阵·自愈清键·distinct 去抖）。[now] 便测（默认系统时钟）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeState(now: () -> Long = { System.currentTimeMillis() }): Flow<EggNestState> =
        settingsRepo.eggNestPactFlow.flatMapLatest { pact ->
            if (pact == null) {
                flowOf(EggNestState.Empty)
            } else {
                combine(
                    characterDao.observeByUuid(pact.characterUuid),
                    petDao.observeForCharacter(pact.characterUuid),
                ) { character, pet ->
                    // eligibility 装配与 PetDetailViewModel.refreshAdoptionStatus 逐字同源（禁止重算口径）。
                    val canAdopt = character?.let {
                        val messageCount = messageDao.countAllForCharacter(pact.characterUuid)
                        PetAdoptionRules.evaluate(it.relationshipQuality, it.creationDate, messageCount, now()).canAdopt
                    } ?: false
                    val d = deriveEggNest(
                        pactUuid = pact.characterUuid,
                        characterExists = character != null,
                        characterName = character?.name ?: "",
                        hasPet = pet != null,
                        canAdopt = canAdopt,
                    )
                    if (d.clearPact) clearPact() // 自愈：单次 DataStore edit·幂等（清后之约流→null→flowOf(Empty)，终态一致）。
                    d.state
                }
            }
        }.distinctUntilChanged()

    /** 定约（原子·幂等同值·E4）。 */
    suspend fun setPact(characterUuid: String, nowMs: Long) = settingsRepo.setEggNestPact(characterUuid, nowMs)

    /** 清键（自愈 / 兑现·原子·幂等）。 */
    suspend fun clearPact() = settingsRepo.clearEggNestPact()
}
