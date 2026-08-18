package com.situ.aichat.ui.pet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.economy.CurrencyService
import com.situ.aichat.pet.PetItem
import com.situ.aichat.pet.PetItemCategory
import com.situ.aichat.pet.PetItemCatalog
import com.situ.aichat.pet.PetShopService
import com.situ.aichat.pet.species
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 宠物商店 VM（1:1 iOS `PetShopView` 状态）。响应式余额 + 宠物（按物种过滤商品 + 装扮已拥有判定）；选分类；
 * [purchase] 调 [PetShopService.purchase]（原子扣币 + 入库），返回 outcome 由 Screen 弹 snackbar。
 */
@HiltViewModel
class PetShopViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val petRepository: PetRepository,
    private val petShopService: PetShopService,
    currencyService: CurrencyService,
) : ViewModel() {

    val characterUuid: String = savedStateHandle.get<String>(ARG_CHARACTER_UUID) ?: ""

    val balance: StateFlow<Int> = currencyService.observeUserCoinBalance()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val pet: StateFlow<CharacterPetEntity?> = petRepository.observeForCharacter(characterUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selectedCategory = MutableStateFlow(PetItemCategory.FOOD)
    val selectedCategory: StateFlow<PetItemCategory> = _selectedCategory.asStateFlow()

    fun selectCategory(category: PetItemCategory) {
        _selectedCategory.value = category
    }

    /** 按物种 + 分类过滤、按价格升序（1:1 iOS displayedItems）。 */
    fun displayedItems(pet: CharacterPetEntity?, category: PetItemCategory): List<PetItem> {
        val species = pet?.species ?: return emptyList()
        return PetItemCatalog.items(species).filter { it.category == category }.sortedBy { it.price }
    }

    /** 扣币 + 入库（原子）。无宠物 → PetNotFound。 */
    suspend fun purchase(item: PetItem): PetShopService.PurchaseOutcome {
        val p = pet.value ?: return PetShopService.PurchaseOutcome.PetNotFound
        return petShopService.purchase(item, p.uuid)
    }

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
    }
}
