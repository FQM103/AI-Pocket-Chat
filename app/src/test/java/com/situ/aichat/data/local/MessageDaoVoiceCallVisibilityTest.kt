package com.situ.aichat.data.local

import androidx.room.Room
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 语音通话逐轮转写不进聊天气泡流 T2（2026-07-12 用户实机上报「你在干什么」通话原话以气泡泄漏）：
 * 规格——[com.situ.aichat.voice.VoiceCallPersistence] 落的 `isPartOfVoiceCall=true` 轮次消息只供模型历史与
 * 记忆链路，用户可见面（聊天列表窗口 / 删后预览重算 / 通知回复线程）一律过滤；用户回看通话内容的唯一入口 =
 * 通话记录卡（CALL_RECORD_CARD·isPartOfVoiceCall=false）。三条可见性 SQL 与
 * [com.situ.aichat.offline.OfflineChatVisibility] 谓词同源（改一处须同步全部）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageDaoVoiceCallVisibilityTest {

    private lateinit var db: AppDatabase
    private val convUuid = "conv-1"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.characterDao().upsert(CharacterEntity(uuid = "char-1", name = "角色", creationDate = 0L))
            db.conversationDao().upsert(ConversationEntity(uuid = convUuid, title = "会话", characterUuid = "char-1", creationDate = 0L))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insert(
        ts: Long,
        role: String = "user",
        content: String = "消息$ts",
        kind: String = "plain_text",
        isPartOfVoiceCall: Boolean = false,
    ) {
        db.messageDao().upsert(
            MessageEntity(
                messageUUID = "m-$ts", conversationUuid = convUuid,
                roleRaw = role, content = content, timestamp = ts, messageKindRaw = kind,
                isPartOfVoiceCall = isPartOfVoiceCall,
            ),
        )
    }

    /** 复刻实机场景：普通聊天两条 → 通话轮次（user+assistant）→ 通话记录卡。 */
    private suspend fun seedCallScenario() {
        insert(1L, role = "user", content = "那个，你睡了吗?")
        insert(2L, role = "assistant", content = "嗯……在睡觉啦")
        insert(3L, role = "user", content = "你在干什么", isPartOfVoiceCall = true)
        insert(4L, role = "assistant", content = "在陪你打电话呀", isPartOfVoiceCall = true)
        insert(5L, role = "assistant", content = "{\"type\":\"call_record\"}", kind = "call_record_card")
    }

    @Test
    fun `聊天列表窗口过滤通话轮次_保留通话记录卡与普通消息`() = runBlocking {
        seedCallScenario()
        val visible = db.messageDao().observeVisibleWindowed(convUuid, 50).first()
        assertEquals(listOf("m-5", "m-2", "m-1"), visible.map { it.messageUUID })
        assertTrue("窗口内绝不出现通话轮次消息", visible.none { it.isPartOfVoiceCall })
    }

    @Test
    fun `最新可见消息跳过通话轮次_预览重算不回落通话原话`() = runBlocking {
        insert(1L, content = "普通消息")
        insert(2L, content = "你在干什么", isPartOfVoiceCall = true)
        // 删卡片/最新消息后重算预览的场景：最新一条可见 = 普通消息，绝不把通话原话当预览。
        assertEquals("m-1", db.messageDao().latestVisibleMessage(convUuid)?.messageUUID)
    }

    @Test
    fun `通知回复线程与快捷回复预览同口径过滤`() = runBlocking {
        seedCallScenario()
        val recent = db.messageDao().getRecentVisible(convUuid, 50)
        assertTrue("通知/快捷回复预览绝不泄漏通话原话", recent.none { it.isPartOfVoiceCall })
        assertEquals(listOf("m-5", "m-2", "m-1"), recent.map { it.messageUUID })
    }

    @Test
    fun `模型上下文取数不受过滤影响_通话轮次照常入历史`() = runBlocking {
        seedCallScenario()
        val history = db.messageDao().getRecent(convUuid, 50)
        assertEquals("getRecent 全量：模型必须看到通话里说过的话", 5, history.size)
        assertTrue(history.any { it.isPartOfVoiceCall })
    }
}
