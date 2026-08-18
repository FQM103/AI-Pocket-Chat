package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上一版」快照纯函数 T1（阅读器掌控力 C3·图纸三 §7 T1-1）：12 字段完整性 / 编解码往返 / 损坏槽兜底（E6）/
 * 互换对合（E5）/ 布尔折叠。
 *
 * 断言从图纸 §3.1 规格**独立反推**，不照抄实现：
 * - 12 字段名单来自「实体 16 列减 5 个轨道字段」这条规则，逐个点名比对；
 * - 「互换」的语义是**两版在同一个槽里对调**，因此 swap 两次必须回到起点（数学上的对合），这条不看实现怎么写；
 * - 损坏槽的期望来自 E6「视同无槽、绝不崩」。
 */
class StoryChapterDraftTest {

    private fun chapter(
        id: String = "ch-1",
        title: String = "第七章",
        content: String = "正文哨兵",
        hasChoice: Boolean = true,
        aiSuggestedEnding: Boolean = true,
        previousDraftJson: String? = null,
    ) = StoryChapterEntity(
        id = id,
        storyId = "story-1",
        chapterNumber = 7,
        title = title,
        teaser = "引子哨兵",
        createdAt = 1_700_000_000_000L,
        content = content,
        mood = "tense",
        scenes = "场景哨兵",
        hasChoice = hasChoice,
        choicePrompt = "你决定",
        choiceOptions = """["A","B"]""",
        userChoice = "选项A",
        choiceMadeAt = 1_700_000_001_000L,
        aiSuggestedEnding = aiSuggestedEnding,
        chapterSummary = "小结哨兵",
        unlockAt = 1_700_000_002_000L,
        previousDraftJson = previousDraftJson,
    )

    // ── fromEntity：12 个内容字段一个不少 ──

    @Test
    fun fromEntity_十二个内容字段逐个拷入() {
        val d = StoryChapterDraft.fromEntity(chapter())

        assertEquals("第七章", d.title)
        assertEquals("引子哨兵", d.teaser)
        assertEquals("正文哨兵", d.content)
        assertEquals("tense", d.mood)
        assertEquals("场景哨兵", d.scenes)
        assertEquals(true, d.hasChoice)
        assertEquals("你决定", d.choicePrompt)
        assertEquals("""["A","B"]""", d.choiceOptions)
        assertEquals("选项A", d.userChoice)
        assertEquals(1_700_000_001_000L, d.choiceMadeAt)
        assertEquals(true, d.aiSuggestedEnding)
        assertEquals("小结哨兵", d.chapterSummary)
    }

    /** 轨道字段（章在书里的位置与身份）不许进槽——否则「换回上一版」会把章号/创建时间一起换回去。 */
    @Test
    fun 轨道字段不进槽() {
        val encoded = StoryChapterDraft.encode(StoryChapterDraft.fromEntity(chapter()))

        assertTrue("槽里不许出现 id", !encoded.contains("\"id\""))
        assertTrue("槽里不许出现 storyId", !encoded.contains("storyId"))
        assertTrue("槽里不许出现 chapterNumber", !encoded.contains("chapterNumber"))
        assertTrue("槽里不许出现 createdAt", !encoded.contains("createdAt"))
        assertTrue("槽里不许出现 unlockAt", !encoded.contains("unlockAt"))
    }

    // ── encode / decode ──

    @Test
    fun 编解码往返十二字段无损() {
        val original = StoryChapterDraft.fromEntity(chapter())

        val back = StoryChapterDraft.decode(StoryChapterDraft.encode(original))

        assertEquals(original, back)
    }

    @Test
    fun 空槽解码得null() {
        assertNull(StoryChapterDraft.decode(null))
        assertNull(StoryChapterDraft.decode(""))
    }

    /** E6：槽里是半截/乱码 JSON → 返 null（调用方视同无槽隐藏入口），绝不抛。 */
    @Test
    fun E6_损坏槽解码得null不抛() {
        assertNull(StoryChapterDraft.decode("{\"title\":\"没闭合"))
        assertNull(StoryChapterDraft.decode("这压根不是 JSON"))
        assertNull(StoryChapterDraft.decode("[1,2,3]"))
    }

