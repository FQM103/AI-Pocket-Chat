package com.situ.aichat.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.UserProfileEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Editable user-profile fields (iOS `UserProfileEditView`). City (P5, 高德) is deferred. */
data class UserProfileEditState(
    val nickname: String = "",
    val bio: String = "",
    val birthdayMillis: Long? = null,
    val avatarPath: String? = null,
    /** 「希望 TA 怎么待你」相处偏好（四小件·2026-07-16）：全局一份不分角色，空=persona 段不注入该行。 */
    val companionPreference: String = "",
)

/**
 * Backs the user-profile edit form. The profile is a singleton row (id = 1). City columns are not
 * exposed by the form yet and are preserved across save.
 */
@HiltViewModel
class UserProfileEditViewModel @Inject constructor(
    private val dao: UserProfileDao,
) : ViewModel() {

    private val _state = MutableStateFlow(UserProfileEditState())
    val state: StateFlow<UserProfileEditState> = _state.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** Loaded row — kept so the (form-untouched) city columns survive a save. */
    private var loaded: UserProfileEntity? = null

    init {
        viewModelScope.launch {
            dao.get()?.let { p ->
                loaded = p
                _state.value = UserProfileEditState(
                    nickname = p.nickname,
                    bio = p.bio,
                    birthdayMillis = p.birthday,
                    avatarPath = p.avatarPath,
                    companionPreference = p.companionPreference,
                )
            }
        }
    }

    fun update(transform: (UserProfileEditState) -> UserProfileEditState) {
        _state.value = transform(_state.value)
    }

    fun save(onSaved: () -> Unit) {
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            val s = _state.value
            val base = loaded ?: UserProfileEntity()
            dao.upsert(
                base.copy(
                    id = 1,
                    nickname = s.nickname.trim(),
                    bio = s.bio,
                    avatarPath = s.avatarPath,
                    birthday = s.birthdayMillis,
                    companionPreference = s.companionPreference,
                ),
            )
            _saving.value = false
            onSaved()
        }
    }
}
