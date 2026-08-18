package com.situ.aichat.sticker

import com.situ.aichat.data.local.entity.CustomStickerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with iOS `StickerAliasTests` + `StickerGIFFallbackTests` (the pure parts). Assertions are
 * reverse-derived from the iOS test expectations — alias cleaning (`[ ] :` + all whitespace incl
 * tab/全角空格), `c_name`/`c_name_N` numbering, alias⇄UUID round-trips, strip-invalid vs strip-all,
 * semantic conversion, GIF magic prefilter, and source resolution.
 */
class StickerServiceTest {

    private fun sticker(
        name: String,
        uuid: String = "uuid-${name.hashCode()}",
        description: String = "",
        isAnimated: Boolean = false,
        imagePath: String = "",
    ) = CustomStickerEntity(
        stickerUuid = uuid,
        name = name,
        semanticDescription = description,
        isAnimated = isAnimated,
        imagePath = imagePath,
    )

    // ---- buildCustomStickerAliasMap ----

    @Test fun `alias single`() {
        val map = StickerService.buildCustomStickerAliasMap(listOf(sticker("我想要大叫", "test-uuid-1")))
        assertEquals("test-uuid-1", map["c_我想要大叫"])
        assertEquals(1, map.size)
    }

    @Test fun `alias distinct names`() {
        val map = StickerService.buildCustomStickerAliasMap(
            listOf(sticker("开心猫", "uuid-a"), sticker("难过兔", "uuid-b")),
        )
        assertEquals("uuid-a", map["c_开心猫"])
        assertEquals("uuid-b", map["c_难过兔"])
        assertEquals(2, map.size)
    }

    @Test fun `alias same name appends suffix`() {
        val map = StickerService.buildCustomStickerAliasMap(
            listOf(sticker("哈哈", "uuid-1"), sticker("哈哈", "uuid-2"), sticker("哈哈", "uuid-3")),
        )
        assertEquals("uuid-1", map["c_哈哈"])
        assertEquals("uuid-2", map["c_哈哈_2"])
        assertEquals("uuid-3", map["c_哈哈_3"])
        assertEquals(3, map.size)
    }

    @Test fun `alias strips bracket-colon special chars`() {
        val map = StickerService.buildCustomStickerAliasMap(listOf(sticker("[测试:1]", "uuid-special")))
        assertEquals("uuid-special", map["c_测试1"])
    }

    @Test fun `alias strips space and newline`() {
        val map = StickerService.buildCustomStickerAliasMap(listOf(sticker("我 想\n大叫", "uuid-ws")))
        assertEquals("uuid-ws", map["c_我想大叫"])
    }

    @Test fun `alias strips tab and fullwidth space`() {
        // \t 与全角空格 　 都属于 Char.isWhitespace()，必须过滤
        val map = StickerService.buildCustomStickerAliasMap(listOf(sticker("大\t叫　猫", "uuid-tab")))
        assertEquals("uuid-tab", map["c_大叫猫"])
    }

    @Test fun `alias empty name falls back`() {
        val map = StickerService.buildCustomStickerAliasMap(listOf(sticker("", "uuid-empty")))
        assertEquals("uuid-empty", map["c_表情包"])
    }

    // ---- K8（2026-07-12 性能线程专项）：预建反查表重载 = 便捷版逐字节等价 ----

    @Test fun `uuid-to-alias overload equals list version byte for byte`() {
        val stickers = listOf(sticker("哈哈", "uuid-1"), sticker("哈哈", "uuid-2"), sticker("", "uuid-empty"))
        val content = "看[sticker:uuid-1]和[sticker:uuid-2]还有[sticker:uuid-empty]与未知[sticker:unknown]"
        val viaList = StickerService.convertUUIDToAlias(content, stickers)
        val viaMap = StickerService.convertUUIDToAlias(content, StickerService.buildUuidToAliasMap(stickers))
        assertEquals(viaList, viaMap)
        assertEquals("看[sticker:c_哈哈]和[sticker:c_哈哈_2]还有[sticker:c_表情包]与未知[sticker:unknown]", viaMap)
    }

    @Test fun `uuid-to-alias map keeps first alias for duplicate uuid`() {
        // 同一 uuid 挂两个名字（理论脏数据）：反查表保留首别名（对齐 iOS uniquingKeysWith { first }）
        val map = StickerService.buildUuidToAliasMap(listOf(sticker("猫", "dup-uuid"), sticker("狗", "dup-uuid")))
        assertEquals("c_猫", map["dup-uuid"])
        assertEquals(1, map.size)
    }