    /** 老槽少写了几个键（将来字段增删/半截写入）→ 缺的落 null，已有的照读，不炸。 */
    @Test
    fun 缺键的老槽照样解码() {
        val d = StoryChapterDraft.decode("""{"title":"只有标题"}""")

        assertEquals("只有标题", d?.title)
        assertNull(d?.content)
        assertNull(d?.hasChoice)
    }

    /** 多出未知键（将来版本写的槽被老代码读到）→ ignoreUnknownKeys 兜住。 */
    @Test
    fun 未知键不影响解码() {
        val d = StoryChapterDraft.decode("""{"title":"标题","futureField":"将来才有的"}""")

        assertEquals("标题", d?.title)
    }

    // ── swapApplied ──

    @Test
    fun 互换_内容字段取上一版_槽换成互换前的当前章() {
        val old = StoryChapterDraft.fromEntity(chapter(title = "旧标题", content = "旧正文", hasChoice = false))
        val current = chapter(title = "新标题", content = "新正文", previousDraftJson = StoryChapterDraft.encode(old))

        val swapped = StoryChapterDraft.swapApplied(current, old)

        assertEquals("正文换成上一版", "旧正文", swapped.content)
        assertEquals("标题换成上一版", "旧标题", swapped.title)
        assertEquals("选项态一并换回", false, swapped.hasChoice)
        assertEquals(
            "槽里换成互换前的当前章",
            StoryChapterDraft.fromEntity(current),
            StoryChapterDraft.decode(swapped.previousDraftJson),
        )
    }

    /** 轨道字段在互换中恒不动（章号/创建时间/解锁时间/身份）。 */
    @Test
    fun 互换_轨道字段恒不动() {
        val old = StoryChapterDraft.fromEntity(chapter(title = "旧标题", content = "旧正文"))
        val current = chapter(title = "新标题", previousDraftJson = StoryChapterDraft.encode(old))

        val swapped = StoryChapterDraft.swapApplied(current, old)

        assertEquals("ch-1", swapped.id)
        assertEquals("story-1", swapped.storyId)
        assertEquals(7, swapped.chapterNumber)
        assertEquals(1_700_000_000_000L, swapped.createdAt)
        assertEquals(1_700_000_002_000L, swapped.unlockAt)
    }

    /**
     * E5 对合：连换两次必须逐字段回到起点（含槽本身）——这正是「两版永不丢、可反复来回切」的数学表述。
     */
    @Test
    fun E5_互换两次回到起点() {
        val old = StoryChapterDraft.fromEntity(chapter(title = "旧标题", content = "旧正文", aiSuggestedEnding = false))
        val current = chapter(title = "新标题", content = "新正文", previousDraftJson = StoryChapterDraft.encode(old))

        val once = StoryChapterDraft.swapApplied(current, old)
        assertNotEquals("换一次必须真的变了", current, once)

        val twice = StoryChapterDraft.swapApplied(once, StoryChapterDraft.decode(once.previousDraftJson)!!)

        assertEquals("换两次逐字段回到起点（含槽）", current, twice)
    }

    /** 老槽没写布尔键时按实体默认折叠（无选择 / 未自标结局），不许落 null 或翻面。 */
    @Test
    fun 互换_布尔缺省折叠为false() {
        val draftWithoutBooleans = StoryChapterDraft.decode("""{"title":"旧标题","content":"旧正文"}""")!!

        val swapped = StoryChapterDraft.swapApplied(chapter(hasChoice = true, aiSuggestedEnding = true), draftWithoutBooleans)

        assertEquals(false, swapped.hasChoice)
        assertEquals(false, swapped.aiSuggestedEnding)
    }

    /** 老槽缺 title/content/mood 这三个 NOT NULL 列时的兜底（空串 / 实体默认 mood），不许写出 null。 */
    @Test
    fun 互换_非空列缺省兜底() {
        val empty = StoryChapterDraft.decode("""{"teaser":"只有引子"}""")!!

        val swapped = StoryChapterDraft.swapApplied(chapter(), empty)

        assertEquals("", swapped.title)
        assertEquals("", swapped.content)
        assertEquals("peaceful", swapped.mood)
    }
}
