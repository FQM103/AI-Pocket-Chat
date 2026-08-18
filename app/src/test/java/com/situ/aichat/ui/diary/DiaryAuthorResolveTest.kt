package com.situ.aichat.ui.diary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：交换日记作者显示名解析（U3 故人来信·契约 FABLE5_DIARY_REDESIGN_PROPOSAL §6.3 O1/O2/O3b）。
 * 断言从 spec 独立反推——四条口径：非交换日记=null；活角色=活名不孤儿；角色已删=快照名+孤儿；
 * 快照空/空白=通用兜底名+孤儿。兜底名由调用方传入（@Composable 壳从资源取「一位旧友」）。
 */
class DiaryAuthorResolveTest {

    private val fallback = "一位旧友"

    @Test
    fun `user's own diary resolves to null`() {
        // authorCharacterUuid 为空 = 用户自己写的日记，绝不显示作者头/署名（快照名有值也不越权）。
        assertNull(resolveDiaryAuthor(null, null, null, fallback))
        assertNull(resolveDiaryAuthor(null, "残留快照", "残留活名", fallback))
    }

    @Test
    fun `live character uses live name and is not orphan`() {
        // O1：角色在 = 活名（无缝）；不是孤儿信，无「故友的信」淡标。
        val d = resolveDiaryAuthor("char-1", null, "小满", fallback)!!
        assertEquals("小满", d.name)
        assertFalse(d.isOrphan)
    }

    @Test
    fun `live name wins over snapshot`() {
        // 角色仍在但也存了快照（改名场景）：显示当前活名，不回退快照。
        val d = resolveDiaryAuthor("char-1", "旧快照名", "现用名", fallback)!!
        assertEquals("现用名", d.name)
        assertFalse(d.isOrphan)
    }

    @Test
    fun `deleted character falls back to snapshot name and is orphan`() {
        // O1+O2：角色已删（活名查空）但 uuid 非空 → 快照名 + 孤儿标记（触发「故友的信」淡标）。
        val d = resolveDiaryAuthor("ghost", "小满", null, fallback)!!
        assertEquals("小满", d.name)
        assertTrue(d.isOrphan)
    }

    @Test
    fun `deleted with null snapshot uses fallback name and is orphan`() {
        // O3b：v25 迁移前删的老信没有快照名 → 通用兜底名「一位旧友」，仍是孤儿。
        val d = resolveDiaryAuthor("ghost", null, null, fallback)!!
        assertEquals(fallback, d.name)
        assertTrue(d.isOrphan)
    }

    @Test
    fun `deleted with blank snapshot uses fallback name and is orphan`() {
        // 快照空串/纯空白同样降级到兜底名（不显示一段空白作者名）。
        listOf("", "   ", "\t").forEach { blank ->
            val d = resolveDiaryAuthor("ghost", blank, null, fallback)!!
            assertEquals("空白快照『$blank』应回退兜底名", fallback, d.name)
            assertTrue(d.isOrphan)
        }
    }
}
