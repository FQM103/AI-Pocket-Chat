package com.situ.aichat.ui.settings

import android.os.Looper
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldUserResidentDao
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.world.WorldBootstrap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [WorldSettingsViewModel] T2-4（W13 图纸 §7）：四 setter 传值（合法值恒 [AppSettings] 常量）+ setTimezone
 * 先 ensureCreated 后 updateUserTimezone + null 显示跟随设备。MockK 假 repo/dao/bootstrap·Robolectric 主循环。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldSettingsViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun buildVm(
        settings: AppSettings = AppSettings(),
        pinnedZone: String? = null,
    ): Triple<WorldSettingsViewModel, SettingsRepository, WorldDao> {
        val repo = mockk<SettingsRepository>(relaxed = true) {
            every { appSettings } returns flowOf(settings)
        }
        val worldDao = mockk<WorldDao>(relaxed = true) {
            coEvery { getState() } returns WorldStateEntity(seed = 1L, userTimezoneId = pinnedZone, createdAt = 0L)
        }
        val bootstrap = mockk<WorldBootstrap>(relaxed = true)
        val residentDao = mockk<WorldUserResidentDao>(relaxed = true) { every { observeCount() } returns flowOf(0) }
        val vm = WorldSettingsViewModel(repo, worldDao, bootstrap, residentDao)
        idle()
        return Triple(vm, repo, worldDao)
    }

    /** 订阅 state（WhileSubscribed 才开闸）驱动主循环后读值。 */
    private fun <T> withState(vm: WorldSettingsViewModel, block: () -> T): T {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        scope.launch { vm.state.collect {} }
        idle()
        return try {
            block()
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun `四setter传值_恒AppSettings常量`() {
        val (vm, repo, _) = buildVm()
        vm.setVividness(AppSettings.WORLD_VIVIDNESS_LITE)
        vm.setNotification(AppSettings.WORLD_NOTIFICATION_ALL)
        vm.setRelationships(false)
        vm.setRomance(true)
        idle()
        coVerify(exactly = 1) { repo.setWorldVividnessTier(AppSettings.WORLD_VIVIDNESS_LITE) }
        coVerify(exactly = 1) { repo.setWorldNotificationTier(AppSettings.WORLD_NOTIFICATION_ALL) }
        coVerify(exactly = 1) { repo.setWorldRelationshipsEnabled(false) }
        coVerify(exactly = 1) { repo.setWorldRomanceEnabled(true) }
    }

    @Test
    fun `setTimezone_先ensureCreated后updateUserTimezone`() {
        val repo = mockk<SettingsRepository>(relaxed = true) { every { appSettings } returns flowOf(AppSettings()) }
        val dao = mockk<WorldDao>(relaxed = true) { coEvery { getState() } returns null }
        val bootstrap = mockk<WorldBootstrap>(relaxed = true)
        val residentDao = mockk<WorldUserResidentDao>(relaxed = true) { every { observeCount() } returns flowOf(0) }
        val vm = WorldSettingsViewModel(repo, dao, bootstrap, residentDao)
        idle()
        vm.setTimezone("Asia/Tokyo")
        idle()
        coVerifyOrder {
            bootstrap.ensureCreated(any())
            dao.updateUserTimezone("Asia/Tokyo")
        }
    }

    @Test
    fun `null时区_state显示跟随设备`() {
        val (vm, _, _) = buildVm(pinnedZone = null)
        withState(vm) {
            assertNull(vm.state.value.timezoneId)
        }
    }

    @Test
    fun `已钉时区_state回显该zoneId`() {
        val (vm, _, _) = buildVm(pinnedZone = "Asia/Tokyo")
        withState(vm) {
            assertEquals("Asia/Tokyo", vm.state.value.timezoneId)
        }
    }

    @Test
    fun `四项从appSettings派生`() {
        val (vm, _, _) = buildVm(
            settings = AppSettings(
                worldVividnessTier = AppSettings.WORLD_VIVIDNESS_RICH,
                worldNotificationTier = AppSettings.WORLD_NOTIFICATION_SILENT,
                worldRelationshipsEnabled = false,
                worldRomanceEnabled = true,
            ),
        )
        withState(vm) {
            val s = vm.state.value
            assertEquals(AppSettings.WORLD_VIVIDNESS_RICH, s.vividnessTier)
            assertEquals(AppSettings.WORLD_NOTIFICATION_SILENT, s.notificationTier)
            assertEquals(false, s.relationshipsEnabled)
            assertEquals(true, s.romanceEnabled)
        }
    }
}
