package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 宠物反应文案（1:1 iOS PetReactionTexts）。断言反推 iOS：5 性格 × 5 操作 × 4 条 = 100 条、具体池字面、
 * randomReaction 取值落在对应池内。
 */
class PetReactionTextsTest {

    @Test fun `total is 5x5x4 equals 100`() {
        assertEquals(5, PetReactionTexts.reactions.size)
        var total = 0
        for ((_, byAction) in PetReactionTexts.reactions) {
            assertEquals(5, byAction.size)
            for ((_, texts) in byAction) {
                assertEquals(4, texts.size)
                total += texts.size
            }
        }
        assertEquals(100, total)
    }

    @Test fun `lively feed pool matches iOS`() {
        assertEquals(
            listOf("好吃好吃！再来一份！", "吃饱啦～出去玩吧！", "嗷呜～真香！", "吃完还想吃！"),
            PetReactionTexts.reactions[PetPersonalityType.LIVELY]!![PetReactionTexts.ReactionAction.FEED],
        )
    }

    @Test fun `timid clean pool matches iOS`() {
        assertEquals(
            listOf("轻一点…", "水不会太烫吧…", "…其实挺舒服的", "谢谢…洗干净了"),
            PetReactionTexts.reactions[PetPersonalityType.TIMID]!![PetReactionTexts.ReactionAction.CLEAN],
        )
    }

    @Test fun `randomReaction returns member of the pool for every combo`() {
        for (p in PetPersonalityType.entries) {
            for (a in PetReactionTexts.ReactionAction.entries) {
                val pool = PetReactionTexts.reactions[p]!![a]!!
                val picked = PetReactionTexts.randomReaction(p, a, Random(42))
                assertTrue("$p/$a -> $picked", picked in pool)
            }
        }
    }
}
