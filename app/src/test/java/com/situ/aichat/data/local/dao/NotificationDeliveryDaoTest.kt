package com.situ.aichat.data.local.dao

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.NotificationDeliveryRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 主动通知真实感改造 R1 🔴-1：[NotificationDeliveryDao.markDelivered] 真 Room 行为。
 *
 * 它是三闸（连发 / 降频 / 查重）读数的数据源——后台弹出的通知靠它置 `deliveredAt`，否则
 * 「用户不开 App」期间三闸读数恒 0。断言从返工指令的机制反推：**双写**（deliveredAt + 正文）、
 * **守卫不覆盖既有值**（与回前台 drain 回灌幂等互斥）、**未命中零行不抛**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationDeliveryDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NotificationDeliveryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.notificationDeliveryDao()
    }

    @After
    fun tearDown() = db.close()

    /** 排程期台账原貌：deliveredAt=null、正文空串（正文到点才现做）。 */
    private fun scheduledRecord(
        deliveryId: String,
        characterId: String = "char-1",
        body: String = "",
        deliveredAt: Long? = null,
    ) = NotificationDeliveryRecordEntity(
        id = "rec-$deliveryId",
        characterId = characterId,
        category = "schedule_0",
        deliveryIdentifier = deliveryId,
        requestIdentifier = "aichat_streak_${characterId}_schedule_0",
        conversationUuid = "conv-1",
        notificationBody = body,
        windowId = "1200-1230",
        windowStartMinute = 1200,
        windowEndMinute = 1230,
        scheduledAt = 1_000L,
        deliveredAt = deliveredAt,
    )

    @Test
    fun `markDelivered 双写 deliveredAt 与正文`() = runBlocking {
        dao.upsert(scheduledRecord("d-1"))

        dao.markDelivered("d-1", 5_000L, "今天忙完了，想起你")

        val after = dao.getByDeliveryIdentifier("d-1")!!
        assertEquals("deliveredAt 置为传入时刻", 5_000L, after.deliveredAt)
        assertEquals("正文同写（否则查重闸比不到东西）", "今天忙完了，想起你", after.notificationBody)
    }

    @Test
    fun `markDelivered 二次调用不覆盖既有值`() = runBlocking {
        dao.upsert(scheduledRecord("d-1"))
        dao.markDelivered("d-1", 5_000L, "首次投递的正文")

        dao.markDelivered("d-1", 9_999L, "第二次的正文")

        val after = dao.getByDeliveryIdentifier("d-1")!!
        assertEquals("deliveredAt 守卫生效，保留首值", 5_000L, after.deliveredAt)
        assertEquals("正文一并保留首值", "首次投递的正文", after.notificationBody)
    }

    @Test
    fun `markDelivered 对已有 deliveredAt 的行零改（与 drain 回灌幂等互斥）`() = runBlocking {
        // 回前台 drain 已先行置位的行：本 UPDATE 必须一个字节都不动它。
        dao.upsert(scheduledRecord("d-1", body = "drain 回灌的正文", deliveredAt = 3_000L))

        dao.markDelivered("d-1", 7_000L, "worker 想写的正文")

        val after = dao.getByDeliveryIdentifier("d-1")!!
        assertEquals(3_000L, after.deliveredAt)
        assertEquals("drain 回灌的正文", after.notificationBody)
    }

    @Test
    fun `markDelivered 未命中的 deliveryIdentifier 零行无异常`() = runBlocking {
        dao.upsert(scheduledRecord("d-1"))

        dao.markDelivered("不存在的-id", 5_000L, "无处安放的正文")

        assertNull("不存在的行不会被凭空建出来", dao.getByDeliveryIdentifier("不存在的-id"))
        assertNull("既有行不受影响", dao.getByDeliveryIdentifier("d-1")!!.deliveredAt)
    }

    @Test
    fun `markDelivered 只动指定 deliveryIdentifier 那一行`() = runBlocking {
        dao.upsert(scheduledRecord("d-1"))
        dao.upsert(scheduledRecord("d-2"))

        dao.markDelivered("d-1", 5_000L, "只给 d-1 的正文")

        assertEquals(5_000L, dao.getByDeliveryIdentifier("d-1")!!.deliveredAt)
        assertNull("同角色另一条台账不受牵连", dao.getByDeliveryIdentifier("d-2")!!.deliveredAt)
        assertEquals("", dao.getByDeliveryIdentifier("d-2")!!.notificationBody)
    }

    @Test
    fun `markDelivered 置位后即被三闸查询看见`() = runBlocking {
        // 端到端口径钉：连发/降频闸读 countDeliveredSince、查重闸读 recentDeliveredBodies，
        // 二者谓词均含 deliveredAt IS NOT NULL —— 置位前后由 0 变 1 才算真的「解了失明」。
        dao.upsert(scheduledRecord("d-1"))
        assertEquals("置位前三闸看不见", 0, dao.countDeliveredSince("char-1", 0L))

        dao.markDelivered("d-1", 5_000L, "现做的这条")

        assertEquals("置位后连发/降频闸计数为 1", 1, dao.countDeliveredSince("char-1", 0L))
        assertEquals("查重闸能比到正文", listOf("现做的这条"), dao.recentDeliveredBodies("char-1", 3))
    }
}
