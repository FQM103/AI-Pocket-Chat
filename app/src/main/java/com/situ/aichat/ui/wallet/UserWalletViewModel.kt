package com.situ.aichat.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.economy.CurrencyService
import com.situ.aichat.economy.EconomyLastViewedStore
import com.situ.aichat.economy.WalletLedger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 我的钱包屏（14.6a·1:1 iOS `WalletView`·💰只读）：响应式余额 + 用户侧全部流水 + 本月收/支小计。
 * 仅读 [CurrencyService.observeUserCoinBalance]（未建钱包回退 100）与 DAO 用户流水 Flow，零钱写路径。
 */
@HiltViewModel
class UserWalletViewModel @Inject constructor(
    currencyService: CurrencyService,
    private val characterRepo: CharacterRepository,
    private val economyLastViewed: EconomyLastViewedStore,
) : ViewModel() {

    /** 进「我的钱包」清全部角色的「新变动」高亮（P1-40 拍板·浏览即清）。 */
    fun markAllWalletNewsViewed() {
        viewModelScope.launch {
            economyLastViewed.markAllViewed(characterRepo.getAll().map { it.uuid })
        }
    }

    data class UiState(
        val balance: Int = 100,
        val transactions: List<CurrencyTransactionEntity> = emptyList(),
        val monthlyEarn: Int = 0,
        val monthlySpend: Int = 0,
        /** stateIn 占位初值=false；combine 真实发射后 true（批2 复核修 LOW#2：占位 100→真值不滚动）。 */
        val loaded: Boolean = false,
    )

    val state: StateFlow<UiState> = combine(
        currencyService.observeUserCoinBalance(),
        currencyService.observeUserTransactions(),
    ) { balance, txns ->
        val stats = WalletLedger.monthlyStats(txns, WalletLedger.monthStartMillis(System.currentTimeMillis()))
        UiState(balance = balance, transactions = txns, monthlyEarn = stats.earn, monthlySpend = stats.spend, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())
}
