package com.situ.aichat.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the 32-entry built-in catalog (1:1 iOS `BuiltInStickerCatalog`). Values reverse-derived from
 * Models/StickerTypes.swift: 29 png + 2 jpg (大哭_1/大哭_2) + 1 gif (谁怕谁_1, the only animated).
 */
class BuiltInStickerCatalogTest {

    @Test fun `catalog has 32 entries`() {
        assertEquals(32, BuiltInStickerCatalog.all.size)
        assertEquals(32, BuiltInStickerCatalog.byId.size)
    }

    @Test fun `only 谁怕谁 is animated gif`() {
        val animated = BuiltInStickerCatalog.all.filter { it.isAnimated }
        assertEquals(listOf("谁怕谁_1"), animated.map { it.id })
        assertEquals("gif", BuiltInStickerCatalog.byId["谁怕谁_1"]!!.fileExtension)
    }

    @Test fun `大哭 1 and 2 are jpg`() {
        assertEquals("jpg", BuiltInStickerCatalog.byId["大哭_1"]!!.fileExtension)
        assertEquals("jpg", BuiltInStickerCatalog.byId["大哭_2"]!!.fileExtension)
    }

    @Test fun `sample entry fields`() {
        val s = BuiltInStickerCatalog.byId["开心_1"]!!
        assertEquals("开心", s.name)
        assertEquals("超级开心、兴奋、调皮捣蛋、嘻嘻", s.semanticDescription)
        assertFalse(s.isAnimated)
        assertTrue(s.isBuiltIn)
        assertEquals("png", s.fileExtension)
    }

    @Test fun `enabled equals full set when nothing disabled`() {
        assertEquals(32, BuiltInStickerCatalog.enabled(emptySet()).size)
    }

    @Test fun `enabled filters out hidden ids`() {
        val enabled = BuiltInStickerCatalog.enabled(setOf("开心_1"))
        assertEquals(31, enabled.size)
        assertFalse(enabled.any { it.id == "开心_1" })
    }

    @Test fun `enabled empty when all disabled`() {
        val allIds = BuiltInStickerCatalog.all.map { it.id }.toSet()
        assertTrue(BuiltInStickerCatalog.enabled(allIds).isEmpty())
    }

    @Test fun `every entry maps to a unique ascii asset path with matching ext`() {
        val paths = BuiltInStickerCatalog.all.map { s ->
            val p = BuiltInStickerCatalog.assetPath(s.id)
            assertTrue("缺 assetPath: ${s.id}", p != null)
            assertTrue("非 ASCII 资源名: $p", p!!.all { it.code < 128 })
            assertTrue("扩展名不符: $p", p.endsWith(".${s.fileExtension}"))
            assertTrue("前缀不符: $p", p.startsWith("stickers/"))
            p
        }
        assertEquals("资源路径应 32 个且互不重复", 32, paths.toSet().size)
    }

    @Test fun `assetPath null for unknown id`() {
        assertEquals(null, BuiltInStickerCatalog.assetPath("不存在_99"))
    }
}
