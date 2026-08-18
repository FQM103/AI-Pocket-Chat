package com.situ.aichat.prompt.diary

import android.content.Context
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.DiaryCommentEntity
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.util.LocaleManager
import com.situ.aichat.work.BackgroundScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * T2：角色回应用户回复的服务链（R3·契约 §2 F3·MockK 全假）。守卫链从规格独立反推：
 * 有用户回复才生成、已回应过幂等跳过、回应以 parentCommentId=根 id 入库。
 */
class DiaryCommentReplyTest {

    private val context = mockk<Context>(relaxed = true)
    private val contextLog = mockk<ContextLogService>()
    private val apiConfigRepo = mockk<ApiConfigRepository>()
    private val diaryRepository = mockk<DiaryRepository>(relaxed = true)
    private val characterDao = mockk<CharacterDao>()
    private val userProfileDao = mockk<UserProfileDao>()
    private val settingsRepo = mockk<SettingsRepository>()
    private val service = DiaryCommentService(
        context = context,
        contextLog = contextLog,
        apiConfigRepo = apiConfigRepo,
        diaryRepository = diaryRepository,
        characterDao = characterDao,
        userProfileDao = userProfileDao,
        settingsRepo = settingsRepo,
        backgroundScheduler = mockk<BackgroundScheduler>(relaxed = true),
    )

    private val entry = DiaryEntryEntity(uuid = "d1", content = "日记正文", visibilityRaw = "openToAI")
    private val root = DiaryCommentEntity(id = "root1", entryUuid = "d1", content = "角色评论", timestamp = 100, characterUuid = "c1")
    private val userReply = DiaryCommentEntity(
        id = "u1", entryUuid = "d1", content = "用户回你", timestamp = 200,
        characterUuid = null, parentCommentId = "root1", isFromUser = true,
    )

    @Before fun setUp() {
        // PromptStrings 内部走 LocaleManager.wrap(context).getString → JVM 单测替换为哨兵串。
        mockkObject(LocaleManager)
        every { LocaleManager.wrap(any()) } returns context
        every { context.getString(any()) } returns "s"
        coEvery { diaryRepository.getEntry("d1") } returns entry
        coEvery { apiConfigRepo.resolveConfigValues(any()) } returns mockk<ApiConfigValues>()
        coEvery { settingsRepo.getAppSettings() } returns AppSettings()
        coEvery { characterDao.getByUuid("c1") } returns
            CharacterEntity(uuid = "c1", name = "小满", creationDate = 0L)
        coEvery { userProfileDao.get() } returns null
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "这是角色的回应内容"
    }

    @After fun tearDown() = unmockkObject(LocaleManager)

    @Test fun `happy path - response lands as child of the root comment`(): Unit = runBlocking {
        coEvery { diaryRepository.commentsForEntry("d1") } returns listOf(root, userReply)

        service.generateReplyForComment("d1", "root1")

        coVerify(exactly = 1) {
            diaryRepository.addComment(
                entryUuid = "d1",
                content = "这是角色的回应内容",
                characterUuid = "c1",
                timestamp = any(),
                parentCommentId = "root1",
                isFromUser = false,
            )
        }
    }

