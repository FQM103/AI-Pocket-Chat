package com.situ.aichat.data.repository

import com.situ.aichat.data.local.entity.CharacterEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test

/**
 * CharacterDeletionService 行为测试——验证删角色链「真的能用」(不止编译过)：跑在服务自有 app 级 scope 上、
 * 不随调用方 (ViewModel) 离屏取消而丢半截，且保持 iOS 删序 (cleanup 先于 delete)。
 *
 * 手法：MockK 假掉 [CharacterDeletionCleaner] / [CharacterRepository]；character 用 relaxed mock 只桩 uuid +
 * avatarPath(null → `AvatarStore.delete` 真 no-op·见其实现)。服务自有 `Dispatchers.Default` scope，故用真并发 +
 * [CompletableDeferred] 做确定性 happens-before 交接 + `withTimeout`/`coVerify(timeout)` 兜底 → 秒级、可重复、
 * 不吃线程竞态。
 */
class CharacterDeletionServiceTest {

    private lateinit var deletionCleaner: CharacterDeletionCleaner
    private lateinit var characterRepo: CharacterRepository
    private lateinit var service: CharacterDeletionService
    private lateinit var character: CharacterEntity

    @Before
    fun setUp() {
        deletionCleaner = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        character = mockk(relaxed = true)
        every { character.uuid } returns "char-1"
        every { character.avatarPath } returns null
        service = CharacterDeletionService(deletionCleaner, characterRepo)
    }

    @Test
    fun 删除链按iOS序_cleanup先于删角色本体() = runBlocking {
        service.delete(character)
        // 服务跑在自有 scope，fire-and-forget；用 timeout 等链跑到删本体再核顺序。
        coVerify(timeout = 2_000) { characterRepo.delete("char-1") }
        coVerifyOrder {
            deletionCleaner.cleanup(character)
            characterRepo.delete("char-1")
        }
    }

    @Test
    fun 调用方scope半途取消_删除链仍跑完不丢半截() = runBlocking {
        val cleanupEntered = CompletableDeferred<Unit>()
        val allowCleanupToFinish = CompletableDeferred<Unit>()
        coEvery { deletionCleaner.cleanup(character) } coAnswers {
            cleanupEntered.complete(Unit)
            allowCleanupToFinish.await() // 把 cleanup 卡在半途，好在此刻取消调用方
        }

        // 调用方 scope = viewModelScope 替身：发起删除后保持存活，待会儿在 cleanup 半途把它取消（用户离开联系人页）。
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        callerScope.launch {
            service.delete(character)
            awaitCancellation()
        }

        withTimeout(2_000) { cleanupEntered.await() } // 服务自有 scope 已开始清理
        callerScope.cancel()                          // 调用方离屏取消
        allowCleanupToFinish.complete(Unit)           // 放行 cleanup 收尾

        // 关键断言：尽管调用方已取消，删除链仍抵达 characterRepo.delete（不会留下「已清理一半但仍存在」的半残角色）。
        coVerify(timeout = 2_000) { characterRepo.delete("char-1") }
        coVerifyOrder {
            deletionCleaner.cleanup(character)
            characterRepo.delete("char-1")
        }
    }
}
