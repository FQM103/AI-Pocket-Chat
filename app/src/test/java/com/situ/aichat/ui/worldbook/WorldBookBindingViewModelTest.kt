package com.situ.aichat.ui.worldbook

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.worldbook.WorldBookRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [WorldBookBindingViewModel] T2（W13 复核 R1 🟡-2）：世界书互斥「同屏陈旧态」单向击穿的 bind 侧守卫。
 *
 * 断言从 🟡-2 修改指令独立反推：① 已加入时 selectSole/toggle 对 repository **零调用**（[WorldBookBindingViewModel.isJoinedWorldNow]
 * 直读库守卫）② 未加入时正常委托 ③ nativeOrigin 态派生（原住民出身 → 段副标 native_locked 的数据来源）。
 * MockK 假 dao/repository·Robolectric 主循环驱动 viewModelScope。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBookBindingViewModelTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val uuid = "c1"

    private fun buildVm(
        joined: Boolean = false,
        native: Boolean = false,
    ): Pair<WorldBookBindingViewModel, WorldBookRepository> {
        val characterDao = mockk<CharacterDao> {
            coEvery { getByUuid(uuid) } returns
                CharacterEntity(uuid = uuid, name = "苏晚", creationDate = 0L, joinedWorld = joined)
        }
        val worldNativeDao = mockk<WorldNativeDao> {
            coEvery { getByRecruitedUuid(uuid) } returns
                if (native) WorldNativeStateEntity(nativeId = "native:x", recruitedCharacterUuid = uuid) else null
        }
        // 三个 observe* 在属性初始化时即被调用来搭 Flow 链（stateIn 上游需真 Flow）——显式桩住。
        val repository = mockk<WorldBookRepository>(relaxed = true) {
            every { observeBoundBookUuidsForCharacter(uuid) } returns flowOf(emptyList())
            every { observeBookSummaries() } returns flowOf(emptyList())
            every { observeAllBooks() } returns flowOf(emptyList())
        }
        val vm = WorldBookBindingViewModel(
            repository, characterDao, worldNativeDao,
            SavedStateHandle(mapOf("characterUuid" to uuid)),
        )
        idle()
        return vm to repository
    }

    @Test
    fun `未加入_selectSole与toggle正常委托repository`() {
        val (vm, repo) = buildVm(joined = false)
        vm.selectSole("book1")
        vm.toggle("book2", bound = true)
        vm.toggle("book3", bound = false)
        idle()
        coVerify(exactly = 1) { repo.setSoleBinding(uuid, "book1") }
        coVerify(exactly = 1) { repo.bind(uuid, "book2") }
        coVerify(exactly = 1) { repo.unbind(uuid, "book3") }
    }

    @Test
    fun `已加入_selectSole与toggle对repository零调用_击穿被挡`() {
        val (vm, repo) = buildVm(joined = true)
        vm.selectSole("book1")
        vm.toggle("book2", bound = true)
        vm.toggle("book3", bound = false)
        idle()
        coVerify(exactly = 0) { repo.setSoleBinding(any(), any()) }
        coVerify(exactly = 0) { repo.bind(any(), any()) }
        coVerify(exactly = 0) { repo.unbind(any(), any()) }
    }

    @Test
    fun `isJoinedWorldNow_直读库为真时返回真并回写joinedWorld`() = runBlocking {
        val (vm, _) = buildVm(joined = true)
        assertTrue(vm.isJoinedWorldNow())
        assertTrue(vm.joinedWorld.value)
    }

    @Test
    fun `nativeOrigin态派生`() {
        val (vmNative, _) = buildVm(joined = true, native = true)
        assertTrue("原住民出身", vmNative.nativeOrigin.value)

        val (vmPlain, _) = buildVm(joined = false, native = false)
        assertFalse("普通角色", vmPlain.nativeOrigin.value)
    }
}
