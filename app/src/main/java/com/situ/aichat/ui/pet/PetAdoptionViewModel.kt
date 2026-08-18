package com.situ.aichat.ui.pet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.pet.PetFactory
import com.situ.aichat.pet.PetSpecies
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 宠物领养 VM（1:1 iOS `PetAdoptionView.createPet`）：建宠（随机性格 + 3% 隐藏款）→ upsert。双击保护：
 * 角色已有宠物则直接走 [onDone]（对齐 iOS `character.pet != nil → dismiss`）。
 */
@HiltViewModel
class PetAdoptionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val petRepository: PetRepository,
) : ViewModel() {

    val characterUuid: String = savedStateHandle.get<String>(ARG_CHARACTER_UUID) ?: ""

    fun adopt(name: String, species: PetSpecies, onDone: () -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            if (petRepository.getForCharacter(characterUuid) == null) {
                petRepository.upsert(PetFactory.createAdoptedPet(trimmed, species, characterUuid))
            }
            onDone()
        }
    }

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
    }
}
