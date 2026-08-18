package com.situ.aichat.moments

import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentLikeEntity
import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.util.DateFormatters
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * [MomentChatContextService] 纯格式化函数单测（M06 7.2.6）。断言反推 iOS `buildMomentContext` 的字面格式：
 * `时间 你发了动态：“内容”` / `时间 用户名发了动态[（附带图片）]：“内容”` + ` ← …点赞了；…评论说：“…”`。
 * 时间描述复用 [DateFormatters.momentTimeDescription]（已单测）算期望值，故本测专测**格式装配**（引号/箭头/
 * 全角分号/反应措辞/图片标记/顺序），不重测时间格式本身。
 */
class MomentChatContextServiceTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now = 1_700_000_000_000L

    private fun post(content: String, ts: Long, author: String, charUuid: String?, images: String = "") =
        MomentPostEntity(
            uuid = "p-$ts",
            content = content,
            timestamp = ts,
            authorTypeRaw = author,
            characterUuid = charUuid,
            imagePathsJson = images,
        )

    private fun like(ts: Long, author: String, charUuid: String?) =
        MomentLikeEntity(timestamp = ts, authorTypeRaw = author, characterUuid = charUuid, postUuid = "p")

    private fun comment(content: String, ts: Long, author: String, charUuid: String?) =
        MomentCommentEntity(
            uuid = "c-$ts",
            content = content,
            timestamp = ts,
            authorTypeRaw = author,
            characterUuid = charUuid,
            postUuid = "p",
        )

    private fun td(ts: Long) = DateFormatters.momentTimeDescription(ts, now, zone)

    // ---- 角色自己帖 ----

    @Test
    fun `character post with no user reaction has no arrow`() {
        val p = post("今天天气真好", now - 3 * 3600_000, "character", "c1")
        val result = MomentChatContextService.formatCharacterOwnPostLine(p, null, emptyList(), "小明", now, zone)
        assertEquals("${td(p.timestamp)} 你发了动态：“今天天气真好”", result)
    }

    @Test
    fun `character post with user like and one comment`() {
        val p = post("去爬山啦", now - 5 * 3600_000, "character", "c1")
        val l = like(now - 1800_000, "user", null)
        val cm = comment("好酷！", now - 600_000, "user", null)
        val result = MomentChatContextService.formatCharacterOwnPostLine(p, l, listOf(cm), "小明", now, zone)
        assertEquals(
            "${td(p.timestamp)} 你发了动态：“去爬山啦” ← 小明在${td(l.timestamp)}点赞了；小明在${td(cm.timestamp)}评论说：“好酷！”",
            result,
        )
    }

    @Test
    fun `character post with two user comments joined by fullwidth semicolon`() {
        val p = post("加班中", now - 2 * 3600_000, "character", "c1")
        val c1 = comment("辛苦", now - 3600_000, "user", null)
        val c2 = comment("早点休息", now - 1200_000, "user", null)
        val result = MomentChatContextService.formatCharacterOwnPostLine(p, null, listOf(c1, c2), "小明", now, zone)
        assertEquals(
            "${td(p.timestamp)} 你发了动态：“加班中” ← 小明在${td(c1.timestamp)}评论说：“辛苦”；小明在${td(c2.timestamp)}评论说：“早点休息”",
            result,
        )
    }

    // ---- 用户帖 ----

    @Test
    fun `user post no image with character like only`() {
        val p = post("分享一首歌", now - 4 * 3600_000, "user", null)
        val l = like(now - 3000_000, "character", "c1")
        val result = MomentChatContextService.formatUserPostLine(p, l, emptyList(), "小明", now, zone)
        assertEquals("${td(p.timestamp)} 小明发了动态：“分享一首歌” ← 你在${td(l.timestamp)}点赞了", result)
    }

    @Test
    fun `user post with image and character comment`() {
        val p = post("旅行照片", now - 6 * 3600_000, "user", null, images = """["/sd/a.jpg"]""")
        val cm = comment("风景真美", now - 2400_000, "character", "c1")
        val result = MomentChatContextService.formatUserPostLine(p, null, listOf(cm), "小明", now, zone)
        assertEquals(
            "${td(p.timestamp)} 小明发了动态（附带图片）：“旅行照片” ← 你在${td(cm.timestamp)}评论说：“风景真美”",
            result,
        )
    }
}
