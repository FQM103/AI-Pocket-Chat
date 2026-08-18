package com.situ.aichat.ui.sticker

import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StickerRepository
import com.situ.aichat.sticker.DisabledBuiltInStickerStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * StickerManagementViewModel 行为（K7·2026-07-12 性能线程专项）：构造器不再主线程读盘——
 * init 协程（IO）回填 disabledIds；mutator 同步重读继续反映最新持久化状态。
 * 等待姿势：轮询「首载已落」（非空 = init 读确已发生）再做后续断言，防 init 迟到覆盖（PITFALLS 1e）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StickerManagementViewModelTest {

    private val stickerRepo = mockk<StickerRepository> { every { observeAll() } returns emptyFlow() }
    private val settingsRepo = mockk<SettingsRepository> { every { appSettings } returns emptyFlow() }

    /** 轮询等 init 协程的首载真实落地（value 非空即证据），超时 5s。 */
    private fun awaitFirstLoad(vm: StickerManagementViewModel): Set<String> {
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.disabledIds.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return vm.disabledIds.value
    }

    @Test
    fun `init loads persisted disabled ids asynchronously`() {
        val context = RuntimeEnvironment.getApplication()
        DisabledBuiltInStickerStore.disable(context, "builtin_x")
        val vm = StickerManagementViewModel(context, stickerRepo, settingsRepo)
        assertEquals(setOf("builtin_x"), awaitFirstLoad(vm))
    }

    @Test
    fun `mutators keep reflecting fresh store state after first load`() {
        val context = RuntimeEnvironment.getApplication()
        DisabledBuiltInStickerStore.disable(context, "a")
        val vm = StickerManagementViewModel(context, stickerRepo, settingsRepo)
        awaitFirstLoad(vm) // 先证 init 已落，再动 mutator（防迟到覆盖型 flaky）
        vm.hideBuiltIn("b")
        assertEquals(setOf("a", "b"), vm.disabledIds.value)
        vm.enableBuiltIn("a")
        assertEquals(setOf("b"), vm.disabledIds.value)
        vm.restoreAllBuiltIn()
        assertEquals(emptySet<String>(), vm.disabledIds.value)
    }
}
