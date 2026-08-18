package com.situ.aichat.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沉浸模式移除的一次性遗留迁移 T1（纯 JVM·Preferences 内存对象直测）：
 * 曾开启沉浸的设备按快照整段还原被关闭的开关；快照损坏/缺失只清键不动现值；干净设备不触发。
 */
class LegacyImmersiveMigrationTest {

    private val legacyEnabled = booleanPreferencesKey("immersive_mode_enabled")
    private val legacySnapshot = stringPreferencesKey("immersive_mode_snapshot_json")

    /** 旧 ImmersiveModeSnapshot 编码产物的等价 JSON（字段名 = 旧 @Serializable 字段名）。 */
    private val snapshotJson = """
        {"version":1,"snapshotAt":123,"calendarIntegrationEnabled":true,"scheduleSystemEnabled":true,
         "busyModeEnabled":true,"momentAutoPostFrequency":2,"momentAutoCommentFrequency":3,
         "momentAutoLikeEnabled":true,"diaryAutoGenerateEnabled":true,"diaryCharacterInteractionEnabled":true,
         "petDiaryAutoGenerateEnabled":true,"notificationsEnabled":true}
    """.trimIndent()

    @Test
    fun `干净设备不触发迁移`() = runBlocking {
        assertFalse(LegacyImmersiveMigration.shouldMigrate(mutablePreferencesOf().toPreferences()))
    }

    @Test
    fun `沉浸开启且快照完好_整段还原并清除遗留键`() = runBlocking {
        val prefs = mutablePreferencesOf().apply {
            this[legacyEnabled] = true
            this[legacySnapshot] = snapshotJson
            // 沉浸期间被 enable() 压成关闭态的现值
            this[SettingsRepository.KEY_SCHEDULE_SYSTEM] = false
            this[SettingsRepository.KEY_NOTIFICATIONS_ENABLED] = false
            this[SettingsRepository.KEY_MOMENT_AUTO_POST_FREQ] = 0
        }.toPreferences()
        assertTrue(LegacyImmersiveMigration.shouldMigrate(prefs))

        val out = LegacyImmersiveMigration.migrate(prefs)

        assertEquals(true, out[SettingsRepository.KEY_SCHEDULE_SYSTEM])
        assertEquals(true, out[SettingsRepository.KEY_NOTIFICATIONS_ENABLED])
        assertEquals(true, out[SettingsRepository.KEY_BUSY_MODE_ENABLED])
        assertEquals(2, out[SettingsRepository.KEY_MOMENT_AUTO_POST_FREQ])
        assertEquals(3, out[SettingsRepository.KEY_MOMENT_AUTO_COMMENT_FREQ])
        assertEquals(true, out[SettingsRepository.KEY_DIARY_AUTO_GENERATE])
        assertEquals(true, out[SettingsRepository.KEY_PET_DIARY_AUTO_GENERATE])
        assertNull(out[legacyEnabled])
        assertNull(out[legacySnapshot])
    }

    @Test
    fun `快照损坏_只清遗留键不动现值`() = runBlocking {
        val prefs = mutablePreferencesOf().apply {
            this[legacyEnabled] = true
            this[legacySnapshot] = "{not-valid-json"
            this[SettingsRepository.KEY_SCHEDULE_SYSTEM] = false
        }.toPreferences()

        val out = LegacyImmersiveMigration.migrate(prefs)

        assertEquals(false, out[SettingsRepository.KEY_SCHEDULE_SYSTEM]) // 现值原样保留
        assertNull(out[legacyEnabled])
        assertNull(out[legacySnapshot])
    }

    @Test
    fun `沉浸未开启但残留旧键_只清理不还原`() = runBlocking {
        val prefs = mutablePreferencesOf().apply {
            this[legacyEnabled] = false
            this[legacySnapshot] = snapshotJson
            this[SettingsRepository.KEY_SCHEDULE_SYSTEM] = false // 用户自己关的，不许被快照翻回
        }.toPreferences()
        assertTrue(LegacyImmersiveMigration.shouldMigrate(prefs))

        val out = LegacyImmersiveMigration.migrate(prefs)

        assertEquals(false, out[SettingsRepository.KEY_SCHEDULE_SYSTEM])
        assertNull(out[legacyEnabled])
        assertNull(out[legacySnapshot])
    }

    @Test
    fun `快照单字段缺失_其余字段照常还原`() = runBlocking {
        val partial = """{"scheduleSystemEnabled":true,"momentAutoPostFrequency":1}"""
        val prefs = mutablePreferencesOf().apply {
            this[legacyEnabled] = true
            this[legacySnapshot] = partial
            this[SettingsRepository.KEY_NOTIFICATIONS_ENABLED] = false
        }.toPreferences()

        val out = LegacyImmersiveMigration.migrate(prefs)

        assertEquals(true, out[SettingsRepository.KEY_SCHEDULE_SYSTEM])
        assertEquals(1, out[SettingsRepository.KEY_MOMENT_AUTO_POST_FREQ])
        assertEquals(false, out[SettingsRepository.KEY_NOTIFICATIONS_ENABLED]) // 缺失字段不动现值
    }
}
