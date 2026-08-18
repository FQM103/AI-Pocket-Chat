package com.situ.aichat.ui.pet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.repository.PetRepository
import com.situ.aichat.pet.PetInventoryService
import com.situ.aichat.pet.PetItem
import com.situ.aichat.pet.PetItemCategory
import com.situ.aichat.pet.PetItemCatalog
import com.situ.aichat.pet.metadata
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 宠物背包 VM（1:1 iOS `PetInventorySheet` 状态）。响应式宠物（库存）；选分类；使用消耗品 / 佩戴-摘下装扮
 * 调 [PetInventoryService]，返回 outcome 由 Screen 弹 snackbar + 管理 2s 冷却/处理中态。
 */
@HiltViewModel
class PetInventoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val petRepository: PetRepository,
    private val petInventoryService: PetInventoryService,
) : ViewModel() {

    val characterUuid: String = savedStateHandle.get<String>(ARG_CHARACTER_UUID) ?: ""

    val pet: StateFlow<CharacterPetEntity?> = petRepository.observeForCharacter(characterUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selectedCategory = MutableStateFlow(PetItemCategory.FOOD)
    val selectedCategory: StateFlow<PetItemCategory> = _selectedCategory.asStateFlow()

    fun selectCategory(category: PetItemCategory) {
        _selectedCategory.value = category
    }

    /** 当前分类下已拥有的物品（按价格升序，1:1 iOS displayedItems）。 */
    fun displayedItems(pet: CharacterPetEntity?, category: PetItemCategory): List<PetItem> {
        val owned = pet?.metadata?.petInventory?.owned ?: return emptyList()
        return owned.keys.mapNotNull { PetItemCatalog.find(it) }
            .filter { it.category == category }
            .sortedBy { it.price }
    }

    /** 零食总份数（各消耗品数量求和，1:1 iOS foodCount）。 */
    fun foodCount(pet: CharacterPetEntity?): Int {
        val inv = pet?.metadata?.petInventory ?: return 0
        return inv.owned.keys.mapNotNull { PetItemCatalog.find(it) }
            .filter { it.category == PetItemCategory.FOOD }
            .sumOf { inv.quantity(it.id) }
    }

    /** 装扮件数（每件数量恒 1，直接计件，1:1 iOS costumeCount）。 */
    fun costumeCount(pet: CharacterPetEntity?): Int {
        val owned = pet?.metadata?.petInventory?.owned ?: return 0
        return owned.keys.mapNotNull { PetItemCatalog.find(it) }.count { it.category == PetItemCategory.COSTUME }
    }

    /** 某物品当前数量（消耗品）。 */
    fun quantityOf(pet: CharacterPetEntity?, itemId: String): Int = pet?.metadata?.petInventory?.quantity(itemId) ?: 0

    /** 是否正佩戴该装扮。 */
    fun isEquipped(pet: CharacterPetEntity?, itemId: String): Boolean =
        pet?.metadata?.petInventory?.equippedItemId == itemId

    suspend fun useConsumable(item: PetItem): PetInventoryService.ConsumeOutcome {
        val p = pet.value ?: return PetInventoryService.ConsumeOutcome.PetNotFound
        return petInventoryService.useConsumable(item, p.uuid)
    }

    /** 佩戴/摘下切换（已佩戴 → 摘下；否则佩戴）。 */
    suspend fun toggleEquip(item: PetItem): PetInventoryService.EquipOutcome {
        val p = pet.value ?: return PetInventoryService.EquipOutcome.PetNotFound
        return if (p.metadata.petInventory.equippedItemId == item.id) {
            petInventoryService.unequip(p.uuid)
        } else {
            petInventoryService.equip(item, p.uuid)
        }
    }

    // pet-ui-2：背包操作成功后回传给详情页头顶气泡的字面反应文案（1:1 iOS PetInventorySheet）。
    /** 吃消耗品反应：5 选 1（对齐 iOS randomConsumeReaction，含 fallback「好吃!」）。 */
    fun consumeReaction(itemName: String): String = listOf(
        "好好吃的${itemName}!",
        "${itemName}真好吃~",
        "还想要更多的${itemName}!",
        "吃到${itemName} 开心!",
        "${itemName}太美味啦!",
    ).randomOrNull() ?: "好吃!"

    /** 换装反应：摘下/戴上（注意 iOS 戴上文案无「」括号、用「,好看吗?」）。 */
    fun equipReaction(itemName: String, wasEquipped: Boolean): String =
        if (wasEquipped) "把${itemName}摘下来啦" else "戴上${itemName},好看吗?"

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
    }
}
