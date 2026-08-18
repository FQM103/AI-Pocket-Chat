package com.situ.aichat.ui.pet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.pet.AdoptionProgress
import com.situ.aichat.pet.PetAdoptionRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 宠物列表卡片状态（1:1 iOS PetListView PetStatus）。 */
sealed interface PetCardItem {
    val characterUuid: String
    val characterName: String
    data class Adopted(override val characterUuid: String, override val characterName: String, val pet: CharacterPetEntity) : PetCardItem
    data class CanAdopt(override val characterUuid: String, override val characterName: String) : PetCardItem
    data class Locked(override val characterUuid: String, override val characterName: String, val progress: AdoptionProgress) : PetCardItem
}

/**
 * 宠物列表枢纽 VM（1:1 iOS `PetListView`）：所有角色的宠物状态概览。已领养 → 迷你动画；满足条件 → 去领养；
 * 未满足 → 进度环。响应式（领养后自动刷新）；对无宠角色按需查消息总数算领养资格。
 */
@HiltViewModel
class PetListViewModel @Inject constructor(
    characterRepo: CharacterRepository,
    petRepository: PetRepository,
    private val messageDao: MessageDao,
) : ViewModel() {

    val cards: StateFlow<List<PetCardItem>> =
        combine(characterRepo.observeAll(), petRepository.observeAll()) { chars, pets ->
            val petByChar = pets.associateBy { it.characterUuid }
            chars.map { c ->
                val pet = petByChar[c.uuid]
                if (pet != null) {
                    PetCardItem.Adopted(c.uuid, c.name, pet)
                } else {
                    val elig = PetAdoptionRules.evaluate(c.relationshipQuality, c.creationDate, messageDao.countAllForCharacter(c.uuid))
                    if (elig.canAdopt) PetCardItem.CanAdopt(c.uuid, c.name) else PetCardItem.Locked(c.uuid, c.name, elig.progress)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
