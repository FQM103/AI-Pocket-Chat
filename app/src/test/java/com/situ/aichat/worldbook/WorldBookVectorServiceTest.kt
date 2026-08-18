package com.situ.aichat.worldbook

import com.situ.aichat.data.local.dao.WorldBookDao
import com.situ.aichat.prompt.memory.VectorMemoryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链接条目向量匹配 T2（WB5·MockK）：阈值门 / 相似度判定 / 缺嵌入现场补嵌落库 /
 * 签名漂移重嵌 / 嵌入器不可用优雅降级。序列化与余弦用二维单位向量的功能性假实现（语义等价）。
 */
class WorldBookVectorServiceTest {

    private val dao = mockk<WorldBookDao> {
        coEvery { updateEntryEmbedding(any(), any(), any()) } just runs
    }

    private val vm = mockk<VectorMemoryService> {
        every { serializeEmbedding(any()) } answers {
            firstArg<FloatArray>().joinToString(",").toByteArray()
        }
        every { deserializeEmbedding(any()) } answers {
            String(firstArg<ByteArray>()).split(",").map { it.toFloat() }.toFloatArray()
        }
        every { cosineSimilarity(any(), any()) } answers {
            val a = firstArg<FloatArray>()
            val b = secondArg<FloatArray>()
            (a[0] * b[0] + a[1] * b[1]).toDouble()
        }
    }

    private val service = WorldBookVectorService(vm, dao)

    private val currentSig = VectorMemoryService.MODEL_SIGNATURE

    private fun bytes(x: Float, y: Float) = floatArrayOf(x, y).joinToString(",").toByteArray()

    @Test
    fun 阈值0_直接空且不算查询嵌入() = runBlocking {
        val e = wbEntry("e1", vectorized = true)
        assertTrue(service.matchedEntryUuids(listOf(e), "查询", 0).isEmpty())
        coVerify(exactly = 0) { vm.generateEmbedding(any()) }
    }

    @Test
    fun 相似达标命中_不达标落选() = runBlocking {
        coEvery { vm.generateEmbedding("查询文本") } returns floatArrayOf(1f, 0f)
        val hit = wbEntry("命中", vectorized = true)
            .copy(embedding = bytes(1f, 0f), embeddingSignature = currentSig)
        val miss = wbEntry("落选", vectorized = true)
            .copy(embedding = bytes(0f, 1f), embeddingSignature = currentSig)

        val matched = service.matchedEntryUuids(listOf(hit, miss), "查询文本", 60)
        assertEquals(setOf(hit.uuid), matched)
    }

    @Test
    fun 缺嵌入_现场补嵌并落库() = runBlocking {
        coEvery { vm.generateEmbedding("查询文本") } returns floatArrayOf(1f, 0f)
        val e = wbEntry("e1", vectorized = true, comment = "灵田", content = "灵田在后山")
        coEvery { vm.generateEmbedding("灵田\n灵田在后山") } returns floatArrayOf(1f, 0f)

        val matched = service.matchedEntryUuids(listOf(e), "查询文本", 60)

        assertEquals(setOf("e1"), matched)
        coVerify { dao.updateEntryEmbedding("e1", any(), currentSig) }
    }

    @Test
    fun 签名漂移_现场重嵌覆盖旧向量() = runBlocking {
        coEvery { vm.generateEmbedding("查询文本") } returns floatArrayOf(1f, 0f)
        val stale = wbEntry("e1", vectorized = true, comment = "灵田", content = "灵田在后山")
            .copy(embedding = bytes(0f, 1f), embeddingSignature = "旧模型签名")
        coEvery { vm.generateEmbedding("灵田\n灵田在后山") } returns floatArrayOf(1f, 0f)

        val matched = service.matchedEntryUuids(listOf(stale), "查询文本", 60)

        assertEquals("漂移后须按新嵌入判定（旧向量是反例）", setOf("e1"), matched)
        coVerify { dao.updateEntryEmbedding("e1", any(), currentSig) }
    }

    @Test
    fun 嵌入器不可用_优雅降级不落库() = runBlocking {
        coEvery { vm.generateEmbedding("查询文本") } returns floatArrayOf(1f, 0f)
        val e = wbEntry("e1", vectorized = true, comment = "灵田", content = "灵田在后山")
        coEvery { vm.generateEmbedding("灵田\n灵田在后山") } returns null

        assertTrue(service.matchedEntryUuids(listOf(e), "查询文本", 60).isEmpty())
        coVerify(exactly = 0) { dao.updateEntryEmbedding(any(), any(), any()) }
    }

    @Test
    fun 查询嵌入不可用_整轮空() = runBlocking {
        coEvery { vm.generateEmbedding("查询文本") } returns null
        val e = wbEntry("e1", vectorized = true)
        assertTrue(service.matchedEntryUuids(listOf(e), "查询文本", 60).isEmpty())
    }
}
