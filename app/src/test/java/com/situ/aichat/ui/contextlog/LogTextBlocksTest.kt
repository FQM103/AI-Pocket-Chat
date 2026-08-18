package com.situ.aichat.ui.contextlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全文分块纯函数单测（D-3 打磨·②·T1）。规格反推：行边界切块、块大小有上界、
 * 换行重接 = 原文（唯一例外 = 单行超上限硬切）、短文本单块直返。
 */
class LogTextBlocksTest {

    @Test
    fun 短文本_单块原样() {
        assertEquals(listOf("你好\n世界"), splitLogTextBlocks("你好\n世界"))
        assertEquals(listOf(""), splitLogTextBlocks(""))
    }

    @Test
    fun 行边界切块_重接即原文_块不超上限() {
        // 900 行 × 10 字：maxChars=100 → 每块 ≤100，行绝不被腰斩。
        val text = (1..900).joinToString("\n") { "第${it}行内容啊" }
        val blocks = splitLogTextBlocks(text, maxChars = 100)
        assertTrue("必须真分了块", blocks.size > 1)
        blocks.forEach { assertTrue("块大小有上界：${it.length}", it.length <= 100) }
        assertEquals("换行重接 = 原文（零字符增删）", text, blocks.joinToString("\n"))
        blocks.forEach { block ->
            block.split('\n').forEach { line -> assertTrue("行不被腰斩", line.startsWith("第") && line.endsWith("啊")) }
        }
    }

    @Test
    fun 单行超上限_硬切成多块_内容零丢失() {
        val mono = "甲".repeat(250) + "乙".repeat(250)
        val blocks = splitLogTextBlocks(mono, maxChars = 100)
        assertTrue(blocks.size >= 5)
        blocks.forEach { assertTrue(it.length <= 100) }
        assertEquals("硬切块直接拼接 = 原文", mono, blocks.joinToString(""))
    }

    @Test
    fun 混合_长行前后的普通行不受牵连() {
        val text = "普通行A\n" + "长".repeat(300) + "\n普通行B"
        val blocks = splitLogTextBlocks(text, maxChars = 100)
        assertEquals("普通行A", blocks.first())
        assertEquals("普通行B", blocks.last())
        assertEquals("总字符零丢失（换行数 = 原文换行数）", text.length, blocks.sumOf { it.length } + 2)
    }

    // ── 复核 R1-🟡1 反例回归：空行落在块边界曾被静默吞掉（恒等性破裂·fuzz 实证）──

    @Test
    fun 块恰满后跟空行_空行不丢() {
        // 反例原文：块积满 10 字后下一行是空行——旧实现空行进空块后块仍被判「空」，换行凭空消失。
        val text = "aaaaaaaaaa\n\nbb"
        assertEquals(text, splitLogTextBlocks(text, maxChars = 10).joinToString("\n"))
    }

    @Test
    fun 连续多个空行跨块边界_逐个保住() {
        val text = "aaaaaaaaaa\n\n\n\nbb"
        assertEquals(text, splitLogTextBlocks(text, maxChars = 10).joinToString("\n"))
    }

    @Test
    fun 前导空行与尾随换行_原样保住() {
        val leading = "\n\naaaaaaaaaa\nb"
        assertEquals(leading, splitLogTextBlocks(leading, maxChars = 10).joinToString("\n"))
        val trailing = "aaaaaaaaaa\nb\n"
        assertEquals(trailing, splitLogTextBlocks(trailing, maxChars = 10).joinToString("\n"))
    }

    @Test
    fun 硬切长行后跟空行_行距不消失() {
        // 硬切块与后续文本的重接是文档明示例外（块界=视觉换行），但长行**之后的空行**必须存活：
        // 尾块应以空行开头（渲染出行距）。
        val text = "长".repeat(25) + "\n\n下一段"
        val blocks = splitLogTextBlocks(text, maxChars = 10)
        assertEquals("硬切 25 字成 3 块 + 尾块", 4, blocks.size)
        assertEquals("尾块以空行开头保住行距", "\n下一段", blocks.last())
    }

    @Test
    fun 硬切不劈emoji代理对() {
        // 「😀」= 2 个 UTF-16 单元；maxChars=5 的切点落在代理对中间时高位回退一格。
        val mono = "😀".repeat(20) // 40 单元、无换行 → 必硬切
        val blocks = splitLogTextBlocks(mono, maxChars = 5)
        blocks.forEach { b ->
            assertTrue("块不得以孤高位结尾：${b.length}", !Character.isHighSurrogate(b.last()))
            assertTrue("块不得以孤低位开头", !Character.isLowSurrogate(b.first()))
            assertTrue(b.length <= 5)
        }
        assertEquals("拼接零丢失", mono, blocks.joinToString(""))
    }

    @Test
    fun 默认上限_二十万字级性能兜底形状() {
        // 形状断言：20 万字 → 约 50+ 块（每块 ≤4000），LazyColumn 才有惰性空间。
        val text = (1..10_000).joinToString("\n") { "这是第${it}行的日志正文内容" }
        val blocks = splitLogTextBlocks(text)
        assertTrue("块数要够 LazyColumn 惰性", blocks.size > 10)
        blocks.forEach { assertTrue(it.length <= LOG_TEXT_BLOCK_CHARS) }
        assertEquals(text, blocks.joinToString("\n"))
    }
}
