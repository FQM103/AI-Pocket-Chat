package com.situ.aichat.data.model

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryChatInfluenceWeight
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.story.StoryUpdateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「我的模板」载荷（图纸四 §3.2 · §5 E6/E7 · §7 T1-2）：抽取 → 编码 → 解码 的逐字段往返。
 *
 * 断言从图纸规格独立反推：13 个字段**逐个点名**核对（不是 `assertEquals(payload, decoded)` 一把梭），
 * 这样将来谁在 [UserStoryTemplatePayload.fromStory] 里漏抄一个字段，红的是那个字段的名字。
 */
class UserStoryTemplatePayloadTest {

    /** 每个字段都给一个**与默认值不同**的哨兵值——默认值会让「漏抄」假绿。 */
    private val story = StoryEntity(
        id = "s1",
        title = "对面楼的灯",
        genre = "赛博修真",
        writingStyle = "哥特暗黑",
        narrativePerson = StoryNarrativePerson.FIRST,
        chapterLengthPreference = 2600,
        chatInfluenceWeight = StoryChatInfluenceWeight.HEAVY,
        worldSetting = "民国上海",
        plotDirection = "重逢",
        updateMode = StoryUpdateMode.CHASE,
        unlockHour = 7,
        unlockMinute = 45,
        customPromptsJson = """{"writerIdentity":"你是一位…","bannedExpressions":"忌口","chapterChoicesEnabled":false}""",
    )

    @Test fun 抽取_十三字段逐个落位() {
        val p = UserStoryTemplatePayload.fromStory(story)
        assertEquals("赛博修真", p.genre)
        assertTrue("题材不在内置目录里 → 判为自定义题材", p.isCustomGenre)
        assertEquals("哥特暗黑", p.writingStyle)
        assertEquals(StoryNarrativePerson.FIRST, p.narrativePerson)
        assertEquals(2600, p.chapterLengthPreference)
        assertEquals(StoryChatInfluenceWeight.HEAVY, p.chatInfluenceWeight)
        assertEquals("民国上海", p.worldSetting)
        assertEquals("重逢", p.plotDirection)
        assertEquals(StoryUpdateMode.CHASE, p.updateMode)
        assertEquals(7, p.unlockHour)
        assertEquals(45, p.unlockMinute)
        assertEquals(story.customPromptsJson, p.customPromptsJson)
        assertNull("参考题材从不落库，抽取端只能得 null（图纸 §11 D-1）", p.referenceGenre)
    }

    @Test fun 抽取_预设题材不判自定义() {
        assertTrue(!UserStoryTemplatePayload.fromStory(story.copy(genre = "言情")).isCustomGenre)
        assertTrue(!UserStoryTemplatePayload.fromStory(story.copy(genre = "日常")).isCustomGenre)
    }

    @Test fun 抽取_有意不含书名与角色() {
        // 模板是「设定包」不是「书」：payload 上根本没有书名字段，改坏了这条编译就红。
        val encoded = UserStoryTemplatePayload.encode(UserStoryTemplatePayload.fromStory(story))
        assertTrue("串里不该出现书名", !encoded.contains("对面楼的灯"))
    }

    @Test fun 往返_编码解码后十三字段逐个不变() {
        val p = UserStoryTemplatePayload.fromStory(story)
        val back = UserStoryTemplatePayload.decode(UserStoryTemplatePayload.encode(p))!!
        assertEquals(p.genre, back.genre)
        assertEquals(p.isCustomGenre, back.isCustomGenre)
        assertEquals(p.writingStyle, back.writingStyle)
        assertEquals(p.narrativePerson, back.narrativePerson)
        assertEquals(p.chapterLengthPreference, back.chapterLengthPreference)
        assertEquals(p.chatInfluenceWeight, back.chatInfluenceWeight)
        assertEquals(p.worldSetting, back.worldSetting)
        assertEquals(p.plotDirection, back.plotDirection)
        assertEquals(p.updateMode, back.updateMode)
        assertEquals(p.unlockHour, back.unlockHour)
        assertEquals(p.unlockMinute, back.unlockMinute)
        assertEquals("忌口与两个格式开关全在这一串里，必须一个字不差", p.customPromptsJson, back.customPromptsJson)
        assertEquals(p.referenceGenre, back.referenceGenre)
    }

    @Test fun 往返_可空字段为null也不丢() {
        val p = UserStoryTemplatePayload.fromStory(
            story.copy(worldSetting = null, plotDirection = null, customPromptsJson = null),
        )
        val back = UserStoryTemplatePayload.decode(UserStoryTemplatePayload.encode(p))!!
        assertNull(back.worldSetting)
        assertNull(back.plotDirection)
        assertNull(back.customPromptsJson)
    }

    @Test fun 解码_损坏或空串返回null不抛() {
        assertNull(UserStoryTemplatePayload.decode(null))
        assertNull(UserStoryTemplatePayload.decode(""))
        assertNull(UserStoryTemplatePayload.decode("{不是 JSON"))
        assertNull("字段类型不对也只 null 不炸", UserStoryTemplatePayload.decode("""{"unlockHour":"七点"}"""))
    }

    @Test fun 解码_未知键不炸_老模板缺键回默认() {
        val back = UserStoryTemplatePayload.decode("""{"genre":"言情","futureKey":123}""")!!
        assertEquals("言情", back.genre)
        assertEquals("缺键回默认值（加字段零迁移的前提）", StoryUpdateMode.FREE, back.updateMode)
        assertEquals(20, back.unlockHour)
    }

    @Test fun 上限常量锁定二十() {
        assertEquals(20, UserStoryTemplatePayload.MAX_USER_TEMPLATES)
    }
}
