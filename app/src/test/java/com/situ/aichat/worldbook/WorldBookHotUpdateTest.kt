package com.situ.aichat.worldbook

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.worldbook.WorldBookRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 热更新 T2（WB7c·契约 §12.11 三验收·真 Room 全链）：聊天中途经仓库改条目 / 改绑定，
 * 下一回合激活立即按新状态生效——证明服务层无跨回合缓存、每回合现读现算。
 * 另验 [toWorldInfoSettings] 映射（触发设置改完即用的最后一环）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBookHotUpdateTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: WorldBookRepository
    private lateinit var service: WorldBookPromptService

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WorldBookRepository(db.worldBookDao())
        val vectorService = mockk<WorldBookVectorService> {
            coEvery { matchedEntryUuids(any(), any(), any()) } returns emptySet()
        }
        service = WorldBookPromptService(db.worldBookDao(), vectorService, db.messageDao())
        runBlocking { db.characterDao().upsert(CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun msg(uuid: String, content: String): MessageEntity =
        MessageEntity(messageUUID = uuid, conversationUuid = "conv1", roleRaw = "user", content = content, timestamp = 0L)

    private fun activate(text: String): WorldInfoActivationResult? = runBlocking {
        service.activateForTurn(
            characterUuid = "c1",
            conversationUuid = "conv1",
            sortedMessages = listOf(msg("u-$text", text)),
            characterName = "小雨",
            userName = "阿檀",
        )
    }

    private fun allInjectedText(result: WorldInfoActivationResult?): String {
        val r = result ?: return ""
        return listOf(r.before, r.after, r.suffix).joinToString("\n") +
            r.atDepth.joinToString("\n") { it.content }
    }

    @Test
    fun 聊天中途改条目内容_下一回合注入新文本() = runBlocking {
        val bookUuid = repo.createBook("青云录")
        repo.bind("c1", bookUuid)
        val entry = repo.newEntryDraft(bookUuid).copy(
            comment = "青云门",
            content = "旧设定：青云门在东域。",
            keysJson = """["青云门"]""",
        )
        repo.saveEntry(entry)

        val first = allInjectedText(activate("聊聊青云门"))
        assertTrue("首回合注入旧文本", first.contains("旧设定：青云门在东域。"))

        // 用户聊到一半去改设定
        repo.saveEntry(entry.copy(content = "新设定：青云门已迁往北境。"))

        val second = allInjectedText(activate("再聊青云门"))
        assertTrue("下一回合立即注入新文本", second.contains("新设定：青云门已迁往北境。"))
        assertFalse("旧文本不得残留", second.contains("旧设定：青云门在东域。"))
    }

    @Test
    fun 聊天中途绑定与解绑_下一回合生效() = runBlocking {
        val bookUuid = repo.createBook("青云录")
        repo.saveEntry(
            repo.newEntryDraft(bookUuid).copy(comment = "基调", content = "修真世界基调。", constant = true),
        )

        assertNull("未绑定时不激活", activate("你好"))

        repo.bind("c1", bookUuid)
        assertNotNull("绑定后下一回合即激活", activate("你好"))

        repo.unbind("c1", bookUuid)
        assertNull("解绑后下一回合即失效", activate("你好"))
    }

    @Test
    fun 中途拨开关_整本与单条即时生效() = runBlocking {
        val bookUuid = repo.createBook("青云录")
        repo.bind("c1", bookUuid)
        val entry = repo.newEntryDraft(bookUuid).copy(comment = "基调", content = "常驻基调。", constant = true)
        repo.saveEntry(entry)

        assertNotNull(activate("你好"))

        repo.setEntryEnabled(entry.uuid, false)
        assertNull("条目停用即时生效", activate("你好"))

        repo.setEntryEnabled(entry.uuid, true)
        repo.setBookEnabled(bookUuid, false)
        assertNull("整本停用即时生效", activate("你好"))
    }

    @Test
    fun 设置映射_worldInfo字段到引擎设置() {
        val settings = AppSettings(
            worldInfoScanDepth = 5,
            worldInfoBudgetChars = 8000,
            worldInfoRecursiveScan = true,
            worldInfoMaxRecursionSteps = 3,
            worldInfoInsertionStrategy = "GLOBAL_FIRST",
            worldInfoCaseSensitive = true,
            worldInfoMatchWholeWords = false,
        ).toWorldInfoSettings()

        assertEquals(5, settings.scanDepth)
        assertEquals(8000, settings.budgetChars)
        assertTrue(settings.recursiveScan)
        assertEquals(3, settings.maxRecursionSteps)
        assertEquals(WorldInfoInsertionStrategy.GLOBAL_FIRST, settings.insertionStrategy)
        assertTrue(settings.caseSensitive)
        assertFalse(settings.matchWholeWords)
    }

    @Test
    fun 设置映射_坏策略名宽容降级默认() {
        val settings = AppSettings(worldInfoInsertionStrategy = "不存在的策略").toWorldInfoSettings()
        assertEquals(WorldInfoInsertionStrategy.CHARACTER_FIRST, settings.insertionStrategy)
    }
}
