package com.situ.aichat.ui.starfield

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆星空「上次访问时刻」per-character 存储（图纸 J4·纹路照
 * [com.situ.aichat.economy.EconomyLastViewedStore]）——本卷**唯一**新增持久化。
 *
 * nova（新星呼吸）与流星判定 = 星 timestamp > 上次访问时刻；**缺值 0 = 首次进入 → 全场无 nova**
 * （避免第一次进入满屏呼吸的廉价感）。退出星空页时（`DisposableEffect onDispose`）记 now。
 * 设备本地状态，不进备份；进程死亡未走 onDispose → 不更新 → 下次 nova 重现（宁多亮一次不漏亮·图纸 §3.3）。
 */
@Singleton
class StarfieldLastVisitStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    /** 该角色上次访问星空的时刻（缺省 0 = 从未进过）。 */
    suspend fun lastVisited(characterUuid: String): Long =
        dataStore.data.first()[key(characterUuid)] ?: 0L

    /** 记该角色为「已看到 [now] 为止」。 */
    suspend fun markVisited(characterUuid: String, now: Long = System.currentTimeMillis()) {
        dataStore.edit { it[key(characterUuid)] = now }
    }

    private fun key(characterUuid: String) = longPreferencesKey("$PREFIX$characterUuid")

    private companion object {
        const val PREFIX = "starfield_last_visit_"
    }
}
