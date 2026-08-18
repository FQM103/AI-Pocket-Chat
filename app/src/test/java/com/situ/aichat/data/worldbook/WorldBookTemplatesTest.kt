package com.situ.aichat.data.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预置模板 T1（WB8）：断言从内容规格独立反推（`FABLE5_WORLDBOOK_TEMPLATES_DRAFT.md` §0 写法总则 +
 * 机制配比），不照搬实现——模板是纯数据，规格就是「每套 48 条、无单字关键词、机制配比固定」这套硬约束。
 */
class WorldBookTemplatesTest {

    private val templates = WorldBookTemplates.all

    private fun WorldBookEntryEntity.keys() = decodeStringList(keysJson)
    private fun WorldBookEntryEntity.secondaryKeys() = decodeStringList(secondaryKeysJson)

    @Test
    fun 三套模板_题材与规模对表D5() {
        assertEquals(listOf("xianzhou", "shanghai", "wasteland"), templates.map { it.id })
        assertEquals(listOf("云梦仙洲", "沪上旧梦", "灰烬纪元"), templates.map { it.name })
        templates.forEach { t ->
            assertEquals("《${t.name}》须 48 条（写最全口径）", 48, t.entries.size)
            assertTrue("简介须非空（书卡副文案）", t.description.isNotBlank())
        }
    }

    @Test
    fun 每条_标题内容非空_原型uuid为占位() {
        templates.forEach { t ->
            val comments = t.entries.map { it.comment }
            assertEquals("《${t.name}》条目标题不得重复", comments.size, comments.distinct().size)
            t.entries.forEach { e ->
                assertTrue("标题非空：${t.name}", e.comment.isNotBlank())
                assertTrue("内容须有实质篇幅：${e.comment}", e.content.length >= 40)
                assertEquals("原型 uuid 须为占位空串（复制时换新）：${e.comment}", "", e.uuid)
                assertEquals("原型 bookUuid 须为占位空串：${e.comment}", "", e.bookUuid)
                assertTrue("模板条目默认启用：${e.comment}", e.enabled)
            }
        }
    }

    @Test
    fun 关键词纪律_无单字_关键词条目必有主键() {
        templates.forEach { t ->
            t.entries.forEach { e ->
                (e.keys() + e.secondaryKeys()).forEach { key ->
                    assertTrue("关键词须 ≥2 字（纯子串匹配防误触）：${e.comment} → 「$key」", key.length >= 2)
                }
                if (!e.constant && !e.vectorized) {
                    assertTrue("非常驻非语义条目必须有主键：${e.comment}", e.keys().isNotEmpty())
                }
                if (e.secondaryKeys().isNotEmpty()) {
                    assertTrue("有次键的条目必有主键（消歧示范）：${e.comment}", e.keys().isNotEmpty())
                    assertTrue("次键逻辑须启用：${e.comment}", e.selective)
                    assertEquals("消歧示范用 AND ANY(0)：${e.comment}", 0, e.selectiveLogic)
                }
            }
        }
    }

    @Test
    fun 机制配比_每套一致() {
        templates.forEach { t ->
            val constants = t.entries.filter { it.constant }
            assertEquals("《${t.name}》常驻 = 基调×2 + 暗线×1", 3, constants.size)

            val easterEggs = constants.filter { it.delay != null }
            assertEquals("《${t.name}》暗线彩蛋须恰 1 条", 1, easterEggs.size)
            with(easterEggs.single()) {
                assertEquals("暗线 delay=30", 30, delay)
                assertEquals("暗线 order=180", 180, insertionOrder)
                assertEquals("暗线 position=1", 1, position)
            }

            val vectorized = t.entries.filter { it.vectorized }
            assertEquals("《${t.name}》语义条目须恰 1 条", 1, vectorized.size)
            assertTrue("语义条目不走关键词", vectorized.single().keys().isEmpty())

            val grouped = t.entries.filter { it.groupName.isNotEmpty() }
            assertEquals("《${t.name}》互斥三选一须恰 3 条", 3, grouped.size)
            assertEquals("同一分组", 1, grouped.map { it.groupName }.distinct().size)
            grouped.forEach {
                assertEquals("传闻组概率 60：${it.comment}", 60, it.probability)
                assertEquals("传闻组冷却 10：${it.comment}", 10, it.cooldown)
            }

            assertTrue("《${t.name}》须有 sticky 情境条目", t.entries.any { it.sticky in 3..4 })
        }
    }

    @Test
    fun 数值走阶梯_不越界() {
        val orderLadder = setOf(10, 50, 100, 150, 180, 200)
        templates.forEach { t ->
            t.entries.forEach { e ->
                assertTrue("order 走阶梯：${e.comment}=${e.insertionOrder}", e.insertionOrder in orderLadder)
                assertTrue("position 只用 0/1：${e.comment}", e.position == 0 || e.position == 1)
                assertTrue("概率只用 60/100：${e.comment}", e.probability == 60 || e.probability == 100)
                e.sticky?.let { assertTrue("sticky 3–4：${e.comment}", it in 3..4) }
                e.cooldown?.let { assertEquals("冷却=10：${e.comment}", 10, it) }
            }
        }
    }

    @Test
    fun 消歧示范在位_白芷与大世界() {
        val baizhi = templates.first { it.id == "xianzhou" }.entries.first { "白芷" in it.comment }
        assertEquals(listOf("白芷"), baizhi.keys())
        assertTrue("白芷须带消歧次键", baizhi.secondaryKeys().isNotEmpty())

        val dashijie = templates.first { it.id == "shanghai" }.entries.first { it.comment == "大世界" }
        assertEquals(listOf("大世界"), dashijie.keys())
        assertTrue("大世界须带消歧次键", dashijie.secondaryKeys().contains("白相"))
    }
}
