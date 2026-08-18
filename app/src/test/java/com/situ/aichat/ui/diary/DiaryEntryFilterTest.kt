package com.situ.aichat.ui.diary

import com.situ.aichat.data.local.entity.DiaryEntryEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：日记作者筛选谓词（U4·契约 §6.2/§6.3③）。断言从 spec 独立反推——
 * 全部=全收；我的=authorCharacterUuid 为空；TA 的信=authorCharacterUuid 非空（含 U3 孤儿信）；
 * 「我的」与「TA 的信」构成对全集的无交叠二分。
 */
class DiaryEntryFilterTest {

    private val mine = DiaryEntryEntity(uuid = "u-mine")
    private val theirs = DiaryEntryEntity(uuid = "u-theirs", authorCharacterUuid = "char-1")
    private val orphan = DiaryEntryEntity(uuid = "u-orphan", authorCharacterUuid = "ghost", authorNameSnapshot = "小满")

    @Test
    fun `ALL matches every entry`() {
        listOf(mine, theirs, orphan).forEach { assertTrue(DiaryEntryFilter.ALL.matches(it)) }
    }

    @Test
    fun `MINE matches only user's own diaries`() {
        assertTrue(DiaryEntryFilter.MINE.matches(mine))
        assertFalse(DiaryEntryFilter.MINE.matches(theirs))
        assertFalse("孤儿信仍是 TA 的信，不算「我的」", DiaryEntryFilter.MINE.matches(orphan))
    }

    @Test
    fun `THEIRS matches exchange diaries including orphans`() {
        assertTrue(DiaryEntryFilter.THEIRS.matches(theirs))
        assertTrue("孤儿信（角色已删·快照名留存）仍归 TA 的信", DiaryEntryFilter.THEIRS.matches(orphan))
        assertFalse(DiaryEntryFilter.THEIRS.matches(mine))
    }

    @Test
    fun `MINE and THEIRS partition the full set with no overlap`() {
        listOf(mine, theirs, orphan).forEach { e ->
            val inMine = DiaryEntryFilter.MINE.matches(e)
            val inTheirs = DiaryEntryFilter.THEIRS.matches(e)
            assertTrue("每条恰属一侧（无交叠）", inMine != inTheirs)
            assertTrue("并集 = 全集（ALL）", (inMine || inTheirs) == DiaryEntryFilter.ALL.matches(e))
        }
    }
}
