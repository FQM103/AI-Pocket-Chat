package com.situ.aichat.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.situ.aichat.data.model.ApiFunction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps "app function → API config uuid" — faithful port of iOS `APIFunctionRouter`.
 * Persisted in DataStore (one key per function); an unassigned function falls back to the active
 * default config (resolved in [ApiConfigRepository.resolveConfigValues]).
 */
@Singleton
class ApiFunctionRouter @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** Reactive map of explicit assignments (function → config uuid), for the assignment UI. */
    val assignments: Flow<Map<ApiFunction, String>> = dataStore.data.map { prefs ->
        ApiFunction.entries.mapNotNull { fn -> prefs[key(fn)]?.let { fn to it } }.toMap()
    }

    suspend fun assignedId(function: ApiFunction): String? = dataStore.data.first()[key(function)]

    /** Assign a config uuid to a function; pass null/blank to revert to "use default". */
    suspend fun setAssignment(function: ApiFunction, uuid: String?) {
        dataStore.edit { prefs ->
            if (uuid.isNullOrBlank()) prefs.remove(key(function)) else prefs[key(function)] = uuid
        }
    }

    /** Clear every function assignment pointing at a (deleted) config uuid. */
    suspend fun clearAssignmentsForConfig(uuid: String) {
        dataStore.edit { prefs ->
            ApiFunction.entries.forEach { fn -> if (prefs[key(fn)] == uuid) prefs.remove(key(fn)) }
        }
    }

    private fun key(function: ApiFunction) = stringPreferencesKey("$PREFIX${function.raw}")

    companion object {
        private const val PREFIX = "api_fn_assign_"

        /**
         * 该配置承接的功能显示名列表（settings-api-3，1:1 iOS APIFunctionRouter.assignedFunctionDisplayNames）：
         * 按 [ApiFunction.entries] 声明序遍历——显式分配到本 uuid 的取 displayName；若本配置是默认(isDefault)，
         * 未分配(stored==null)的功能也隐式承接。纯函数（传入已收集的 assignments map），便于单测从 iOS 值反推。
         */
        fun assignedFunctionDisplayNames(
            assignments: Map<ApiFunction, String>,
            configUuid: String,
            isDefault: Boolean,
        ): List<String> = ApiFunction.entries.mapNotNull { fn ->
            val stored = assignments[fn]
            when {
                stored == configUuid -> fn.displayName
                isDefault && stored == null -> fn.displayName
                else -> null
            }
        }
    }
}
