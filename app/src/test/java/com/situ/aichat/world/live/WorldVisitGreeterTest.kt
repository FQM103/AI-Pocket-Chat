package com.situ.aichat.world.live

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * [WorldVisitGreeter] T2-4（Robolectric 真 Room·图纸 §7·E15）：到达开场落库（role=assistant·timestamp=arriveAt）+
 * 未读 +1 + 会话末条更新 + 无会话先建；重复结算不双插（幂等）。断言从图纸 §3 独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldVisitGreeterTest {

    private lateinit var db: AppDatabase
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var greeter: WorldVisitGreeter

    private val charUuid = "char-azhe"
    private val name = "阿哲"
    private val travelKey = "char-azhe:1700000000000"
    private val arriveAt = 1700000000000L
    private val openerUuid = UUID.nameUUIDFromBytes("world:visitopener:$travelKey".toByteArray()).toString()

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        conversationRepo = ConversationRepository(db.conversationDao())
        messageRepo = MessageRepository(db.messageDao())
        greeter = WorldVisitGreeter(conversationRepo, messageRepo)
        db.characterDao().upsert(CharacterEntity(uuid = charUuid, name = name, creationDate = 0L)) // 满足会话 FK
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `到达开场落库_未读加1_末条更新_无会话先建`() = runBlocking {
        greeter.greetArrival(charUuid, name, travelKey, arriveAt)
        val convUuid = conversationRepo.getOrCreateForCharacter(charUuid, name) // 幂等·= 首建的那条
        val expectedText = WorldVisitGreeter.OPENERS[WorldVisitGreeter.variantOf(travelKey)]

        val msg = messageRepo.get(openerUuid)!!
        assertEquals("assistant", msg.roleRaw)
        assertEquals(expectedText, msg.content)
        assertEquals(arriveAt, msg.timestamp)
        assertEquals(convUuid, msg.conversationUuid)

        val conv = db.conversationDao().getByUuid(convUuid)!!
        assertEquals("未读 +1", 1, conv.cachedUnreadCount)
        assertEquals("assistant", conv.lastMessageRole)
        assertEquals(expectedText.take(60), conv.lastMessagePreview)
        assertEquals(arriveAt, conv.lastMessageDate)
        assertEquals("恰一条消息", 1, messageRepo.recentChronological(convUuid, 50).size)
    }

    @Test
    fun `重复结算不双插_E15`() = runBlocking {
        greeter.greetArrival(charUuid, name, travelKey, arriveAt)
        greeter.greetArrival(charUuid, name, travelKey, arriveAt) // 二次结算 / 返程那趟
        val convUuid = conversationRepo.getOrCreateForCharacter(charUuid, name)
        assertEquals("不双插", 1, messageRepo.recentChronological(convUuid, 50).size)
        assertEquals("未读不重复 +1", 1, db.conversationDao().getByUuid(convUuid)!!.cachedUnreadCount)
    }
}
