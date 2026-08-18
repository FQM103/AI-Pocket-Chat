package com.situ.aichat.data.worldbook

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 模板复制 T2（WB8·Robolectric 真 Room 真仓库）：复制成「我的书」= 全新 uuid 家族、字段原样、
 * 重复复制互不相干、删副本不伤别本——「不做只读内置」（D5）的行为面验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBookTemplateCopyTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: WorldBookRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WorldBookRepository(db.worldBookDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun 复制_落库全量条目_字段原样_uuid全新() = runBlocking {
        val template = WorldBookTemplates.all.first { it.id == "xianzhou" }
        val bookUuid = repo.copyTemplate(template)

        val book = repo.getBook(bookUuid)!!
        assertEquals("云梦仙洲", book.name)
        assertEquals(template.description, book.description)
        assertTrue("模板书默认非全局", !book.isGlobal)

        val entries = db.worldBookDao().entriesForBook(bookUuid)
        assertEquals(48, entries.size)
        assertEquals("uuid 须全新且互不重复", 48, entries.map { it.uuid }.distinct().size)
        assertTrue("uuid 不得残留占位空串", entries.none { it.uuid.isBlank() })
        assertEquals("uid 按序重排", (0..47).toList(), entries.map { it.uid }.sorted())
        assertTrue("嵌入两列须为空（交 WB5 懒补）", entries.all { it.embedding == null && it.embeddingSignature == null })

        // 机制字段抽查：常驻基调 / 互斥传闻 / 语义 / 暗线彩蛋
        val tone = entries.first { it.comment == "世界基调·云梦仙洲" }
        assertTrue(tone.constant)
        assertEquals(10, tone.insertionOrder)
        val rumor = entries.first { it.comment == "坊市传闻·秘境将开" }
        assertEquals("坊市传闻", rumor.groupName)
        assertEquals(60, rumor.probability)
        assertEquals(10, rumor.cooldown)
        val semantic = entries.first { it.vectorized }
        assertTrue(semantic.comment.contains("语义"))
        val egg = entries.first { it.delay != null }
        assertTrue(egg.constant)
        assertEquals(30, egg.delay)
    }

    @Test
    fun 重复复制_两本互不相干_删一本不伤另一本() = runBlocking {
        val template = WorldBookTemplates.all.first { it.id == "wasteland" }
        val first = repo.copyTemplate(template)
        val second = repo.copyTemplate(template)

        assertTrue("两次复制须得两本书", first != second)
        val firstUuids = db.worldBookDao().entriesForBook(first).map { it.uuid }.toSet()
        val secondUuids = db.worldBookDao().entriesForBook(second).map { it.uuid }.toSet()
        assertTrue("条目 uuid 集合须不相交", (firstUuids intersect secondUuids).isEmpty())

        repo.deleteBook(first)
        assertNull(repo.getBook(first))
        assertNotNull("删副本不伤另一本", repo.getBook(second))
        assertEquals(48, db.worldBookDao().entriesForBook(second).size)
    }

    @Test
    fun 复制后可随便改_与模板零关联() = runBlocking {
        val template = WorldBookTemplates.all.first { it.id == "shanghai" }
        val bookUuid = repo.copyTemplate(template)
        val entry = db.worldBookDao().entriesForBook(bookUuid).first { it.comment == "外滩" }

        repo.saveEntry(entry.copy(content = "我自己改写的外滩。"))
        val edited = db.worldBookDao().getEntry(entry.uuid)!!
        assertEquals("我自己改写的外滩。", edited.content)

        // 模板原型不受影响（内存常量），再复制一本仍是原文
        val again = repo.copyTemplate(template)
        val fresh = db.worldBookDao().entriesForBook(again).first { it.comment == "外滩" }
        assertTrue("新副本仍是模板原文", fresh.content.startsWith("上海的门面"))
    }
}
