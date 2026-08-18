package com.situ.aichat.ui.moments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.MomentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 角色 / 用户动态页（M06 7.2.8，对齐 iOS `CharacterMomentsView` / `UserMomentsView`）共用 VM。路由参数
 * `characterUuid` 非空 = 角色模式（该角色发的帖），空 = 用户模式（我发的帖）；两者近乎同构，合一 VM。
 * 卡片点赞按钮经 [toggleLike] 写库（与主信息流一致）。
 */
@HiltViewModel
class MomentAuthorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val momentRepo: MomentRepository,
    characterRepo: CharacterRepository,
    userProfileDao: UserProfileDao,
) : ViewModel() {

    val characterUuid: String? = savedStateHandle.get<String>(ARG_CHARACTER_UUID)?.takeIf { it.isNotEmpty() }
    val isUserMode: Boolean = characterUuid == null

    val posts: StateFlow<List<MomentPostWithRelations>> =
        (if (characterUuid != null) momentRepo.observeCharacterFeed(characterUuid) else momentRepo.observeUserFeed())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val characters: StateFlow<Map<String, CharacterEntity>> =
        characterRepo.observeAll()
            .map { list -> list.associateBy { it.uuid } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val userProfile: StateFlow<UserProfileEntity?> =
        userProfileDao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleLike(postUuid: String, hasUserLike: Boolean) {
        viewModelScope.launch {
            if (hasUserLike) momentRepo.removeUserLike(postUuid) else momentRepo.addLike(postUuid, MomentAuthorType.USER, characterUuid = null)
        }
    }

    companion object {
        const val ARG_CHARACTER_UUID = "characterUuid"
    }
}
