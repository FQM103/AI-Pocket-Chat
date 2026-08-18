package com.situ.aichat.ui.world.resident

import android.os.Looper
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.world.WorldBootstrap
import com.situ.aichat.world.cast.CreateResult
import com.situ.aichat.world.cast.ResidentDraft
import com.situ.aichat.world.cast.WorldResidentService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [ResidentCreateViewModel] T2（战役 B·图纸 §7·E12）：init 派生城市（真 [com.situ.aichat.world.atlas.WorldAtlas] 纯算）/
 * 空名拦截不调 create / 提交调 create 一次 / **双提交 create 只调一次（E12 门闩·门闩真正所在层）** /
 * 年龄只收数字 / 性格至多 3。MockK 假 service/bootstrap（真 DB 背景执行器无法被 Robolectric 主循环 idle 排空）·
 * 主循环 idle 驱动 viewModelScope。E12 归此层验（§11：§7 名义挂 T2-1 服务测，但门闩在 VM、服务层两次 create 天然两条 slug）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResidentCreateViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** 假 bootstrap（ensureCreated 立返 seed=1 的世界行·WorldAtlas.of(1) 纯算派生十大区）。 */
    private fun buildVm(service: WorldResidentService): ResidentCreateViewModel {
        val bootstrap = mockk<WorldBootstrap> {
            coEvery { ensureCreated(any()) } returns WorldStateEntity(seed = 1L, createdAt = 0L)
        }
        val vm = ResidentCreateViewModel(service, bootstrap)
        idle() // 驱动 init 的 ensureCreated + 城市派生
        return vm
    }

    @Test
    fun `init 派生城市列表_默认云野镇`() {
        val vm = buildVm(mockk(relaxed = true))
        val s = vm.state.value
        assertTrue("十大区派生", s.regions.isNotEmpty())
        assertEquals("默认出生城 = 云野镇", "city_yunye", s.cityId)
        assertEquals("云野镇", s.cityName)
        assertTrue("当前大区有城", s.citiesOfRegion.isNotEmpty())
    }

    @Test
    fun `空名 submit → InvalidName_不调 create`() {
        val service = mockk<WorldResidentService>(relaxed = true)
        val vm = buildVm(service)
        vm.submit(); idle()
        assertTrue("nameError 置位", vm.state.value.nameError)
        assertTrue(vm.state.value.result is CreateResult.InvalidName)
        coVerify(exactly = 0) { service.create(any(), any()) }
    }

    @Test
    fun `填名 submit → 调 create 一次_组正确 draft_Ok`() {
        val draftSlot = slot<ResidentDraft>()
        val service = mockk<WorldResidentService> {
            coEvery { create(capture(draftSlot), any()) } returns CreateResult.Ok("resident_x")
        }
        val vm = buildVm(service)
        vm.setName("  江晚棠 ")
        vm.setOccupation("旧书店店主")
        vm.toggleTrait("温吞")
        vm.setGenderPreset("male")
        vm.submit(); idle()
        coVerify(exactly = 1) { service.create(any(), any()) }
        assertTrue(vm.state.value.result is CreateResult.Ok)
        // draft 组装：性别 preset 透传、性格底色带入、城默认云野镇。
        assertEquals("male", draftSlot.captured.gender)
        assertEquals(listOf("温吞"), draftSlot.captured.traits)
        assertEquals("city_yunye", draftSlot.captured.cityId)
    }

    @Test
    fun `E12 双提交_create 只调一次_门闩`() {
        // 用 gate 让首个 create 挂起不返回 → submitting 恒 true 跨过第二次 submit（无论内联/排队分发，门闩都成立）。
        val gate = CompletableDeferred<CreateResult>()
        val service = mockk<WorldResidentService> {
            coEvery { create(any(), any()) } coAnswers { gate.await() }
        }
        val vm = buildVm(service)
        vm.setName("江晚棠")
        vm.submit() // submitting=true 同步置位·create 进 gate 挂起
        vm.submit() // submitting 仍 true → 直接返回
        idle()
        coVerify(exactly = 1) { service.create(any(), any()) }
        gate.complete(CreateResult.Ok("resident_x")) // 释放
        idle()
    }

    @Test
    fun `年龄只收数字并截三位_E5`() {
        val vm = buildVm(mockk(relaxed = true))
        vm.setAge("abc12xy345")
        assertEquals("仅数字·截 3 位", "123", vm.state.value.ageText)
    }

    @Test
    fun `性格底色至多 3 个_满后静默忽略`() {
        val vm = buildVm(mockk(relaxed = true))
        listOf("温吞", "毒舌", "热心", "孤僻").forEach { vm.toggleTrait(it) }
        assertEquals(3, vm.state.value.traits.size)
        assertEquals(listOf("温吞", "毒舌", "热心"), vm.state.value.traits)
    }
}
