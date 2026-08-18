package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.MessageEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计 P1 行为测试——「后台向量 backfill 不再幽灵刷新聊天窗口」的两根支柱：
 * 1. [MessageRepository.observeVisibleWindowed] 出口剥离 embedding（UI 窗口不背 blob）；
 *    剥离后「仅 embedding 有差」的两次 DB 重查询产出 **equals 相等** 的列表 → 下游 StateFlow 去重吞掉。
 * 2. [MessageEntity] equals 升级为全字段结构比较（窄版会把 isContentRevealed / audio 列等
 *    「插入后更新」的刷新静默吞掉——那才是真该重组的）。断言从规格独立反推。
 */
class MessageWindowProjectionTest {

    private fun message(
        uuid: String = "m1",
        content: String = "你好",
        embedding: ByteArray? = null,
        isContentRevealed: Boolean = true,
        audioRelativePath: String? = null,
    ) = MessageEntity(
        messageUUID = uuid,
        conversationUuid = "conv-1",
        roleRaw = "assistant",
        content = content,
        timestamp = 1_000L,
        embedding = embedding,
        isContentRevealed = isContentRevealed,
        audioRelativePath = audioRelativePath,
    )

    // ────────────── 支柱 1：出口剥离 ──────────────

    @Test
    fun `窗口流剥离embedding_backfill前后两次查询产出相等列表`() = runBlocking {
        val dao = mockk<MessageDao>()
        val before = listOf(message(embedding = null))
        val after = listOf(message(embedding = byteArrayOf(1, 2, 3))) // backfill 只写了向量列
        every { dao.observeVisibleWindowed("conv-1", 50) } returns flowOf(before, after)

        val emissions = MessageRepository(dao).observeVisibleWindowed("conv-1", 50).toList()

        assertEquals(2, emissions.size)
        assertNull(emissions[1].single().embedding) // blob 不进 UI 窗口
        // 关键：剥离后两次发射 equals 相等 → stateIn 的 StateFlow 去重会吞掉第二次（幽灵刷新根除）。
        assertEquals(emissions[0], emissions[1])
    }

    @Test
    fun `窗口流保持DESC转ASC顺序不变`() = runBlocking {
        val dao = mockk<MessageDao>()
        val newest = message(uuid = "m-new").copy(timestamp = 2_000L)
        val oldest = message(uuid = "m-old")
        every { dao.observeVisibleWindowed("conv-1", 50) } returns flowOf(listOf(newest, oldest)) // DAO 出 DESC

        val window = MessageRepository(dao).observeVisibleWindowed("conv-1", 50).toList().single()

        assertEquals(listOf("m-old", "m-new"), window.map { it.messageUUID }) // 显示序 ASC
    }

    // ────────────── 支柱 2：全字段 equals ──────────────

    @Test
    fun `equals覆盖插入后可变列_窄版会吞掉的刷新现在判不等`() {
        val base = message()
        // 旧窄版 equals 只比 uuid/timestamp/content/embedding——以下三组在旧版下「相等」，会吞掉真实 UI 变化：
        assertNotEquals(base, base.copy(isContentRevealed = false)) // B1 占位变身位
        assertNotEquals(base, base.copy(audioRelativePath = "a.mp3")) // 语音列
        assertNotEquals(base, base.copy(isHeldForDelivery = true)) // 忙碌暂扣位
        // 内容字段照常参与：
        assertNotEquals(base, base.copy(content = "改了"))
        // 全同 → 相等（含双 null embedding）。
        assertEquals(base, message())
    }

    @Test
    fun `embedding按内容比较_同内容不同实例相等`() {
        val a = message(embedding = byteArrayOf(7, 8))
        val b = message(embedding = byteArrayOf(7, 8))
        assertEquals(a, b) // 数组内容比较而非引用
        assertNotEquals(a, message(embedding = byteArrayOf(9)))
        assertNotEquals(a, message(embedding = null))
        assertTrue(a.hashCode() == b.hashCode())
    }
}
