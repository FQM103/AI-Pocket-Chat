package com.situ.aichat.ui.world.onboarding

import android.os.Looper
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.world.WorldBootstrap
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * [WorldOnboardingViewModel] T2-5（W13 图纸 §7）：confirmZone 钉设备时区 / skip 不写时区且 done / finish done /
 * 步进 1→2→3 / pickZone 后「就这个」不覆盖。MockK 假 repo/dao/bootstrap·Robolectric 主循环驱动写库协程。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldOnboardingViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun buildVm(): Triple<WorldOnboardingViewModel, SettingsRepository, WorldDao> {
        val repo = mockk<SettingsRepository>(relaxed = true) {
            every { appSettings } returns flowOf(AppSettings())
        }
        val worldDao = mockk<WorldDao>(relaxed = true)
        val bootstrap = mockk<WorldBootstrap>(relaxed = true)
        val vm = WorldOnboardingViewModel(repo, worldDao, bootstrap)
        return Triple(vm, repo, worldDao)
    }

    @Test
    fun `primaryAction step1_先ensureCreated后钉设备时区_进step2`() {
        val bootstrap = mockk<WorldBootstrap>(relaxed = true)
        val repo = mockk<SettingsRepository>(relaxed = true) { every { appSettings } returns flowOf(AppSettings()) }
        val dao = mockk<WorldDao>(relaxed = true)
        val v = WorldOnboardingViewModel(repo, dao, bootstrap)
        v.primaryAction()
        idle()
        coVerifyOrder {
            bootstrap.ensureCreated(any())
            dao.updateUserTimezone(ZoneId.systemDefault().id)
        }
        assertEquals(2, v.step.value)
    }

    @Test
    fun `步进 1到2到3`() {
        val (vm, _, _) = buildVm()
        assertEquals(1, vm.step.value)
        vm.primaryAction(); idle()
        assertEquals(2, vm.step.value)
        vm.primaryAction()
        assertEquals(3, vm.step.value)
    }

    @Test
    fun `skip_不写时区且done`() {
        val (vm, repo, worldDao) = buildVm()
        vm.skip()
        idle()
        coVerify(exactly = 0) { worldDao.updateUserTimezone(any()) }
        coVerify(exactly = 1) { repo.setWorldOnboardingDone(true) }
    }

    @Test
    fun `finish_done`() {
        val (vm, repo, _) = buildVm()
        vm.finish()
        idle()
        coVerify(exactly = 1) { repo.setWorldOnboardingDone(true) }
    }

    @Test
    fun `pickZone写选中值_随后就这个不再覆盖设备时区`() {
        val (vm, _, worldDao) = buildVm()
        vm.pickZone("Asia/Tokyo")
        idle()
        coVerify(exactly = 1) { worldDao.updateUserTimezone("Asia/Tokyo") }
        // 已显式选过 → step1 主钮不再写第二次（confirmZone no-op），仅推进步进。
        vm.primaryAction()
        idle()
        coVerify(exactly = 1) { worldDao.updateUserTimezone(any()) }
        assertEquals(2, vm.step.value)
    }
}
