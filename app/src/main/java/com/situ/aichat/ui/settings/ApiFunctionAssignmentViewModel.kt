package com.situ.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApiFunctionAssignmentViewModel @Inject constructor(
    private val repo: ApiConfigRepository,
    private val router: ApiFunctionRouter,
) : ViewModel() {

    val configs: StateFlow<List<ApiConfigEntity>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeConfig: StateFlow<ApiConfigEntity?> =
        repo.observeActive().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val assignments: StateFlow<Map<ApiFunction, String>> =
        router.assignments.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Assign [uuid] to [function]; pass null to revert to the default (active) config. */
    fun setAssignment(function: ApiFunction, uuid: String?) {
        viewModelScope.launch { router.setAssignment(function, uuid) }
    }
}
