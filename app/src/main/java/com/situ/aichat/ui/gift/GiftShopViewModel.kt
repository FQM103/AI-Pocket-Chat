package com.situ.aichat.ui.gift

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.GiftCategory
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.economy.CurrencyService
import com.situ.aichat.gift.GiftCatalog
import com.situ.aichat.gift.GiftItem
import com.situ.aichat.gift.GiftSendService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 礼物店 VM（9.2d d-2，1:1 iOS `GiftShopView` 状态）。
 *
 * - [balance] 响应式余额（决定卡片可负担配色）。
 * - 送礼对象：[lockedCharacter]（从聊天等带入、不可换）优先；否则用户在店内 [pickCharacter] 选 [pickedCharacter]。
 * - [spend] 调 [GiftSendService.spendAndCreateRecord]（第一步扣币建 record），成功后 UI 导航到反应页生成反应。
 */
@HiltViewModel
class GiftShopViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val giftSendService: GiftSendService,
    private val characterRepo: CharacterRepository,
    currencyService: CurrencyService,
) : ViewModel() {

    /** 预选角色 UUID（路由 giftShop/{characterUuid} 带入；giftShop 无参时为 null）。 */
    private val preselectedUuid: String? =
        savedStateHandle.get<String>(ARG_CHARACTER_UUID)?.takeIf { it.isNotBlank() }

    /** 是否锁定送礼对象（带入角色时不显示「选择/更换对象」）。 */
    val isLocked: Boolean = preselectedUuid != null

    val balance: StateFlow<Int> = currencyService.observeUserCoinBalance()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 100)

    /** 选对象 sheet 用的角色列表（creationDate DESC，同 iOS picker）。 */
    val characters: StateFlow<List<CharacterEntity>> = characterRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _lockedCharacter = MutableStateFlow<CharacterEntity?>(null)
    val lockedCharacter: StateFlow<CharacterEntity?> = _lockedCharacter.asStateFlow()

    private val _pickedCharacter = MutableStateFlow<CharacterEntity?>(null)
    val pickedCharacter: StateFlow<CharacterEntity?> = _pickedCharacter.asStateFlow()

    private val _selectedCategory = MutableStateFlow<GiftCategory?>(null)
    val selectedCategory: StateFlow<GiftCategory?> = _selectedCategory.asStateFlow()

    init {
        if (preselectedUuid != null) {
            viewModelScope.launch { _lockedCharacter.value = characterRepo.get(preselectedUuid) }
        }
    }

    fun pickCharacter(c: CharacterEntity) { _pickedCharacter.value = c }
    fun selectCategory(cat: GiftCategory?) { _selectedCategory.value = cat }

    /** 当前有效送礼对象（锁定优先）。 */
    fun effectiveCharacter(): CharacterEntity? = _lockedCharacter.value ?: _pickedCharacter.value

    /** 当前分类的礼物（无选则全部）。GiftCatalog 静态，按 category 现算无开销。 */
    fun itemsFor(category: GiftCategory?): List<GiftItem> =
        if (category != null) GiftCatalog.items(category) else GiftCatalog.allItems

    /** 第一步：扣币 + 建 record。成功后调用方导航到反应页（recordUuid）生成反应。 */
    suspend fun spend(item: GiftItem): GiftSendService.ShopSpendOutcome {
        val c = effectiveCharacter() ?: return GiftSendService.ShopSpendOutcome.SpendFailed
        return giftSendService.spendAndCreateRecord(item, c.uuid)
    }

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
    }
}
