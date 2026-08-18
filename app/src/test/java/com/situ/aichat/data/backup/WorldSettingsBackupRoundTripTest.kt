package com.situ.aichat.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
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
 * 世界系统设置 7 键备份往返 T2-7（W14 图纸 §3.3 E7·真 [SettingsRepository] + 两枚临时文件 DataStore）：
 * 源库 7 键全设**非默认值** → `appSettings.first()` 快照 → 目标库新 DataStore `applyBackupSettings(快照)` →
 * 断言 7 键逐一等于非默认值（applyBackupSettings 是读映射的精确逆·换机不丢世界设置）。断言从图纸 §0 设置 7 键独立反推。
 */
class WorldSettingsBackupRoundTripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var srcStore: DataStore<Preferences>
    private lateinit var dstStore: DataStore<Preferences>
    private lateinit var srcRepo: SettingsRepository
    private lateinit var dstRepo: SettingsRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        srcStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "src.preferences_pb") })
        dstStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tmp.root, "dst.preferences_pb") })
        srcRepo = SettingsRepository(srcStore)
        dstRepo = SettingsRepository(dstStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** world 五键 + egg_nest 双键全设非默认（lite/false/true/all/true + uuid/时间戳）→ 往返后逐键原值。 */
    @Test
    fun 世界设置7键非默认值换机往返_E7() = runBlocking {
        val pactUuid = "char-egg-uuid-42"
        val pactAt = 1_720_000_000_000L
        srcRepo.setWorldVividnessTier(AppSettings.WORLD_VIVIDNESS_LITE)   // 默认 standard
        srcRepo.setWorldRelationshipsEnabled(false)                       // 默认 true
        srcRepo.setWorldRomanceEnabled(true)                             // 默认 false
        srcRepo.setWorldNotificationTier(AppSettings.WORLD_NOTIFICATION_ALL) // 默认 gentle
        srcRepo.setWorldOnboardingDone(true)                            // 默认 false
        srcRepo.setEggNestPact(pactUuid, pactAt)                         // 默认 "" / 0L

        val snapshot = srcRepo.appSettings.first()
        dstRepo.applyBackupSettings(snapshot)
        val restored = dstRepo.appSettings.first()

        assertEquals("鲜活度档往返", AppSettings.WORLD_VIVIDNESS_LITE, restored.worldVividnessTier)
        assertFalse("关系系统开关往返", restored.worldRelationshipsEnabled)
        assertTrue("恋爱线开关往返", restored.worldRomanceEnabled)
        assertEquals("通知档往返", AppSettings.WORLD_NOTIFICATION_ALL, restored.worldNotificationTier)
        assertTrue("首启已走过标记往返", restored.worldOnboardingDone)
        assertEquals("蛋巢之约角色 uuid 往返", pactUuid, restored.eggNestPactCharacterUuid)
        assertEquals("蛋巢之约时间戳往返", pactAt, restored.eggNestPactAt)
    }
}