    @Test fun `uuid-to-alias with empty stickers is passthrough`() {
        val content = "原样[sticker:uuid-x]保留"
        assertEquals(content, StickerService.convertUUIDToAlias(content, emptyList()))
        assertEquals(content, StickerService.convertUUIDToAlias(content, emptyMap()))
    }

    @Test fun `alias multiple empty names dedup`() {
        val map = StickerService.buildCustomStickerAliasMap(
            listOf(sticker("", "uuid-e1"), sticker("", "uuid-e2")),
        )
        assertEquals("uuid-e1", map["c_表情包"])
        assertEquals("uuid-e2", map["c_表情包_2"])
    }

    @Test fun `alias empty list`() {
        assertTrue(StickerService.buildCustomStickerAliasMap(emptyList()).isEmpty())
    }

    // ---- convertAliasToUUID ----

    @Test fun `aliasToUUID normal`() {
        val s = sticker("开心猫", "real-uuid-123")
        assertEquals("哈哈 [sticker:real-uuid-123]", StickerService.convertAliasToUUID("哈哈 [sticker:c_开心猫]", listOf(s)))
    }

    @Test fun `aliasToUUID builtin unchanged`() {
        val s = sticker("测试", "uuid-x")
        assertEquals("[sticker:开心_1]", StickerService.convertAliasToUUID("[sticker:开心_1]", listOf(s)))
    }

    @Test fun `aliasToUUID invalid alias unchanged`() {
        val s = sticker("存在的", "uuid-exists")
        assertEquals("[sticker:c_不存在的]", StickerService.convertAliasToUUID("[sticker:c_不存在的]", listOf(s)))
    }

    @Test fun `aliasToUUID mixed builtin and custom`() {
        val s = sticker("猫咪", "cat-uuid")
        val r = StickerService.convertAliasToUUID("你好[sticker:开心_1]再见[sticker:c_猫咪]", listOf(s))
        assertTrue(r.contains("[sticker:开心_1]"))
        assertTrue(r.contains("[sticker:cat-uuid]"))
        assertFalse(r.contains("c_猫咪"))
    }

    @Test fun `aliasToUUID empty custom list`() {
        assertEquals("[sticker:c_不存在]", StickerService.convertAliasToUUID("[sticker:c_不存在]", emptyList()))
    }

    // ---- convertUUIDToAlias ----

    @Test fun `uuidToAlias normal`() {
        val s = sticker("开心猫", "real-uuid-456")
        assertEquals("回复文字 [sticker:c_开心猫]", StickerService.convertUUIDToAlias("回复文字 [sticker:real-uuid-456]", listOf(s)))
    }

    @Test fun `uuidToAlias builtin unchanged`() {
        val s = sticker("测试", "uuid-y")
        assertEquals("[sticker:开心_1]", StickerService.convertUUIDToAlias("[sticker:开心_1]", listOf(s)))
    }

    @Test fun `uuidToAlias unknown uuid unchanged`() {
        val s = sticker("已知的", "known-uuid")
        assertEquals("[sticker:unknown-uuid-999]", StickerService.convertUUIDToAlias("[sticker:unknown-uuid-999]", listOf(s)))
    }

    @Test fun `round trip alias to uuid to alias`() {
        val s = sticker("猫咪", "round-trip-uuid")
        val original = "文字 [sticker:c_猫咪] 结尾"
        val asUuid = StickerService.convertAliasToUUID(original, listOf(s))
        assertEquals("文字 [sticker:round-trip-uuid] 结尾", asUuid)
        assertEquals(original, StickerService.convertUUIDToAlias(asUuid, listOf(s)))
    }

    // ---- buildStickerListForPrompt ----

    @Test fun `prompt list custom uses alias not uuid`() {
        val s = sticker("认输", "long-uuid-value")
        val list = StickerService.buildStickerListForPrompt(listOf(s), emptySet())
        assertTrue(list.contains("c_认输"))
        assertFalse(list.contains("long-uuid-value"))
        assertTrue(list.contains("（用户添加）"))
    }

    @Test fun `prompt list builtin id intact`() {
        val list = StickerService.buildStickerListForPrompt(emptyList(), emptySet())
        assertTrue(list.contains("开心_1"))
        assertFalse(list.contains("c_开心"))
    }

    // ---- convertStickerTagsToDescription ----

    @Test fun `description uses builtin semantic`() {
        assertEquals(
            "[非语言情绪：超级开心、兴奋、调皮捣蛋、嘻嘻]",
            StickerService.convertStickerTagsToDescription("[sticker:开心_1]", emptyList()),
        )
    }

