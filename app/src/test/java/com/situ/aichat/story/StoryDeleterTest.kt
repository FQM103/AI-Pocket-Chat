package com.situ.aichat.story

import com.situ.aichat.data.repository.StoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * StoryDeleter T2（2026-08-04 删除撤闹钟卷）：期望从缺陷现象独立反推——删掉带未来锁章的追更书后，
 * 闹钟到点照发「第 N 章已解锁」、深链指向已不存在的书。故三条不变式：
 * ① 章号必须在级联删库**前**捕获（删后章行已不在，key 含章号就撤不掉）；
 * ② 删库成功后按捕到的章号逐一撤销；
 * ③ 删库失败异常上抛且**一个闹钟都不撤**（书还在书架上，追更提醒不能丢）。
 */
class StoryDeleterTest {

    private val repository = mockk<StoryRepository>()
    private val unlockScheduler = mockk<StoryUnlockNotificationScheduler>(relaxed = true)
    private val deleter = StoryDeleter(repository, unlockScheduler)

    @Test
    fun 删除_先捕章号再删库_删成后按捕到的章号撤闹钟() = runBlocking {
        coEvery { repository.getChapterNumbers("s1") } returns listOf(1, 2, 5)
        coEvery { repository.deleteStory("s1") } just Runs

        deleter.delete("s1")

        coVerifyOrder {
            repository.getChapterNumbers("s1")
            repository.deleteStory("s1")
            unlockScheduler.cancelUnlocks("s1", listOf(1, 2, 5))
        }
    }

    @Test
    fun 删库失败_异常上抛且闹钟一个不撤() = runBlocking {
        coEvery { repository.getChapterNumbers("s1") } returns listOf(3)
        coEvery { repository.deleteStory("s1") } throws IllegalStateException("disk io")

        try {
            deleter.delete("s1")
            fail("删库异常应原样上抛（三入口 VM 各自 runCatching 出各自的失败提示）")
        } catch (e: IllegalStateException) {
            assertEquals("disk io", e.message)
        }
        verify(exactly = 0) { unlockScheduler.cancelUnlocks(any(), any()) }
    }

    @Test
    fun 无章故事_删库照走_撤销收到空清单() = runBlocking {
        coEvery { repository.getChapterNumbers("s0") } returns emptyList()
        coEvery { repository.deleteStory("s0") } just Runs

        deleter.delete("s0")

        coVerify(exactly = 1) { repository.deleteStory("s0") }
        verify(exactly = 1) { unlockScheduler.cancelUnlocks("s0", emptyList()) }
    }
}
