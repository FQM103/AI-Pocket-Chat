package com.situ.aichat.data.local

import androidx.room.Room
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 世界书时效计数锚 T2（批1 1-3·Robolectric 真 Room·CHAT_CORE_HEALTH_PLAN.md）：
 * 规格——计数锚 = 会话内「非 system、非结构化卡、非空」消息的**真实总数**，恒单调、不受任何取数窗口封顶
 * （修复前用「本轮取到的条数」，超发送路径 500 条上限后计数停摆 → sticky 永不过期 / cooldown 失效）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageDaoScanCountTest {

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

    private suspend fun insert(ts: Long, role: String = "user", content: String = "消息$ts", kind: String = "plain_text") {
        db.messageDao().upsert(
            MessageEntity(
                messageUUID = "m-$ts", conversationUuid = convUuid,
                roleRaw = role, content = content, timestamp = ts, messageKindRaw = kind,
            ),
        )
    }

    @Test
    fun `计数超过500条不封顶且排除system_空文本_结构化卡`() = runBlocking {
        for (ts in 1L..600L) insert(ts)                                  // 600 条正常消息
        insert(1001L, role = "system")                                   // system 不计
        insert(1002L, content = "")                                      // 空文本不计
        insert(1003L, kind = "gift_card", content = "{\"cost\":99}")     // 结构化卡不计
        insert(1004L, kind = "red_packet", content = "{\"amount\":88}")
        insert(1005L, kind = "call_record_card", content = "{}")
        insert(1006L, kind = "offline_marker_start", content = "[标记]")

        assertEquals("真实总数 600，绝不停在取数窗口平台值", 600, db.messageDao().countScannableForWorldBook(convUuid))
    }

    @Test
    fun `计数随新消息单调递增`() = runBlocking {
        for (ts in 1L..10L) insert(ts)
        val before = db.messageDao().countScannableForWorldBook(convUuid)
        insert(11L)
        assertEquals(before + 1, db.messageDao().countScannableForWorldBook(convUuid))
    }
}
