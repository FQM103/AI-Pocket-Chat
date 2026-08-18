package com.situ.aichat.world.link

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldMemoryDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldMemoryEntity
import com.situ.aichat.prompt.memory.TextEmbedder
import com.situ.aichat.prompt.memory.VectorMemoryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldChatContextProvider.memoryLines] 行为测试（#1 修复验证）：世界联动记忆检索的 CPU 密集段
 * （ONNX 嵌入 + 反序列化 + 余弦相似度）必须在**后台线程**执行，绝不占聊天回合所在的主线程；同时行为
 * （命中记忆被检索并渲染）与原实现等价。断言从设计独立反推（MockK 假掉嵌入器 / 向量服务 / DAO）。
 */
class WorldChatContextMemoryLinesTest {

    private val worldDao = mockk<WorldDao>(relaxed = true)
    private val socialDao = mockk<WorldSocialDao>(relaxed = true)
    private val memoryDao = mockk<WorldMemoryDao>()
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val embedder = mockk<TextEmbedder>()
    private val vectorService = mockk<VectorMemoryService>()

    private val provider = WorldChatContextProvider(worldDao, socialDao, memoryDao, characterDao, embedder, vectorService)

    @Test fun vectorRetrieval_runsOnBackgroundThread_andReturnsMatch() = runTest {
        val character = CharacterEntity(uuid = "c1", name = "小满", creationDate = 0L)
        val mem = mockk<WorldMemoryEntity> {
            every { uuid } returns "m1"
            every { content } returns "我们一起看了海"
            every { happenedAt } returns 1_700_000_000_000L
            every { embedding } returns byteArrayOf(1, 2, 3)
        }
        coEvery { memoryDao.recentForCharacter(any(), any()) } returns emptyList()
        coEvery { memoryDao.embeddedForCharacter("c1") } returns listOf(mem)

        // 记录嵌入器被调用时所在线程，验证已切到后台 Default 池。
        val embedThread = AtomicReference<String?>()
        every { embedder.isAvailable } answers { embedThread.set(Thread.currentThread().name); true }
        every { embedder.embed("今天想去海边") } answers {
            embedThread.set(Thread.currentThread().name)
            floatArrayOf(0.1f, 0.2f)
        }
        every { vectorService.deserializeEmbedding(any()) } returns floatArrayOf(0.1f, 0.2f)
        every { vectorService.cosineSimilarity(any(), any()) } returns 0.9

        val lines = provider.memoryLines(character, "今天想去海边", 1_700_000_100_000L, ZoneId.of("Asia/Shanghai"))

        // 行为等价：命中的世界记忆被检索出并渲染成一行。
        assertEquals(1, lines.size)
        assertTrue("检索结果应含命中记忆内容，实为 ${lines.firstOrNull()}", lines[0].contains("我们一起看了海"))

        // #1 修复核心：向量运算在后台 Default 线程执行，不占聊天回合的主线程（Main.immediate）。
        assertNotNull("嵌入器应被调用", embedThread.get())
        assertTrue(
            "向量运算应在 Default 后台线程执行，实为 ${embedThread.get()}",
            embedThread.get()!!.contains("DefaultDispatcher"),
        )
    }

