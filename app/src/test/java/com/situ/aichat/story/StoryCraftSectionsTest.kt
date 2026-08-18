package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 故事二期卷一「三态取值单源」T1（图纸 §7 T1-2 / E15）。
 *
 * 断言从提案 §2-1 三态语义与图纸 §3.2 规格**独立反推**（非照搬实现）：
 * 书级 `null`=跟随全局 / `""`（含纯空白）=本书关闭 / 文本=本书覆盖；
 * 全局 `null`=从未设置→出厂默认 / `""`=全局关闭 / 文本=全局值；
 * 画像没有出厂默认，两层皆空 = 不注入。
 *
 * ⚠️ 书级是**真三态**，与文字忌口的二态（空白=跟随全局）刻意不同（图纸 J2）——
 * 谁把这里「对齐忌口」改成 `isNullOrBlank`，本文件的 `本书空串_关闭` 两例会立刻变红。
 */
class StoryCraftSectionsTest {

    private fun story(prompts: CustomStoryPrompts? = null) = StoryEntity(
        id = "s1",
        title = "书",
        customPromptsJson = prompts?.let { CustomStoryPrompts.encode(it) },
    )

    // ── 场面节拍九宫格：本书 {null, "", 文本} × 全局 {null, "", 文本} ──

    @Test fun 节拍_本书null_全局null_落出厂默认() {
        assertEquals(
            StoryCraftSections.SCENE_BEATS_DEFAULT,
            StoryCraftSections.resolvedSceneBeats(story(), globalOverride = null),
        )
        // 连 customPromptsJson 都没有的老书同样落默认
        assertEquals(
            StoryCraftSections.SCENE_BEATS_DEFAULT,
            StoryCraftSections.resolvedSceneBeats(story(CustomStoryPrompts(writerIdentity = "身份")), globalOverride = null),
        )
    }

    @Test fun 节拍_本书null_全局空串_全局关闭不注入() {
        assertNull(StoryCraftSections.resolvedSceneBeats(story(), globalOverride = ""))
        assertNull("纯空白的全局值同样算关闭", StoryCraftSections.resolvedSceneBeats(story(), globalOverride = "   "))
    }

    @Test fun 节拍_本书null_全局有值_用全局() {
        assertEquals(
            "全局节拍",
            StoryCraftSections.resolvedSceneBeats(story(), globalOverride = "全局节拍"),
        )
    }

    @Test fun 节拍_本书空串_关闭_三种全局都不注入() {
        val closed = story(CustomStoryPrompts(sceneBeats = ""))
        assertNull("本书关闭优先于全局默认", StoryCraftSections.resolvedSceneBeats(closed, globalOverride = null))
        assertNull(StoryCraftSections.resolvedSceneBeats(closed, globalOverride = ""))
        assertNull("本书关闭不许被全局值救活", StoryCraftSections.resolvedSceneBeats(closed, globalOverride = "全局节拍"))
    }

    @Test fun 节拍_本书纯空白_同样算关闭() {
        val blank = story(CustomStoryPrompts(sceneBeats = "  \n "))
        assertNull(StoryCraftSections.resolvedSceneBeats(blank, globalOverride = null))
        assertNull(StoryCraftSections.resolvedSceneBeats(blank, globalOverride = "全局节拍"))
    }

    @Test fun 节拍_本书有值_三种全局都用本书值() {
        val covered = story(CustomStoryPrompts(sceneBeats = "  本书节拍  "))
        assertEquals("本书节拍", StoryCraftSections.resolvedSceneBeats(covered, globalOverride = null))
        assertEquals("本书节拍", StoryCraftSections.resolvedSceneBeats(covered, globalOverride = ""))
        assertEquals("本书节拍", StoryCraftSections.resolvedSceneBeats(covered, globalOverride = "全局节拍"))
    }

    // ── 口味画像六格（同构，但没有出厂默认）──

    @Test fun 画像_两层皆空_不注入() {
        assertNull("没有出厂默认：谁都没填 = 整段不出现", StoryCraftSections.resolvedTasteProfile(story(), globalOverride = null))
        assertNull(StoryCraftSections.resolvedTasteProfile(story(), globalOverride = ""))
    }

    @Test fun 画像_本书null_全局有值_用全局() {
        assertEquals("全局画像", StoryCraftSections.resolvedTasteProfile(story(), globalOverride = "全局画像"))
    }

    @Test fun 画像_本书空串_关闭_不许被全局救活() {
        val closed = story(CustomStoryPrompts(tasteProfile = ""))
        assertNull(StoryCraftSections.resolvedTasteProfile(closed, globalOverride = null))
        assertNull(StoryCraftSections.resolvedTasteProfile(closed, globalOverride = "全局画像"))
    }

    @Test fun 画像_本书有值_覆盖全局() {
        val covered = story(CustomStoryPrompts(tasteProfile = " 本书画像 "))
        assertEquals("本书画像", StoryCraftSections.resolvedTasteProfile(covered, globalOverride = "全局画像"))
        assertEquals("本书画像", StoryCraftSections.resolvedTasteProfile(covered, globalOverride = null))
    }

    // ── 出厂默认文本本身（提案 §3.1 物料 A·逐字锁定）──

    @Test fun 出厂默认主节拍_逐字锁定() {
        // 双保险 pin：整段字面量「重新打字」 + 结构性断言（四拍齐全、篇幅要求在末行）
        val expected = "## 场面节拍（重点场景按此展开）\n" +
            "每一场重点场景按四拍展开，宁慢勿快：\n" +
            "1. 铺垫：先给足氛围与由头——环境、眼神、试探、心理活动，把「就要发生」的张力拉满再进入正题；张力没到位就不推进。\n" +
            "2. 升温：循序渐进，每一步都写透细节（环境、动作、气息、声音）与人物的反应变化，不许三两句就跳到高潮。\n" +
            "3. 高潮：本场的重心，篇幅给足；人物的反应、神态、语言是画面的核心载体，动作要与心理交织，不许写成流水账。\n" +
            "4. 余韵：事后要有情绪落点——对话、心理变化，给这一场一个收尾，不许戛然而止。\n" +
            "整场篇幅占本章至少一半；节拍允许因剧情自然变形，但「铺垫充分、过程写透、有余韵」三点不许省。"
        assertEquals(expected, StoryCraftSections.SCENE_BEATS_DEFAULT)
        assertTrue("标题行必须是段首（装配时直接整段注入，不另加标题）", StoryCraftSections.SCENE_BEATS_DEFAULT.startsWith("## "))
        assertTrue("末尾不留空行（空行由装配点统一补）", !StoryCraftSections.SCENE_BEATS_DEFAULT.endsWith("\n"))
    }
}
