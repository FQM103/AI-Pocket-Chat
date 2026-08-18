package com.situ.aichat.ui.character

import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1：角色信息折叠卡收起态导览行的字段选择（微图纸 2026-07-10-资料页卡序重排与角色信息折叠 §5）。
 * 规格独立反推：导览行必须与卡内实际渲染的字段严格一致——渲染条件 = `isNotEmpty`（AccentInfoRow/
 * AccentTagRow 同口径），顺序 = 展示顺序（外貌→背景→说话风格→口头禅→兴趣→说话示例）。
 */
class CharInfoSummaryTest {

    private fun character(
        appearance: String = "",
        backstory: String = "",
        speaking: String = "",
        catchphrases: String = "",
        interests: String = "",
        examples: String = "",
    ) = CharacterEntity(
        uuid = "u",
        name = "n",
        creationDate = 0L,
        appearanceDescription = appearance,
        backstory = backstory,
        speakingStyle = speaking,
        catchphrases = catchphrases,
        initialInterests = interests,
        exampleDialogues = examples,
    )

    @Test
    fun `全空 - 返回空表(即整卡不渲染的判定源)`() {
        assertEquals(emptyList<Int>(), charInfoSummaryFieldRes(character()))
    }

    @Test
    fun `全满 - 六字段按展示顺序`() {
        val got = charInfoSummaryFieldRes(
            character(
                appearance = "齐肩栗色短发",
                backstory = "自由插画师",
                speaking = "温柔爱用波浪号",
                catchphrases = "好呀好呀，嘿嘿",
                interests = "水彩，老电影",
                examples = "想到你啦～",
            ),
        )
        assertEquals(
            listOf(
                R.string.profile_charinfo_appearance,
                R.string.profile_charinfo_backstory,
                R.string.profile_charinfo_speaking,
                R.string.profile_charinfo_catchphrase,
                R.string.profile_charinfo_interests,
                R.string.profile_charinfo_examples,
            ),
            got,
        )
    }

    @Test
    fun `部分非空 - 只含对应字段且保持展示顺序`() {
        val got = charInfoSummaryFieldRes(character(backstory = "小城长大", interests = "逛菜市场"))
        assertEquals(
            listOf(R.string.profile_charinfo_backstory, R.string.profile_charinfo_interests),
            got,
        )
    }

    @Test
    fun `单字段 - 只有说话示例`() {
        assertEquals(
            listOf(R.string.profile_charinfo_examples),
            charInfoSummaryFieldRes(character(examples = "今天路过花店")),
        )
    }

    @Test
    fun `空白字符串按非空计 - 与卡内渲染条件 isNotEmpty 同口径`() {
        assertEquals(
            listOf(R.string.profile_charinfo_appearance),
            charInfoSummaryFieldRes(character(appearance = " ")),
        )
    }
}
