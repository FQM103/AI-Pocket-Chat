package com.situ.aichat.data.repository

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 一次性遗留迁移：「沉浸模式」功能已整体移除（2026-07-10 用户拍板）。
 *
 * 旧沉浸模式开启时会关闭一批开关（日历/日程/忙碌/朋友圈三自动/日记两自动/宠物日记/通知）并把原值备份成
 * 快照 JSON。功能移除后若不还原，曾开启沉浸的设备会永久停在「全部静默」态。本迁移在 DataStore 首读前跑一次：
 *  - 沉浸 flag 为 true 且快照可解析 → 按快照整段还原上述开关；
 *  - 快照缺失/损坏 → 只清理遗留键、不动现值（与旧「快照丢失」分支同口径）；
 *  - 从未开启过（默认 false）→ 只清理可能残留的键，零行为变化。
 * 迁移后两把遗留键被移除，`shouldMigrate` 不再触发。
 */
object LegacyImmersiveMigration : DataMigration<Preferences> {
    private val KEY_LEGACY_ENABLED = booleanPreferencesKey("immersive_mode_enabled")
    private val KEY_LEGACY_SNAPSHOT = stringPreferencesKey("immersive_mode_snapshot_json")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData.contains(KEY_LEGACY_ENABLED) || currentData.contains(KEY_LEGACY_SNAPSHOT)

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        if (currentData[KEY_LEGACY_ENABLED] == true) {
            currentData[KEY_LEGACY_SNAPSHOT]?.let { raw -> restoreSnapshot(raw, mutable) }
        }
        mutable.remove(KEY_LEGACY_ENABLED)
        mutable.remove(KEY_LEGACY_SNAPSHOT)
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() = Unit

    /** 按旧 ImmersiveModeSnapshot 的字段名从 JSON 还原各开关；单字段缺失/类型错则跳过该字段（不整体失败）。 */
    private fun restoreSnapshot(raw: String, prefs: MutablePreferences) {
        val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        fun bool(name: String): Boolean? = (obj[name] as? JsonPrimitive)?.booleanOrNull
        fun int(name: String): Int? = (obj[name] as? JsonPrimitive)?.intOrNull
        bool("calendarIntegrationEnabled")?.let { prefs[SettingsRepository.KEY_CALENDAR_INTEGRATION] = it }
        bool("scheduleSystemEnabled")?.let { prefs[SettingsRepository.KEY_SCHEDULE_SYSTEM] = it }
        bool("busyModeEnabled")?.let { prefs[SettingsRepository.KEY_BUSY_MODE_ENABLED] = it }
        int("momentAutoPostFrequency")?.let { prefs[SettingsRepository.KEY_MOMENT_AUTO_POST_FREQ] = it }
        int("momentAutoCommentFrequency")?.let { prefs[SettingsRepository.KEY_MOMENT_AUTO_COMMENT_FREQ] = it }
        bool("momentAutoLikeEnabled")?.let { prefs[SettingsRepository.KEY_MOMENT_AUTO_LIKE] = it }
        bool("diaryAutoGenerateEnabled")?.let { prefs[SettingsRepository.KEY_DIARY_AUTO_GENERATE] = it }
        bool("diaryCharacterInteractionEnabled")?.let { prefs[SettingsRepository.KEY_DIARY_CHAR_INTERACTION] = it }
        bool("petDiaryAutoGenerateEnabled")?.let { prefs[SettingsRepository.KEY_PET_DIARY_AUTO_GENERATE] = it }
        bool("notificationsEnabled")?.let { prefs[SettingsRepository.KEY_NOTIFICATIONS_ENABLED] = it }
    }
}
