package com.situ.aichat.ui.story

import com.situ.aichat.story.StoryChapterLength
import com.situ.aichat.story.StoryChatInfluenceWeight
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.story.StoryRoleType
import com.situ.aichat.story.StoryTemplate
import com.situ.aichat.story.StoryTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开书装配 T2（ST6·契约 §11 / §3.1）：模板 + 选角 → StoryCreationForm 字段逐项对表 +
 * 「我也入场」人称覆盖 + J3 默认（权重/连载/章长不问）。纯函数，无 Android/DB 依赖。
 */
class StoryTemplateAssemblyTest {

    private val sample = StoryTemplate(
        id = "t", title = "书名", tagline = "钩子", genre = "科幻", writingStyle = "严肃文学",
        narrativePerson = StoryNarrativePerson.THIRD, worldSetting = "世界观", plotDirection = "剧情",
        roleHint = "提示", coverMotif = "意象",
    )

    @Test
    fun 入场_字段逐项映射_人称吃模板值() {
        val roles = mapOf("c1" to StoryRoleType.PROTAGONIST)
        val f = StoryTemplateAssembly.toCreationForm(sample, roles, includeUserRole = true)

        assertEquals("科幻", f.selectedGenre)
        assertFalse("模板流恒预设题材", f.isCustomGenre)
        assertEquals("严肃文学", f.writingStyle)
        assertEquals("世界观", f.worldSetting)
        assertEquals("剧情", f.plotDirection)
        assertEquals("书名", f.presetTitle)
        assertEquals(roles, f.selectedRoles)
        assertTrue(f.includeUserRole)
        assertEquals("入场默认以主角身份", StoryRoleType.PROTAGONIST, f.userRoleType)
        assertEquals("入场 = 吃模板人称（此模板 third）", StoryNarrativePerson.THIRD, f.narrativePerson)
        // J3：权重 / 章长不问，吃默认（卷二·单模式化：连载模式字段已随枚举整体退役，故不再断言）
        assertEquals(StoryChatInfluenceWeight.MEDIUM, f.chatInfluenceWeight)
        assertEquals(StoryChapterLength.MEDIUM, f.chapterLength)
    }

    @Test
    fun 不入场_人称回落旁观第三人称() {
        // 第二人称模板：入场=你，不入场=旁观 third（照 mockup 屏三）
        val second = sample.copy(narrativePerson = StoryNarrativePerson.SECOND)
        val onIn = StoryTemplateAssembly.toCreationForm(second, emptyMap(), includeUserRole = true)
        val offIn = StoryTemplateAssembly.toCreationForm(second, emptyMap(), includeUserRole = false)
        assertEquals(StoryNarrativePerson.SECOND, onIn.narrativePerson)
        assertEquals(StoryNarrativePerson.THIRD, offIn.narrativePerson)
        assertFalse(offIn.includeUserRole)
    }

    @Test
    fun 真实模板_全部可装配且原样携带书名题材人称() {
        StoryTemplates.all.forEach { t ->
            val f = StoryTemplateAssembly.toCreationForm(t, mapOf("c1" to StoryRoleType.PROTAGONIST), includeUserRole = true)
            assertEquals("书名须原样带入：${t.id}", t.title, f.presetTitle)
            assertEquals("题材须原样带入：${t.id}", t.genre, f.selectedGenre)
            assertEquals("入场人称吃模板：${t.id}", t.narrativePerson, f.narrativePerson)
        }
    }
}
