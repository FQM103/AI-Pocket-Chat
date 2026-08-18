package com.situ.aichat.ui.promise

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.data.repository.PromiseRepository
import com.situ.aichat.promise.PromiseLedgerService
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [PromiseLedgerViewModel] T2（记忆改造三期·图纸 §7 T2-2·MockK 假仓库/服务 + Robolectric）：
 * openPromises=sortedOpen 序 · select/dismiss 派生 detail · 背景状态变更 detail 跟变（E1）· markResolved 调用链 + 清选中。
 * WhileSubscribed 需订阅者才开闸 → CoroutineScope(Main)+idle() 驱动（同 MomentsHubViewModelTest 惯例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromiseLedgerViewModelTest {

    private val openFlow = MutableStateFlow<List<PromiseEntity>>(emptyList())
    private val resolvedFlow = MutableStateFlow<List<PromiseEntity>>(emptyList())
    private val ledgerService = mockk<PromiseLedgerService>(relaxed = true)

    private fun newVm(): PromiseLedgerViewModel {
        val repo = mockk<PromiseRepository> {
            every { observeOpenByCharacter("c1") } returns openFlow
            every { observeResolvedByCharacter("c1") } returns resolvedFlow
        }
        val handle = SavedStateHandle(mapOf(PromiseLedgerViewModel.ARG_CHARACTER_UUID to "c1"))
        return PromiseLedgerViewModel(handle, repo, ledgerService)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** 订阅两列表 + detail（WhileSubscribed 开闸）并驱动主循环，跑完 block 再退订。 */
    private fun <T> withSubscriptions(vm: PromiseLedgerViewModel, block: () -> T): T {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        scope.launch { vm.openPromises.collect {} }
        scope.launch { vm.resolvedPromises.collect {} }
        scope.launch { vm.detail.collect {} }
        idle()
        return try { block() } finally { scope.coroutineContext[Job]?.cancel() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(400) {
            idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("等待超时：$message")
    }

    private fun openP(uuid: String, created: Long, due: Long? = null) = PromiseEntity(
        uuid = uuid, characterUuid = "c1", content = uuid, statusRaw = PromiseStatus.OPEN,
        dueAtMillis = due, createdAtMillis = created, updatedAtMillis = created,
    )

    private fun resolvedP(uuid: String, status: String, resolvedAt: Long) = PromiseEntity(
        uuid = uuid, characterUuid = "c1", content = uuid, statusRaw = status,
        resolvedAtMillis = resolvedAt, createdAtMillis = 0, updatedAtMillis = resolvedAt,
    )

    // ① openPromises 输出 = sortedOpen 序（due 升序在前，其后 created 升序）。
    @Test fun openPromises_isSortedOpenOrder() {
        val vm = newVm()
        withSubscriptions(vm) {
            openFlow.value = listOf(
                openP("n1", created = 300),
                openP("d2", created = 100, due = 5_000),
                openP("d1", created = 200, due = 1_000),
            )
            await("openPromises 输出排序") {
                vm.openPromises.value.map { it.uuid } == listOf("d1", "d2", "n1")
            }
        }
    }

    // ② select 后 detail 命中对应实体；dismissDetail 后为 null。
    @Test fun select_setsDetail_dismissClearsIt() {
        val vm = newVm()
        withSubscriptions(vm) {
            openFlow.value = listOf(openP("p1", created = 100))
            await("列表就绪") { vm.openPromises.value.isNotEmpty() }
            vm.select("p1")
            await("detail 命中") { vm.detail.value?.uuid == "p1" }
            vm.dismissDetail()
            await("detail 清空") { vm.detail.value == null }
            assertEquals("", vm.selectedUuid.value)
        }
    }

    // ③ 背景状态变更（换 Flow 值·对账把 open 移入 resolved）→ detail 跟变（E1）。
    @Test fun backgroundStatusChange_detailFollows_e1() {
        val vm = newVm()
        withSubscriptions(vm) {
            openFlow.value = listOf(openP("p1", created = 100))
            await("open 就绪") { vm.openPromises.value.isNotEmpty() }
            vm.select("p1")
            await("detail=open") { vm.detail.value?.statusRaw == PromiseStatus.OPEN }
            openFlow.value = emptyList()
            resolvedFlow.value = listOf(resolvedP("p1", PromiseStatus.FULFILLED, resolvedAt = 999))
            await("detail 跟变已了结") { vm.detail.value?.statusRaw == PromiseStatus.FULFILLED }
        }
    }

    // ④ markResolved → 调 ledgerService.resolveManually 且 selectedUuid 已清空。
    @Test fun markResolved_callsServiceAndClearsSelection() {
        val vm = newVm()
        withSubscriptions(vm) {
            openFlow.value = listOf(openP("p1", created = 100))
            await("就绪") { vm.openPromises.value.isNotEmpty() }
            vm.select("p1")
            await("已选中") { vm.selectedUuid.value == "p1" }
            vm.markResolved("p1", PromiseStatus.FULFILLED)
            await("选中已清空") { vm.selectedUuid.value == "" }
            coVerify(exactly = 1) { ledgerService.resolveManually("p1", PromiseStatus.FULFILLED, any()) }
        }
    }
}
