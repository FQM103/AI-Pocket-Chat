package com.situ.aichat.ui.world.eggnest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.PetDao
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.pet.EggNestCandidate
import com.situ.aichat.pet.EggNestService
import com.situ.aichat.pet.EggNestState
import com.situ.aichat.pet.PetAdoptionRules
import com.situ.aichat.pet.eggNestPhrase
import com.situ.aichat.pet.sortEggNestCandidates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 家的蛋巢宿主 VM（W12.5 图纸 §2/§3·决策 42）：巢态流（[EggNestService.observeState]）+ 孵蛋之约候选列表装配
 * （全角色·**不要求已加入世界**·决策 42②）+ 定约动作。候选朦胧短语 / eligibility 与 [PetAdoptionRules.evaluate]
 * 同源（禁止重算口径）；排序 / 短语阈值锁死交纯函数（[sortEggNestCandidates]/[eggNestPhrase]·T1-2）。
 */
@HiltViewModel
class EggNestViewModel @Inject constructor(
    private val eggNestService: EggNestService,
    characterDao: CharacterDao,
    petDao: PetDao,
    private val messageDao: MessageDao,
) : ViewModel() {

    /** 巢态（三态 + 空·响应式·随之约键/宠物/eligibility 自愈）。 */
    val state: StateFlow<EggNestState> =
        eggNestService.observeState()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EggNestState.Empty)

    /**
     * 之约候选（全角色）：每行 =（头像、名、有宠 → 禁选 +「已和 TA 养着 {宠名}」；无宠 → 朦胧短语 + overallPercent
     * 细进度条）。eligibility 输入与 PetDetail 同源（rq/creationDate/messageCount）；排序锁死交 [sortEggNestCandidates]。
     */
    val candidates: StateFlow<List<EggNestCandidate>> = combine(
        characterDao.observeAll(),
        petDao.observeAll(),
    ) { characters, pets ->
        val petByCharacter = pets.associateBy { it.characterUuid }
        val rows = characters.map { c ->
            val pet = petByCharacter[c.uuid]
            if (pet != null) {
                EggNestCandidate(c.uuid, c.name, c.avatarPath, petName = pet.name, phrase = null, overallPercent = 0f)
            } else {
                // eligibility 装配逐字同源 PetDetailViewModel.refreshAdoptionStatus（禁止重算口径）。
                val messageCount = messageDao.countAllForCharacter(c.uuid)
                val elig = PetAdoptionRules.evaluate(c.relationshipQuality, c.creationDate, messageCount)
                EggNestCandidate(
                    c.uuid, c.name, c.avatarPath,
                    petName = null,
                    phrase = eggNestPhrase(elig.canAdopt, elig.progress.overallPercent),
                    overallPercent = elig.progress.overallPercent,
                )
            }
        }
        sortEggNestCandidates(rows)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 定下之约（原子·幂等同值·E4 双击）。 */
    fun setPact(characterUuid: String) {
        viewModelScope.launch { eggNestService.setPact(characterUuid, System.currentTimeMillis()) }
    }
}
