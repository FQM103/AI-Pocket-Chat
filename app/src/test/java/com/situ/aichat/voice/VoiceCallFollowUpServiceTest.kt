package com.situ.aichat.voice

import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.recovery.RecoveryClaimTracker
import com.situ.aichat.recovery.RecoveryReplyGenerator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2（C6 通话失联圆场·模式对齐 MeetingMissedReactionService）：
 *  - 旁白必落（SYSTEM_HINT·roleRaw=user·纯括号无保留段标题）+ 无头生成器被调；
 *  - 会话已被占坑（未答恢复/爽约反应进行中）→ 旁白仍落、生成让位（事件不丢：下轮互动角色自然带出）；
 *  - 生成器炸掉不外溢、坑位归还。
 */
class VoiceCallFollowUpServiceTest {

    private val messageRepo = mockk<MessageRepository>(relaxUnitFun = true)
    private val replyGenerator = mockk<RecoveryReplyGenerator> {
        coEvery { generateAndPersist(any()) } returns true
    }
    private val claimTracker = RecoveryClaimTracker() // 纯内存真身
    private val userProfileDao = mockk<UserProfileDao>(relaxed = true) // get()→null → 兜底「用户」
    private val service = VoiceCallFollowUpService(messageRepo, replyGenerator, claimTracker, userProfileDao)

    @Test
    fun `follow-up inserts hidden hint then generates in-character reply`() = runBlocking {
        val saved = slot<MessageEntity>()
        coEvery { messageRepo.upsert(capture(saved)) } returns Unit

        service.followUpAfterSilentCall("conv-1", now = 1_000L)

        assertEquals(MessageKind.SYSTEM_HINT.raw, saved.captured.messageKindRaw)
        assertEquals("user", saved.captured.roleRaw)
        assertTrue("旁白必须陈述『用户没听到你的话』的事实", saved.captured.content.contains("一句也没有听到"))
        assertTrue("纯括号旁白（§7：不得含保留段标题）", saved.captured.content.startsWith("（"))
        coVerify(exactly = 1) { replyGenerator.generateAndPersist("conv-1") }
    }

    @Test
    fun `claimed conversation - hint still lands, generation yields`() = runBlocking {
        claimTracker.tryBegin("conv-1") // 未答恢复正占着坑
        service.followUpAfterSilentCall("conv-1", now = 1_000L)

        coVerify(exactly = 1) { messageRepo.upsert(any()) }
        coVerify(exactly = 0) { replyGenerator.generateAndPersist(any()) }
    }

    @Test
    fun `generator blowing up does not escape and releases the claim`() = runBlocking {
        coEvery { replyGenerator.generateAndPersist(any()) } throws IllegalStateException("no key")

        service.followUpAfterSilentCall("conv-1", now = 1_000L) // 不许外溢

        assertTrue("坑位必须归还（否则未答恢复永久被锁）", claimTracker.tryBegin("conv-1"))
    }

    // ---- 盲区补扫 B3b：静默通话旁白第三人称指名（角色直读·「你」=角色不动·「用户」→真名·§9④）----

    @Test fun `silentCallHint uses real name, keeps 你 for character and 你们 pronoun`() {
        val hint = VoiceCallFollowUpService.silentCallHint("小明")
        assertTrue("开头用真名", hint.contains("刚才你和小明打了一通语音电话"))
        assertTrue("对方说话用真名", hint.contains("小明在电话里说了话"))
        assertTrue("你没听到用真名", hint.contains("你的回应小明一句也没有听到"))
        assertTrue("回应用真名", hint.contains("回应小明在电话里说过的话"))
        assertFalse("无通用码「用户」", hint.contains("用户"))
        assertTrue("「你们」代词保留不动", hint.contains("你们当前的关系"))
    }

    @Test fun `silentCallHint blank name falls back to 用户 literal`() {
        // 调用方兜底后传入的字面「用户」照常渲染（= 旧字节）。
        val hint = VoiceCallFollowUpService.silentCallHint("用户")
        assertTrue(hint.contains("刚才你和用户打了一通语音电话"))
        assertTrue(hint.contains("用户一句也没有听到"))
    }

    @Test fun `follow-up resolves nickname from dao into hint`() = runBlocking {
        coEvery { userProfileDao.get() } returns UserProfileEntity(nickname = "小雨")
        val saved = slot<MessageEntity>()
        coEvery { messageRepo.upsert(capture(saved)) } returns Unit

        service.followUpAfterSilentCall("conv-1", now = 1_000L)

        assertTrue("解析的昵称进旁白", saved.captured.content.contains("你和小雨打了一通语音电话"))
        assertFalse("无通用码", saved.captured.content.contains("用户"))
    }
}
