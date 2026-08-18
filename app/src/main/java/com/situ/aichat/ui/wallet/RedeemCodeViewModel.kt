package com.situ.aichat.ui.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.economy.redeem.RedeemCodeService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 输入兑换码（14.6c-2·💰涉钱写·1:1 iOS RedeemCodeSheet）。四相状态机：editing → redeeming → success/error。
 * 兑换逻辑全在 [RedeemCodeService]（解码/验签/过期/去重/入账原子事务）；本 VM 只管 UI 相位。
 */
@HiltViewModel
class RedeemCodeViewModel @Inject constructor(
    private val redeemService: RedeemCodeService,
) : ViewModel() {

    sealed interface Phase {
        data object Editing : Phase
        data object Redeeming : Phase
        data class Error(val error: RedeemCodeService.RedeemError) : Phase
        data class Success(val coinsAdded: Int, val newBalance: Int) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Editing)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** 用户重新输入 → 清错误回 editing（1:1 iOS onChange）。 */
    fun onInputChanged() {
        if (_phase.value is Phase.Error) _phase.value = Phase.Editing
    }

    fun redeem(rawCode: String) {
        val trimmed = rawCode.trim()
        if (trimmed.isEmpty()) return
        if (_phase.value is Phase.Redeeming || _phase.value is Phase.Success) return
        _phase.value = Phase.Redeeming
        viewModelScope.launch {
            // 💰 钱写进 NonCancellable：离开页面也不丢这笔到账/使用记录（与 14.6b 月薪写一致）。
            val outcome = withContext(NonCancellable) { redeemService.redeem(trimmed) }
            _phase.value = when (outcome) {
                is RedeemCodeService.Outcome.Success -> Phase.Success(outcome.coinsAdded, outcome.newBalance)
                is RedeemCodeService.Outcome.Error -> Phase.Error(outcome.error)
            }
        }
    }
}