    /** 行为等价关键分支：近层去重 ∪ 阈值剔除 ∪ 相似度降序 ∪ take(3) 上限——逐条覆盖，防"纯 wrap"claim 名不副实。 */
    @Test fun vectorRetrieval_dedupsThresholdSortsAndCapsAtLimit() = runTest {
        val character = CharacterEntity(uuid = "c1", name = "小满", creationDate = 0L)
        val n1 = worldMemory("n1", "近三天：一起吃了火锅", embByte = 1)
        coEvery { memoryDao.recentForCharacter(any(), any()) } returns listOf(n1)
        coEvery { memoryDao.embeddedForCharacter("c1") } returns listOf(
            n1, // 也在 near 里 → 应按 uuid 去重、不重复出现
            worldMemory("m2", "海边看日落", embByte = 2),
            worldMemory("m3", "无关的琐事", embByte = 3),
            worldMemory("m4", "养了一只猫", embByte = 4),
            worldMemory("m5", "学会了游泳", embByte = 5),
            worldMemory("m6", "第四个够格但被上限截掉", embByte = 6),
        )
        every { embedder.isAvailable } returns true
        every { embedder.embed(any()) } returns floatArrayOf(0f)
        every { vectorService.deserializeEmbedding(any()) } answers { floatArrayOf(firstArg<ByteArray>()[0].toFloat()) }
        every { vectorService.cosineSimilarity(any(), any()) } answers {
            when (secondArg<FloatArray>()[0]) {
                1f -> 0.95 // n1：相似度够，但应被 near 去重剔除
                2f -> 0.90 // m2 命中
                3f -> 0.50 // m3：低于 0.65 阈值 → 剔除
                4f -> 0.80 // m4 命中
                5f -> 0.70 // m5 命中
                6f -> 0.66 // m6 命中，但被 take(3) 截掉
                else -> 0.0
            }
        }

        val lines = provider.memoryLines(character, "想去海边", 1_700_000_100_000L, ZoneId.of("Asia/Shanghai"))

        // near(n1) 在前，向量层按相似度降序取前 3（m2>m4>m5）。
        assertEquals(4, lines.size)
        assertTrue(lines[0].contains("一起吃了火锅"))
        assertTrue(lines[1].contains("海边看日落"))
        assertTrue(lines[2].contains("养了一只猫"))
        assertTrue(lines[3].contains("学会了游泳"))
        assertFalse("低于阈值应剔除", lines.any { it.contains("无关的琐事") })
        assertFalse("超出 take(3) 上限应截掉", lines.any { it.contains("被上限截掉") })
        assertEquals("n1 只应作为 near 出现一次，不因也在 embedded 里而重复", 1, lines.count { it.contains("一起吃了火锅") })
    }

    /** 门控分支：query 空白 → 只有近层、绝不进 withContext、绝不碰嵌入器/向量扫描。 */
    @Test fun blankQuery_returnsNearOnly_andSkipsEmbedder() = runTest {
        val character = CharacterEntity(uuid = "c1", name = "小满", creationDate = 0L)
        coEvery { memoryDao.recentForCharacter(any(), any()) } returns listOf(worldMemory("n1", "近况一条", embByte = 1))

        val lines = provider.memoryLines(character, "   ", 1_700_000_100_000L, ZoneId.of("Asia/Shanghai"))

        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("近况一条"))
        verify(exactly = 0) { embedder.isAvailable }
        verify(exactly = 0) { embedder.embed(any()) }
        coVerify(exactly = 0) { memoryDao.embeddedForCharacter(any()) }
    }

    /** 门控分支：embed 返回 null（嵌入失败）→ 只有近层、不扫描向量。 */
    @Test fun embedReturnsNull_returnsNearOnly() = runTest {
        val character = CharacterEntity(uuid = "c1", name = "小满", creationDate = 0L)
        coEvery { memoryDao.recentForCharacter(any(), any()) } returns listOf(worldMemory("n1", "近况一条", embByte = 1))
        every { embedder.isAvailable } returns true
        every { embedder.embed(any()) } returns null

        val lines = provider.memoryLines(character, "有效查询", 1_700_000_100_000L, ZoneId.of("Asia/Shanghai"))

        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("近况一条"))
        coVerify(exactly = 0) { memoryDao.embeddedForCharacter(any()) }
    }

    private fun worldMemory(id: String, text: String, embByte: Int) = mockk<WorldMemoryEntity> {
        every { uuid } returns id
        every { content } returns text
        every { happenedAt } returns 1_700_000_000_000L
        every { embedding } returns byteArrayOf(embByte.toByte())
    }
}
