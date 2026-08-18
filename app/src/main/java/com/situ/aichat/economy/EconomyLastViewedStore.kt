package com.situ.aichat.economy

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 钱包卡「新变动」高亮的 per-character 上次浏览时刻（P1-40·拍板 per-character 粒度·DataStore 动态键，
 * 仿 ApiFunctionRouter 套路）。高亮判定 = [hasEconomyNews]（最新流水时刻 > 上次浏览）。
 * 清除时机=浏览即清：进该角色资料页清该角色；进「我的钱包」清全部。设备本地状态，不进备份（缺值=0 → 首访
 * 必亮一次，随浏览即清自然消化）。
 */
@Singleton
class EconomyLastViewedStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /** 该角色上次浏览钱包卡的时刻（缺省 0 = 从未看过）。 */
    suspend fun lastViewed(characterUuid: String): Long =
        dataStore.data.first()[key(characterUuid)] ?: 0L

    /** 浏览即清：记该角色为「已看到 [now] 为止」。 */
    suspend fun markViewed(characterUuid: String, now: Long = System.currentTimeMillis()) {
        dataStore.edit { it[key(characterUuid)] = now }
    }

    /** 进「我的钱包」清全部（对全部已知角色记 now）。 */
    suspend fun markAllViewed(characterUuids: List<String>, now: Long = System.currentTimeMillis()) {
        if (characterUuids.isEmpty()) return
        dataStore.edit { p -> characterUuids.forEach { p[key(it)] = now } }
    }

    private fun key(characterUuid: String) = longPreferencesKey("$PREFIX$characterUuid")

    private companion object {
        const val PREFIX = "economy_last_viewed_"
    }
}
