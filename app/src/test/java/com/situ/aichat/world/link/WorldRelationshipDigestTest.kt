package com.situ.aichat.world.link

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldIds
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [WorldRelationshipDigest] T1-2（纯函数·图纸 §7·E9）：选 3 优先级金标串逐字符 + 相对日/types/色彩句/亲密档边界。
 * 断言从图纸 §4.1 格式表独立反推（金标串按 §4.1 示例逐字重打）。UTC 令 epochDay 与本地日无歧义。
 */
class WorldRelationshipDigestTest {

    private val zone = ZoneOffset.UTC
    private fun self() = CharacterEntity(uuid = "me", name = "我", creationDate = 0L)
    private fun noon(epochDay: Long) = LocalDate.ofEpochDay(epochDay).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun edge(toId: String, closeness: Int, colorRaw: String = "投缘", tension: Int = 0, types: List<String> = listOf("相识")) =
        WorldRelationshipEntity(
            fromId = "me", toId = toId, typesJson = StringListJson.encode(types),
            closeness = closeness, trust = 0, tension = tension, colorRaw = colorRaw,
        )

    private fun event(happenedAt: Long, summary: String, kind: String = "rel_quarrel_start") =
        WorldRelationshipEventEntity(
            uuid = "e-$happenedAt", pairKey = "pk", actorId = "x", targetId = "y",
            kindRaw = kind, arcId = null, summary = summary, happenedAt = happenedAt, settledAt = happenedAt,
        )

    private fun build(
        edges: List<WorldRelationshipEntity>,
        recent: Map<String, WorldRelationshipEventEntity> = emptyMap(),
        names: Map<String, String>,
        query: String = "",
        nowEpochDay: Long = 100L,
    ) = WorldRelationshipDigest.build(self(), edges, recent, names, query, noon(nowEpochDay), zone)

    // MARK: - E9 金标（§4.1 示例逐字符）

    @Test
    fun `E9 金标提炼行逐字符`() {
        val e = edge("azhe", closeness = 50, colorRaw = "别扭", tension = 60, types = listOf("相识", "朋友"))
        val recent = mapOf(
            WorldIds.pairKey("me", "azhe") to event(noon(99), "阿哲和小雅为一件小事拌了嘴，谁也没先低头"),
        )
        val line = build(listOf(e), recent, mapOf("azhe" to "阿哲")).single()
        assertEquals(
            "【与阿哲｜相识·朋友】你们关系不错，眼下和 TA 有点别扭；昨天：阿哲和小雅为一件小事拌了嘴，谁也没先低头",
            line,
        )
    }

    // MARK: - 选 3 优先级顺序（话题相关 → 最近有事 → 最亲）

    @Test
    fun `选3顺序_话题相关_最近有事_最亲`() {
        val edges = listOf(
            edge("a", closeness = 10), // 话题相关（名在 query）
            edge("b", closeness = 20), // 最近有事
            edge("c", closeness = 90), // 最亲
            edge("d", closeness = 80), // 次亲（被挤出前 3）
        )
        val recent = mapOf(WorldIds.pairKey("me", "b") to event(noon(90), "近事"))
        val names = mapOf("a" to "阿一", "b" to "阿二", "c" to "阿三", "d" to "阿四")
        val lines = build(edges, recent, names, query = "今天和阿一聊天")
        assertEquals("恰 3 行", 3, lines.size)
        assertEquals(listOf("阿一", "阿二", "阿三"), lines.map { it.substringAfter("【与").substringBefore("｜") })
    }

    // MARK: - types 边界（空→相识 / 末尾至多 2 个）

    @Test
    fun `types边界`() {
        fun typesOf(t: List<String>) =
            build(listOf(edge("x", 50, types = t)), names = mapOf("x" to "小X")).single().substringAfter("｜").substringBefore("】")
        assertEquals("相识", typesOf(emptyList()))
        assertEquals("相识", typesOf(listOf("相识")))
        assertEquals("朋友·密友", typesOf(listOf("相识", "朋友", "密友")))
    }

    // MARK: - 亲密档四界 + 色彩句省略

    @Test
    fun `亲密档四界`() {
        fun status(c: Int) =
            build(listOf(edge("x", c, colorRaw = "")), names = mapOf("x" to "小X")).single().substringAfter("】")
        assertEquals("你们刚认识不久", status(14))
        assertEquals("你们正慢慢熟络起来", status(15))
        assertEquals("你们正慢慢熟络起来", status(34))
        assertEquals("你们关系不错", status(35))
        assertEquals("你们关系不错", status(69))
        assertEquals("你们交情很深", status(70))
    }

    @Test
    fun `色彩不在表内_省略色彩句连同逗号`() {
        val line = build(listOf(edge("x", 50, colorRaw = "不存在的色")), names = mapOf("x" to "小X")).single()
        assertEquals("【与小X｜相识】你们关系不错", line)
    }

    @Test
    fun `色彩句在表内`() {
        val line = build(listOf(edge("x", 50, colorRaw = "心动")), names = mapOf("x" to "小X")).single()
        assertEquals("【与小X｜相识】你们关系不错，你对 TA 有点心动", line)
    }

    // MARK: - 相对日词 + 气氛有些僵 + 无近事省略

    @Test
    fun `相对日词`() {
        fun relDay(eventEpochDay: Long): String {
            val recent = mapOf(WorldIds.pairKey("me", "x") to event(noon(eventEpochDay), "S"))
            return build(listOf(edge("x", 50, colorRaw = "")), recent, mapOf("x" to "小X"), nowEpochDay = 100L)
                .single().substringAfter("；").substringBefore("：")
        }
        assertEquals("今天", relDay(100))
        assertEquals("昨天", relDay(99))
        assertEquals("前天", relDay(98))
        assertEquals("几天前", relDay(97))
        assertEquals("几天前", relDay(93))
    }

    @Test
    fun `气氛有些僵_仅tension大于等于40且色彩非别扭较劲`() {
        val recent = mapOf(WorldIds.pairKey("me", "x") to event(noon(100), "S"))
        // tension 60 + colorRaw=投缘（不在别扭/较劲）→ 插「，气氛有些僵」。
        val hit = build(listOf(edge("x", 50, colorRaw = "投缘", tension = 60)), recent, mapOf("x" to "小X")).single()
        assertEquals("【与小X｜相识】你们关系不错，和 TA 很聊得来，气氛有些僵；今天：S", hit)
        // colorRaw=较劲（在排除集）→ 不插。
        val excluded = build(listOf(edge("x", 50, colorRaw = "较劲", tension = 60)), recent, mapOf("x" to "小X")).single()
        assertEquals("【与小X｜相识】你们关系不错，你俩暗暗较着劲；今天：S", excluded)
        // tension 39（<40）→ 不插。
        val low = build(listOf(edge("x", 50, colorRaw = "投缘", tension = 39)), recent, mapOf("x" to "小X")).single()
        assertEquals("【与小X｜相识】你们关系不错，和 TA 很聊得来；今天：S", low)
    }

    @Test
    fun `无近事省略近事句`() {
        val line = build(listOf(edge("x", 50, colorRaw = "投缘")), names = mapOf("x" to "小X")).single()
        assertEquals("【与小X｜相识】你们关系不错，和 TA 很聊得来", line)
    }

    @Test
    fun `无边返回空`() {
        assertEquals(emptyList<String>(), build(emptyList(), names = emptyMap()))
    }
}
