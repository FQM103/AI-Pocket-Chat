package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.repository.StoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * 圣经结构化压缩编排行为测试 T2（长篇稳定性 L1·契约 FABLE5_STORY_LONGFORM_STABILITY_PROPOSAL §3/§8）。
 * MockK 假掉 LlmClient / StoryRepository，切分/触发/prompt 纯逻辑真跑。验证：
 * 未触发零副作用（老故事/短篇无感）、成功写回（档案+尾段+水位线）、失败熔断（2 次锁定·成功复位·取消不计）。
 */
class StoryBibleCompressorTest {

    private lateinit var llmClient: LlmClient
    private lateinit var storyRepository: StoryRepository
    private lateinit var compressor: StoryBibleCompressor

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "m",
    )

    /** 1-14 章逐章流水账（跨 12 章、超字数阈值 → 必触发）。 */
    private fun longBible(): String = (1..14).joinToString("\n") { n ->
        "第${n}章角色：主角（状态$n·${"细节".repeat(40)}）\n第${n}章伏笔：悬念$n（${"线索".repeat(40)}）"
    }

    @Before
    fun setUp() {
        llmClient = mockk()
        storyRepository = mockk()
        compressor = StoryBibleCompressor(llmClient, storyRepository)
        coEvery { storyRepository.updateCompressedBible(any(), any(), any()) } just Runs
    }

    private fun stubStory(bible: String?, watermark: Int? = null) {
        coEvery { storyRepository.getStory(any()) } answers {
            StoryEntity(id = firstArg(), title = "书", storyBible = bible, lastBibleCompressedAtChapter = watermark)
        }
    }

    private fun compress(storyId: String = "s1", latest: Int = 14, cfg: ApiConfigValues? = config) =
        runBlocking { compressor.compressIfNeeded(storyId, latest, cfg) }

    @Test
    fun `短篇未触发_零LLM零写库`() {
        stubStory((1..8).joinToString("\n") { "第${it}章角色：主角（状态$it）" })
        compress(latest = 8)
        coVerify(exactly = 0) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { storyRepository.updateCompressedBible(any(), any(), any()) }
    }

    @Test
    fun `无结构化配置直接跳过_不读库`() {
        compress(cfg = null)
        coVerify(exactly = 0) { storyRepository.getStory(any()) }
    }

    @Test
    fun `触发且成功_写回档案加尾段_水位线为最新章减5`() {
        stubStory(longBible())
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns " 【角色档案】\n- 主角｜…｜最后出场：第9章 "
        val bibleSlot = slot<String>()
        coEvery { storyRepository.updateCompressedBible("s1", capture(bibleSlot), 9) } just Runs

        compress(latest = 14)

        coVerify(exactly = 1) { storyRepository.updateCompressedBible("s1", any(), 9) }
        val written = bibleSlot.captured
        // 档案在前（已 trim），近 5 章（10-14）原始行保留在尾段
        assertTrue(written.startsWith("【角色档案】"))
        assertTrue(written.contains("第10章角色："))
        assertTrue(written.contains("第14章伏笔："))
        // 已压缩范围（≤9 章）的原始行不再出现
        assertTrue(!written.contains("第9章角色："))
        assertTrue(!written.contains("第1章伏笔："))
    }

    /** 非流式 completion 不剥内联 <think>——storyBible 落库后逐章回注 prompt，写回前必须剥净。 */
    @Test
    fun `压缩结果剥净think标签后写回`() {
        stubStory(longBible())
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns
            "<think>先合并主角状态。</think>【角色档案】\n- 主角｜…｜最后出场：第9章"
        val bibleSlot = slot<String>()
        coEvery { storyRepository.updateCompressedBible("s1", capture(bibleSlot), 9) } just Runs

        compress(latest = 14)

        assertTrue(bibleSlot.captured.startsWith("【角色档案】"))
        assertTrue("思考标签不得写进圣经", !bibleSlot.captured.contains("<think>"))
        assertTrue("思考正文不得写进圣经", !bibleSlot.captured.contains("先合并主角状态"))
    }

    @Test
    fun `发给LLM的prompt含基底与待压缩行_不含保留尾段行`() {
        stubStory(longBible())
        var seenPrompt = ""
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } answers {
            seenPrompt = firstArg<List<com.situ.aichat.data.remote.llm.ChatMessageDto>>().first().content.orEmpty()
            "【角色档案】\n- 主角｜…"
        }
        compress(latest = 14)
        assertTrue(seenPrompt.contains("第6章角色："))
        assertTrue(seenPrompt.contains("截至第9章"))
        assertTrue(!seenPrompt.contains("第10章角色："))
    }

    @Test
    fun `连续失败两次后熔断_第三次不再烧LLM`() {
        stubStory(longBible())
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("boom")
        compress(); compress(); compress()
        coVerify(exactly = 2) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { storyRepository.updateCompressedBible(any(), any(), any()) }
    }

    @Test
    fun `空返回计一次失败_成功即复位计数`() {
        stubStory(longBible())
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns "   "
        compress() // 失败 1
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns "【角色档案】\n- 主角｜…"
        compress() // 成功 → 复位
        coVerify(exactly = 1) { storyRepository.updateCompressedBible(any(), any(), any()) }
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("boom")
        compress(); compress(); compress() // 复位后重新计 2 次才熔断
        coVerify(exactly = 4) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
    }

    /** 截断防线（记忆护栏 G2 同款）：finish_reason=length（升额后仍被掐断，压缩现走创作槽·思考模型尤易）→
     * 非空的半截档案绝不落库回喂（会丢角色/伏笔且自我强化）。 */
    @Test
    fun `截断结果不落库`() {
        stubStory(longBible())
        // completion 回调 onFinishReason("length")（末位实参）后返回非空半截档案。
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            lastArg<((String?) -> Unit)?>()?.invoke("length")
            "【角色档案】\n- 主角｜…（半截被掐断"
        }
        compress()
        coVerify(exactly = 0) { storyRepository.updateCompressedBible(any(), any(), any()) }
    }

    /** 思考模型专属额度（改动 B 圣经侧·与摘要侧 eq(7200) 对称）：config.isThinkingModel=true →
     * maxTokens = base 2800 × 3 = 8400（回退成写死会被本断言抓住）。 */
    @Test
    fun `思考模型_圣经压缩额度按x3放大`() {
        stubStory(longBible())
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns "【角色档案】\n- 主角｜…"
        compress(cfg = config.copy(isThinkingModel = true))
        coVerify(exactly = 1) {
            llmClient.completion(any(), any(), any(), eq(8_400), any(), any(), any())
        }
    }

    /**
     * 协程取消：不计失败（既有语义）+ **如实重抛**（卷一 chunk 3 语义升级——原来吞掉不抛，
     * 承载它的协程会带着「已取消」的身份继续跑、日志还把取消记成一次失败）。
     */
    @Test
    fun `协程取消不计失败_且如实重抛`() {
        stubStory(longBible())
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } throws CancellationException("取消")
        assertThrows(CancellationException::class.java) { compress() }
        assertThrows(CancellationException::class.java) { compress() }
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("boom")
        compress() // 两次取消没进熔断计数 ⇒ 未被锁定，仍尝试
        coVerify(exactly = 3) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { storyRepository.updateCompressedBible(any(), any(), any()) }
    }

    @Test
    fun `待压缩段为空时跳过_即便尾段很大`() {
        // 全部行都在近 5 章（10-14）→ compressLines 空 → 不烧 LLM
        val recentOnly = (10..14).joinToString("\n") { n ->
            "第${n}章角色：主角（状态$n·${"细节".repeat(60)}）\n第${n}章伏笔：悬念$n（${"线索".repeat(60)}）"
        }
        stubStory(recentOnly)
        compress(latest = 14)
        coVerify(exactly = 0) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `不同故事熔断互不影响`() {
        stubStory(longBible())
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("boom")
        compress("s1"); compress("s1") // s1 锁定
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns "【角色档案】\n- 主角｜…"
        compress("s2")
        coVerify(exactly = 1) { storyRepository.updateCompressedBible("s2", any(), any()) }
        compress("s1") // 仍锁定
        coVerify(exactly = 3) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `二次压缩_水位线之后才算新料`() {
        // 已压缩至 9（基底=档案），尾段 10-21 章 → latest=21：21-9=12 触发，压缩至 16
        val bible = "【角色档案】\n- 主角｜…｜最后出场：第9章\n\n" + (10..21).joinToString("\n") { n ->
            "第${n}章角色：主角（状态$n·${"细节".repeat(40)}）\n第${n}章伏笔：悬念$n（${"线索".repeat(40)}）"
        }
        stubStory(bible, watermark = 9)
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns "【角色档案】\n- 主角｜…｜最后出场：第16章"
        compress(latest = 21)
        coVerify(exactly = 1) { storyRepository.updateCompressedBible("s1", any(), 16) }
    }

    // ── T2 四条件采纳闸（圣经压缩保真优化 C3·图纸 §3.4/§5 E5-E6/E3）──

    /** 已压缩至 9 章的圣经：基底是三段制档案（点名册 = 林晚），尾段 10-21 章 → latest=21 必触发，压缩至 16。 */
    private fun tieredBible(): String = "【主要角色】\n- 林晚｜女主·画廊主理人｜与陈默冷战｜最后出场：第9章\n\n" +
        (10..21).joinToString("\n") { n ->
            "第${n}章角色：林晚（状态$n·${"细节".repeat(40)}）\n第${n}章伏笔：悬念$n（${"线索".repeat(40)}）"
        }

    /** T2-1/E5：压缩产物把点名册里的角色弄丢 → 整份拒收、保旧圣经、计失败；连败 2 次熔断。 */
    @Test
    fun `点名对账丢人_整份拒收保旧圣经_两次后熔断`() {
        stubStory(tieredBible(), watermark = 9)
        // 假输出「整理」得只剩男主，把林晚整行弄丢了
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns
            "【主要角色】\n- 陈默｜男主·摄影师｜准备摊牌｜最后出场：第20章"

        compress(latest = 21); compress(latest = 21); compress(latest = 21)

        coVerify(exactly = 0) { storyRepository.updateCompressedBible(any(), any(), any()) }
        // 连败 2 次即熔断，第三次不再烧 LLM
        coVerify(exactly = 2) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
    }

    /** T2-2/E6：超 5000 字（= 2500 上限 ×2）拒收；恰 5000 字放行（±1 精度）。 */
    @Test
    fun `超长产物拒收_拒收线为上限两倍且恰好边界放行`() {
        assertEquals(5_000, StoryBibleCompression.ARCHIVE_REJECT_CHAR_LIMIT)
        stubStory(longBible()) // 基底为空（纯逐章行）→ 点名册空，隔离出「超长」这唯一拒因
        val over = "【主要角色】" + "字".repeat(5_001 - "【主要角色】".length)
        assertEquals(5_001, over.length)
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns over
        compress(latest = 14)
        coVerify(exactly = 0) { storyRepository.updateCompressedBible(any(), any(), any()) }

        val exact = "【主要角色】" + "字".repeat(5_000 - "【主要角色】".length)
        assertEquals(5_000, exact.length)
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns exact
        compress(latest = 14)
        coVerify(exactly = 1) { storyRepository.updateCompressedBible("s1", any(), 9) }
    }

    /** T2-3：点名齐全且长度合规 → 照旧写回（档案 + 尾段 + 水位线）。 */
    @Test
    fun `点名齐全且长度合规_正常写回_水位线为最新章减5`() {
        stubStory(tieredBible(), watermark = 9)
        val bibleSlot = slot<String>()
        coEvery { storyRepository.updateCompressedBible("s1", capture(bibleSlot), 16) } just Runs
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns
            "【主要角色】\n- 林晚｜女主·画廊主理人｜重新开画展｜最后出场：第16章"

        compress(latest = 21)

        coVerify(exactly = 1) { storyRepository.updateCompressedBible("s1", any(), 16) }
        val written = bibleSlot.captured
        assertTrue(written.startsWith("【主要角色】"))
        assertTrue(written.contains("林晚"))
        assertTrue(written.contains("第17章角色："))  // 近 5 章（17-21）原始行留尾段
        assertTrue(written.contains("第21章伏笔："))
        assertTrue(!written.contains("第16章角色："))  // 已压缩范围不再出现
    }

    /** T2-4/E3：用户手编无段头笔记做基底 → 点名册空（fail-open）→ 对账空过，产物照常采纳。 */
    @Test
    fun `手编无段头基底_点名册空_压缩产物直接采纳`() {
        val handwritten = "我自己记的：\n- 苏晴｜其实是卧底｜别忘了\n\n" +
            (10..21).joinToString("\n") { n ->
                "第${n}章角色：主角（状态$n·${"细节".repeat(40)}）\n第${n}章伏笔：悬念$n（${"线索".repeat(40)}）"
            }
        stubStory(handwritten, watermark = 9)
        // 产物完全没提「苏晴」，但手编行不入册 → 不该被闸拦下
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns
            "【主要角色】\n- 林晚｜女主｜最后出场：第16章"

        compress(latest = 21)

        coVerify(exactly = 1) { storyRepository.updateCompressedBible("s1", any(), 16) }
    }

    @Test
    fun `失败时旧圣经零触碰`() {
        stubStory(longBible())
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("boom")
        compress()
        // 无写库即旧值保留（storyBible 列级写只此一处）
        coVerify(exactly = 0) { storyRepository.updateCompressedBible(any(), any(), any()) }
    }
}
