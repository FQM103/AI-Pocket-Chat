package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宠物聊天气泡文案与节流常量（1:1 iOS `PetChatBubbleService`）。断言反推 iOS：dailyLimit=3、消耗品/装扮文案
 * 各 5 条、占位插入物品名、随机取值落在池内。
 */
class PetChatBubbleServiceTest {

    @Test
    fun daily_limit_is_3() {
        assertEquals(3, PetChatBubbleService.DAILY_LIMIT)
    }

    @Test
    fun consumable_texts_are_5_with_item_name() {
        val texts = PetChatBubbleService.consumableTexts("小饼干")
        assertEquals(5, texts.size)
        assertTrue(texts.all { it.contains("小饼干") })
        assertEquals("主人喂我吃了小饼干，好好吃!", texts[0])
        assertEquals("吃到小饼干了，开心~", texts[4])
    }

    @Test
    fun equip_texts_are_5_with_item_name() {
        val texts = PetChatBubbleService.equipTexts("金色小皇冠")
        assertEquals(5, texts.size)
        assertTrue(texts.all { it.contains("金色小皇冠") })
        assertEquals("主人给我戴了金色小皇冠，好喜欢!", texts[0])
        assertEquals("戴着金色小皇冠感觉超棒!", texts[4])
    }

    @Test
    fun random_pick_is_in_pool() {
        repeat(20) {
            assertTrue(PetChatBubbleService.randomConsumableText("特级罐头") in PetChatBubbleService.consumableTexts("特级罐头"))
            assertTrue(PetChatBubbleService.randomEquipText("毛绒围巾") in PetChatBubbleService.equipTexts("毛绒围巾"))
        }
    }
}