    @Test fun `description uses custom effective description`() {
        val s = sticker("狗头", "dog-uuid", description = "开玩笑、别当真")
        assertEquals("[非语言情绪：开玩笑、别当真]", StickerService.convertStickerTagsToDescription("[sticker:dog-uuid]", listOf(s)))
    }

    @Test fun `description keeps unknown id`() {
        assertEquals("[sticker:不存在]", StickerService.convertStickerTagsToDescription("[sticker:不存在]", emptyList()))
    }

    // ---- stripInvalidStickerTags ----

    @Test fun `strip invalid keeps valid builtin`() {
        assertEquals("你好 [sticker:开心_1]", StickerService.stripInvalidStickerTags("你好 [sticker:开心_1]", emptyList()))
    }

    @Test fun `strip invalid removes hallucinated id`() {
        // [sticker:happy] 是 AI 幻觉 ID（既非内置全集也非自定义）→ 剥掉，尾空白一起
        assertEquals("你好", StickerService.stripInvalidStickerTags("你好 [sticker:happy]", emptyList()))
    }

    @Test fun `strip invalid keeps valid removes invalid in mix`() {
        val s = sticker("猫", "real-cat")
        val r = StickerService.stripInvalidStickerTags("[sticker:real-cat] 中间 [sticker:开心_2]", listOf(s))
        assertTrue(r.contains("[sticker:real-cat]"))
        assertFalse(r.contains("开心_2"))
    }

    // ---- stripAllStickerTags (iOS StickerAliasTests cases) ----

    @Test fun `strip all empty`() = assertEquals("", StickerService.stripAllStickerTags(""))

    @Test fun `strip all no tags unchanged`() {
        val input = "普通文本没有任何表情包标签"
        assertEquals(input, StickerService.stripAllStickerTags(input))
    }

    @Test fun `strip all single`() = assertEquals("你好", StickerService.stripAllStickerTags("你好 [sticker:开心_1]"))

    @Test fun `strip all multiple`() = assertEquals("", StickerService.stripAllStickerTags("[sticker:a][sticker:b][sticker:c]"))

    @Test fun `strip all mixed keeps text`() =
        assertEquals("开头 中间 结尾", StickerService.stripAllStickerTags("开头 [sticker:foo] 中间 [sticker:bar] 结尾"))

    @Test fun `strip all trailing whitespace`() =
        assertEquals("hello world", StickerService.stripAllStickerTags("hello [sticker:foo]   world"))

    @Test fun `strip all empty-id tag kept`() {
        val input = "[sticker:]"
        assertEquals(input, StickerService.stripAllStickerTags(input))
    }

    @Test fun `strip all chinese id`() = assertEquals("太棒了", StickerService.stripAllStickerTags("[sticker:开心_1] 太棒了"))

    // ---- looksLikeGifHeader (iOS isAnimatedGIFData first gate) ----

    @Test fun `gif header true for GIF magic`() {
        val gif = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) // "GIF89a"
        assertTrue(StickerService.looksLikeGifHeader(gif))
    }

    @Test fun `gif header false for png`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        assertFalse(StickerService.looksLikeGifHeader(png))
    }

    @Test fun `gif header false for too short`() {
        assertFalse(StickerService.looksLikeGifHeader(byteArrayOf(0x47, 0x49, 0x46)))
        assertFalse(StickerService.looksLikeGifHeader(ByteArray(0)))
    }

    // ---- resolveSource ----

    @Test fun `resolve builtin static asset uses ascii filename`() {
        // 逻辑 ID 中文，物理资源 ASCII（拼音）——中文文件名打包后查找不稳。
        val src = StickerService.resolveSource("开心_1", emptyList())
        assertEquals(StickerSource.Asset("stickers/kaixin_1.png", false), src)
    }

    @Test fun `resolve builtin gif asset`() {
        assertEquals(StickerSource.Asset("stickers/sheipashei_1.gif", true), StickerService.resolveSource("谁怕谁_1", emptyList()))
    }

    @Test fun `resolve custom file`() {
        val s = sticker("我的", "my-uuid", isAnimated = true, imagePath = "/data/stickers/x.gif")
        assertEquals(StickerSource.CustomFile("/data/stickers/x.gif", true), StickerService.resolveSource("my-uuid", listOf(s)))
    }

    @Test fun `resolve unknown returns null`() {
        assertNull(StickerService.resolveSource("nope-uuid", emptyList()))
    }
}
