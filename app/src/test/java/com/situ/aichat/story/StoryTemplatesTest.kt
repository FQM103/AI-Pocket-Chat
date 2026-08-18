package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置故事模板 T1（ST6·契约 §11）：断言从内容规格独立反推（`FABLE5_STORY_TEMPLATES_DRAFT.md` §0 写法总则 +
 * §1 总览 + 契约 §3.3 配比），不照搬实现——模板是纯数据，规格 = 「12 套 / 字段非空 / genre×style 落预设 /
 * 覆盖全 10 题材 × 全 6 文风 / 字数区间 / 人称配比（悬疑 first·科幻 third·余 second）」这套硬约束。
 */
class StoryTemplatesTest {

    private val templates = StoryTemplates.all

    @Test
    fun 十二套_数量与id唯一对表草稿() {
        assertEquals("须恰 12 套（契约 §3.3·D1）", 12, templates.size)
        val ids = templates.map { it.id }
        assertEquals("id 不得重复", ids.size, ids.distinct().size)
        assertEquals(
            "id 须对表草稿 §2（顺序：言情 5 / 幻想 3 / 张力 3 / 治愈 1）",
            listOf(
                "lamp-across-the-street", "first-year-of-marriage", "seventh-year-reunion",
                "last-question-of-summer", "snow-over-changxin-palace", "never-ask-immortality",
                "dragon-breath-evening-wind", "long-voyage-home", "mist-locked-17th-floor",
                "mist-ridge-sanatorium", "the-fourth-winter", "xiaoman-grocery-store",
            ),
            ids,
        )
    }

    @Test
    fun 每套_字段非空() {
        templates.forEach { t ->
            assertTrue("id 非空", t.id.isNotBlank())
            assertTrue("书名非空：${t.id}", t.title.isNotBlank())
            assertTrue("钩子非空：${t.id}", t.tagline.isNotBlank())
            assertTrue("选角提示非空：${t.id}", t.roleHint.isNotBlank())
            assertTrue("封面意象非空：${t.id}", t.coverMotif.isNotBlank())
            assertTrue("世界观非空：${t.id}", t.worldSetting.isNotBlank())
            assertTrue("剧情方向非空：${t.id}", t.plotDirection.isNotBlank())
        }
    }

    @Test
    fun genre与style落预设枚举() {
        templates.forEach { t ->
            assertTrue("题材须在预设 10 内：${t.id}=${t.genre}", t.genre in StoryCreationCatalog.genres)
            assertTrue("文风须在预设 6 内：${t.id}=${t.writingStyle}", t.writingStyle in StoryCreationCatalog.writingStyles)
        }
    }

    @Test
    fun 覆盖全部题材与文风() {
        assertEquals("12 套须覆盖全部 10 题材", StoryCreationCatalog.genres.toSet(), templates.map { it.genre }.toSet())
        assertEquals("12 套须覆盖全部 6 文风", StoryCreationCatalog.writingStyles.toSet(), templates.map { it.writingStyle }.toSet())
    }

    @Test
    fun 人称合法且配比对表草稿() {
        val valid = setOf(StoryNarrativePerson.FIRST, StoryNarrativePerson.SECOND, StoryNarrativePerson.THIRD)
        templates.forEach { t ->
            assertTrue("人称须合法：${t.id}=${t.narrativePerson}", t.narrativePerson in valid)
        }
        // 草稿 §0.6：SECOND 为主，悬疑=FIRST、科幻=THIRD
        assertEquals("first 恰 1 套", 1, templates.count { it.narrativePerson == StoryNarrativePerson.FIRST })
        assertEquals("third 恰 1 套", 1, templates.count { it.narrativePerson == StoryNarrativePerson.THIRD })
        assertEquals("其余 10 套 second", 10, templates.count { it.narrativePerson == StoryNarrativePerson.SECOND })
        assertEquals("first = 悬疑", "悬疑", templates.single { it.narrativePerson == StoryNarrativePerson.FIRST }.genre)
        assertEquals("third = 科幻", "科幻", templates.single { it.narrativePerson == StoryNarrativePerson.THIRD }.genre)
    }

    @Test
    fun 字数区间_世界观剧情钩子() {
        templates.forEach { t ->
            // 草稿 §0.3/§0.4：世界观 300–500 字、剧情方向 200–400 字（严格锁草稿口径·实测 12 套 max 431/334 全在内，
            // R1 🔵-4 去 cushion 防未来模板越线而测试放行）
            assertTrue("世界观篇幅须在区间（${t.id}=${t.worldSetting.length}）", t.worldSetting.length in 300..500)
            assertTrue("剧情方向篇幅须在区间（${t.id}=${t.plotDirection.length}）", t.plotDirection.length in 200..400)
            assertTrue("钩子须精短（${t.id}=${t.tagline.length}）", t.tagline.length in 6..40)
        }
    }
}
