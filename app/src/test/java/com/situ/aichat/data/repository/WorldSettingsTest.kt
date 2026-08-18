package com.situ.aichat.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.situ.aichat.data.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 世界系统设置 T2-7（W1 图纸 §7）：真 [SettingsRepository] + 临时文件 DataStore（[PreferenceDataStoreFactory.create]）。
 * 断言从图纸 §3 设置表独立反推：
 * - E10 从未写过 → 读出默认（standard / true / false / gentle）
 * - 四项 set → get 往返保真
 */
class WorldSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tmp.root, "world_settings.preferences_pb") },
        )
        repo = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** E10：设置项从未写过 → 读出默认 standard / true / false / gentle。 */
    @Test
    fun 世界设置默认值() = runBlocking {
        val s = repo.appSettings.first()
        assertEquals(AppSettings.WORLD_VIVIDNESS_STANDARD, s.worldVividnessTier)
        assertTrue("关系系统默认开", s.worldRelationshipsEnabled)
        assertFalse("恋爱线默认关", s.worldRomanceEnabled)
        assertEquals(AppSettings.WORLD_NOTIFICATION_GENTLE, s.worldNotificationTier)
    }

    /** 四项 set → get 往返保真。 */
    @Test
    fun 世界设置往返保真() = runBlocking {
        repo.setWorldVividnessTier(AppSettings.WORLD_VIVIDNESS_RICH)
        repo.setWorldRelationshipsEnabled(false)
        repo.setWorldRomanceEnabled(true)
        repo.setWorldNotificationTier(AppSettings.WORLD_NOTIFICATION_ALL)

        val s = repo.appSettings.first()
        assertEquals("rich", s.worldVividnessTier)
        assertFalse(s.worldRelationshipsEnabled)
        assertTrue(s.worldRomanceEnabled)
        assertEquals("all", s.worldNotificationTier)
    }
}
