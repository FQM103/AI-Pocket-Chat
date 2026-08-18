package com.situ.aichat.ui.gift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.repository.CharacterRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 收礼盒 VM（9.2d d-5，1:1 iOS `GiftBoxView` 数据）。
 *
 * 两个响应式查询：[sentGifts]（用户送出 senderType=user）/ [receivedGifts]（用户收到 receiverType=character→user）。
 * [characterByUuid] 把角色列表映射成 uuid→角色 字典——iOS 警示这里必须缓存为 state（computed 每次 body eval 都订阅
 * 所有 AICharacter 属性变更，后台 LLM 写角色字段会触发重组 loop）；安卓用 `observeAll().map{}` 的 StateFlow，
 * 订阅天然隔离在 VM，UI 只 collect 字典快照，等价规避该 loop。
 */
@HiltViewModel
class GiftBoxViewModel @Inject constructor(
    giftDao: GiftDao,
    characterRepo: CharacterRepository,
) : ViewModel() {

    val sentGifts: StateFlow<List<GiftRecordEntity>> = giftDao.observeUserSentGifts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val receivedGifts: StateFlow<List<GiftRecordEntity>> = giftDao.observeUserReceivedGifts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val characterByUuid: StateFlow<Map<String, CharacterEntity>> = characterRepo.observeAll()
        .map { list -> list.associateBy { it.uuid } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
