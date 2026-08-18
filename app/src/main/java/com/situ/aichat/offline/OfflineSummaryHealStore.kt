package com.situ.aichat.offline

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 见面摘要**24h 低频自愈**的 app 全局状态（对齐 iOS `OfflineSummaryRetryCoordinator` 用的 UserDefaults
 * lastHealAt + triedIds）。安卓用 SharedPreferences（数据量极小：一个时间戳 + 一份逗号分隔已试 sessionId）。
 *
 * - [lastHealAt]：上次自愈时间，24h 冷却判断（避免一天多次扫表）。
 * - [triedIds]：本轮已尝试的 sessionId（轮询公平，避免某个永久失败 session 占位阻塞其他 fallback）。
 */
@Singleton
class OfflineSummaryHealStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy { context.getSharedPreferences("offline_summary_heal", Context.MODE_PRIVATE) }

    /** 上次自愈时间（毫秒）；从未自愈 → null。 */
    fun lastHealAt(): Long? = prefs.getLong(KEY_LAST_HEAL_AT, -1L).takeIf { it >= 0L }

    fun setLastHealAt(millis: Long) = prefs.edit { putLong(KEY_LAST_HEAL_AT, millis) }

    /** 本轮已尝试的 sessionId 列表。 */
    fun triedIds(): List<String> =
        prefs.getString(KEY_TRIED_IDS, "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()

    fun setTriedIds(ids: List<String>) = prefs.edit { putString(KEY_TRIED_IDS, ids.joinToString(",")) }

    companion object {
        private const val KEY_LAST_HEAL_AT = "last_heal_at"
        private const val KEY_TRIED_IDS = "tried_ids"
    }
}
