package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 宠物状态「最近买的东西」去重纯函数（[PetInventoryPromptService.dedupeByMention]，1:1 iOS
 * `recentPetShopItems` 去重段）。断言反推 iOS：无独白 → 原样（含重复名不去重）；独白正文 contains 物品名 → 剔除。
 */
class PetInventoryPromptServiceTest {

    @Test
    fun no_pet_messages_keeps_all_including_duplicates() {
        // iOS 不对名字本身去重，只在有独白时剔除被提过的
        val items = listOf("小饼干", "小饼干", "金色小皇冠")
        assertEquals(items, PetInventoryPromptService.dedupeByMention(items, emptyList()))
    }

    @Test
    fun mentioned_item_is_filtered_out() {
        val items = listOf("金色小皇冠", "特级罐头")
        // 宠物独白「主人给我戴了金色小皇冠，好喜欢!」contains 皇冠 → 剔除皇冠，保留罐头
        val mentioned = listOf("主人给我戴了金色小皇冠，好喜欢!")
        assertEquals(listOf("特级罐头"), PetInventoryPromptService.dedupeByMention(items, mentioned))
    }

    @Test
    fun multiple_mentions_filter_each() {
        val items = listOf("小饼干", "金色小皇冠", "特级罐头")
        val mentioned = listOf(
            "吃到小饼干了，开心~",
            "戴着金色小皇冠感觉超棒!",
        )
        // 小饼干 + 皇冠被提过 → 仅剩特级罐头
        assertEquals(listOf("特级罐头"), PetInventoryPromptService.dedupeByMention(items, mentioned))
    }

    @Test
    fun unmentioned_duplicates_preserved() {
        val items = listOf("特级罐头", "特级罐头")
        val mentioned = listOf("主人给我戴了金色小皇冠，好喜欢!") // 不含罐头
        assertEquals(items, PetInventoryPromptService.dedupeByMention(items, mentioned))
    }
}