    @Test fun `no user reply yet - nothing generated`(): Unit = runBlocking {
        coEvery { diaryRepository.commentsForEntry("d1") } returns listOf(root)

        service.generateReplyForComment("d1", "root1")

        coVerify(exactly = 0) { diaryRepository.addComment(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `already responded after user reply - idempotent skip (worker retry safe)`(): Unit = runBlocking {
        val existingResponse = DiaryCommentEntity(
            id = "a1", entryUuid = "d1", content = "已回应过", timestamp = 300,
            characterUuid = "c1", parentCommentId = "root1", isFromUser = false,
        )
        coEvery { diaryRepository.commentsForEntry("d1") } returns listOf(root, userReply, existingResponse)

        service.generateReplyForComment("d1", "root1")

        coVerify(exactly = 0) { diaryRepository.addComment(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `root authored by user (degraded orphan) - never responded to`(): Unit = runBlocking {
        val userRoot = DiaryCommentEntity(
            id = "root1", entryUuid = "d1", content = "用户根", timestamp = 100,
            characterUuid = null, isFromUser = true,
        )
        coEvery { diaryRepository.commentsForEntry("d1") } returns listOf(userRoot, userReply)

        service.generateReplyForComment("d1", "root1")

        coVerify(exactly = 0) { diaryRepository.addComment(any(), any(), any(), any(), any(), any()) }
    }

    // MARK: - R6-1 交换日记留言：作者回应用户顶层留言

    private val exchangeEntry = DiaryEntryEntity(
        uuid = "d1", content = "TA 的信正文", visibilityRaw = "openToAI", authorCharacterUuid = "c1",
    )
    private val userNote = DiaryCommentEntity(
        id = "n1", entryUuid = "d1", content = "读了你的信", timestamp = 100,
        characterUuid = null, parentCommentId = null, isFromUser = true,
    )

    @Test fun `exchange note - the letter's author responds as a child of the note`(): Unit = runBlocking {
        coEvery { diaryRepository.getEntry("d1") } returns exchangeEntry
        coEvery { diaryRepository.commentsForEntry("d1") } returns listOf(userNote)

        service.generateReplyForComment("d1", "n1")

        coVerify(exactly = 1) {
            diaryRepository.addComment(
                entryUuid = "d1",
                content = "这是角色的回应内容",
                characterUuid = "c1",
                timestamp = any(),
                parentCommentId = "n1",
                isFromUser = false,
            )
        }
    }

    @Test fun `exchange note - author already responded, idempotent skip`(): Unit = runBlocking {
        val authorResponse = DiaryCommentEntity(
            id = "a1", entryUuid = "d1", content = "作者已回", timestamp = 200,
            characterUuid = "c1", parentCommentId = "n1", isFromUser = false,
        )
        coEvery { diaryRepository.getEntry("d1") } returns exchangeEntry
        coEvery { diaryRepository.commentsForEntry("d1") } returns listOf(userNote, authorResponse)

        service.generateReplyForComment("d1", "n1")

        coVerify(exactly = 0) { diaryRepository.addComment(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `exchange note - author character deleted, graceful no-op`(): Unit = runBlocking {
        coEvery { diaryRepository.getEntry("d1") } returns exchangeEntry.copy(authorCharacterUuid = "gone")
        coEvery { diaryRepository.commentsForEntry("d1") } returns listOf(userNote)
        coEvery { characterDao.getByUuid("gone") } returns null

        service.generateReplyForComment("d1", "n1")

        coVerify(exactly = 0) { diaryRepository.addComment(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `user top-level note on a non-exchange diary - nothing generated (R3 path unaffected)`(): Unit = runBlocking {
        // 用户日记（无作者）下的用户根：既不走 R6-1（非交换），也不满足 R3（根非角色评论）。
        coEvery { diaryRepository.getEntry("d1") } returns entry
        coEvery { diaryRepository.commentsForEntry("d1") } returns listOf(userNote)

        service.generateReplyForComment("d1", "n1")

        coVerify(exactly = 0) { diaryRepository.addComment(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `exchange diary - R3 round on a character root comment still works`(): Unit = runBlocking {
        // 交换日记下别的角色评论 + 用户回复 → 仍走 R3 该角色回应（分派互不串线）。
        val otherRoot = DiaryCommentEntity(
            id = "root2", entryUuid = "d1", content = "别的角色评论", timestamp = 100, characterUuid = "c1",
        )
        val reply = userReply.copy(id = "u2", parentCommentId = "root2")
        coEvery { diaryRepository.getEntry("d1") } returns exchangeEntry
        coEvery { diaryRepository.commentsForEntry("d1") } returns listOf(otherRoot, reply)

        service.generateReplyForComment("d1", "root2")

        coVerify(exactly = 1) {
            diaryRepository.addComment(
                entryUuid = "d1",
                content = "这是角色的回应内容",
                characterUuid = "c1",
                timestamp = any(),
                parentCommentId = "root2",
                isFromUser = false,
            )
        }
    }

    @Test fun `assertion sanity - completion output is validator-clean`() {
        // 守住测试自身前提：哨兵回应串须过脏数据门（否则 happy path 假阴）。
        assertEquals(true, com.situ.aichat.prompt.GeneratedContentValidator.isLikelyValid("这是角色的回应内容"))
    }
}
