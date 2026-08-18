package com.situ.aichat.ui.character

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.worldbook.WorldBookRepository
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.member.WorldMembershipService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [CharacterWorldViewModel] T2-2（W13 图纸 §7）：四态派生（普通关/开/世界书锁/原住民锁）+ join/leave/move
 * 走确认后委托 [WorldMembershipService]。MockK 假 dao/service·Robolectric 主循环驱动 viewModelScope。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacterWorldViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val uuid = "c1"
    private val seed = 1L

    private fun buildVm(
        joined: Boolean = false,
        homeCityId: String = WorldIds.HOME_CITY_ID,
        native: Boolean = false,
        wbBound: Boolean = false,
        worldExists: Boolean = true,
    ): Pair<CharacterWorldViewModel, WorldMembershipService> {
        val characterDao = mockk<CharacterDao> {
            coEvery { getByUuid(uuid) } returns
                CharacterEntity(uuid = uuid, name = "苏晚", creationDate = 0L, joinedWorld = joined, worldHomeCityId = homeCityId)
        }
        val worldNativeDao = mockk<WorldNativeDao> {
            coEvery { getByRecruitedUuid(uuid) } returns
                if (native) WorldNativeStateEntity(nativeId = "native:x", recruitedCharacterUuid = uuid) else null
        }
        val worldDao = mockk<WorldDao> {
            coEvery { getState() } returns if (worldExists) WorldStateEntity(seed = seed, createdAt = 0L) else null
        }
        val worldBookRepo = mockk<WorldBookRepository> {
            coEvery { boundBookUuids(uuid) } returns if (wbBound) listOf("book1") else emptyList()
        }
        val service = mockk<WorldMembershipService>(relaxed = true)
        val vm = CharacterWorldViewModel(
            characterDao, worldNativeDao, worldDao, worldBookRepo, service,
            SavedStateHandle(mapOf("characterUuid" to uuid)),
        )
        idle()
        return vm to service
    }

    @Test
    fun `四态派生_普通关`() {
        val (vm, _) = buildVm(joined = false)
        val s = vm.state.value
        assertTrue(s.loaded)
        assertFalse(s.joined); assertFalse(s.nativeOrigin); assertFalse(s.worldbookBound)
    }

    @Test
    fun `四态派生_已加入_住址城名与regions解析`() {
        val (vm, _) = buildVm(joined = true)
        val s = vm.state.value
        assertTrue(s.joined); assertFalse(s.nativeOrigin); assertFalse(s.worldbookBound)
        assertEquals(WorldIds.HOME_CITY_ID, s.homeCityId)
        assertEquals("云野镇", s.homeCityName)
        assertTrue("同城默认", s.sameCityAsUser)
        assertEquals("十大区全列", 10, s.regions.size)
        assertTrue("住址城所在区有城", s.citiesOfRegion.isNotEmpty())
    }

    @Test
    fun `四态派生_世界书锁`() {
        val (vm, _) = buildVm(joined = false, wbBound = true)
        val s = vm.state.value
        assertTrue(s.worldbookBound); assertFalse(s.joined); assertFalse(s.nativeOrigin)
    }

    @Test
    fun `四态派生_原住民锁`() {
        val (vm, _) = buildVm(joined = true, native = true)
        val s = vm.state.value
        assertTrue(s.nativeOrigin); assertTrue(s.joined)
    }

    @Test
    fun `join委托service`() {
        val (vm, service) = buildVm(joined = false)
        vm.join()
        idle()
        coVerify(exactly = 1) { service.join(uuid, any()) }
    }

    @Test
    fun `leave委托service`() {
        val (vm, service) = buildVm(joined = true)
        vm.leave()
        idle()
        coVerify(exactly = 1) { service.leave(uuid, any()) }
    }

    @Test
    fun `move委托service_传目标城`() {
        val (vm, service) = buildVm(joined = true)
        vm.move("city_taoqiu")
        idle()
        coVerify(exactly = 1) { service.move(uuid, "city_taoqiu", any()) }
    }

    @Test
    fun `selectRegion切区_只重算城列表不落库`() {
        val (vm, service) = buildVm(joined = true)
        val other = vm.state.value.regions.first { it.id != vm.state.value.selectedRegionId }
        vm.selectRegion(other.id)
        assertEquals(other.id, vm.state.value.selectedRegionId)
        coVerify(exactly = 0) { service.join(any(), any()) }
        coVerify(exactly = 0) { service.move(any(), any(), any()) }
    }
}
