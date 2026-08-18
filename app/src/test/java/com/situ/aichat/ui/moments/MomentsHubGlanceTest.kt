package com.situ.aichat.ui.moments

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.StoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 圈子枢纽实时状态派生纯函数单测（契约 §3 规格反推）：
 * 故事三段判定 + 无故事兜底；宠物「最需照顾」优先级（生病>饿>难过>满足>开心）。
 */
class MomentsHubGlanceTest {

    // ── storyHubStatus ──
    @Test fun story_null_isNone() {
        assertEquals(StoryHubStatus.None, storyHubStatus(null))
    }

    @Test fun story_withLatestChapter_isChapter() {
        val s = StoryEntity(cachedLatestChapterNumber = 12, cachedLatestChapterTitle = "雨夜的来信")
        assertEquals(StoryHubStatus.Chapter(12, "雨夜的来信"), storyHubStatus(s))
    }

    @Test fun story_blankChapterTitle_fallsThrough() {
        // 有章号但标题空 → 不算 Chapter → NoChapter。
        val s = StoryEntity(cachedLatestChapterNumber = 3, cachedLatestChapterTitle = "")
        assertEquals(StoryHubStatus.NoChapter, storyHubStatus(s))
    }

    @Test fun story_noChapter_isNoChapter() {
        val s = StoryEntity(cachedLatestChapterNumber = null)
        assertEquals(StoryHubStatus.NoChapter, storyHubStatus(s))
    }

    /**
     * 卷二·单模式化：原第三支「有连载上限、暂无章节 →『计划 N 章』」随有限模式退役删除（原例
     * `story_noChapter_withLimit_isPlanned` 删除属预期）。迁移 39→40 后 maxChapters 恒 null，
     * 但即便脏数据残留一个值，也一律落 NoChapter——此例即该退役口径的看门狗。
     */
    @Test fun story_noChapter_evenWithStaleMaxChapters_isNoChapter() {
        val s = StoryEntity(cachedLatestChapterNumber = null, maxChapters = 10)
        assertEquals(StoryHubStatus.NoChapter, storyHubStatus(s))
    }

    // ── pickNeediestPet ──（pet() 默认 happiness=80 → HAPPY；hunger=0 / health=100 / neglect=none）
    private fun pet(name: String, hunger: Int = 0, happiness: Int = 80, neglect: String = "none") =
        CharacterPetEntity(name = name, hunger = hunger, happiness = happiness, neglectPhaseRaw = neglect)

    @Test fun pet_empty_isNull() {
        assertNull(pickNeediestPet(emptyList()))
    }

    @Test fun pet_single_isThatPet() {
        val p = pet("开开")
        assertSame(p, pickNeediestPet(listOf(p)))
    }

    @Test fun pet_hungryBeatsHappy() {
        val happy = pet("开开")
        val hungry = pet("饿饿", hunger = 80)
        assertSame(hungry, pickNeediestPet(listOf(happy, hungry)))
    }

    @Test fun pet_sickBeatsHungry() {
        val hungry = pet("饿饿", hunger = 80)
        val sick = pet("病病", neglect = "sick")
        assertSame(sick, pickNeediestPet(listOf(hungry, sick)))
    }

    @Test fun pet_sadBeatsContent() {
        val content = pet("安安", happiness = 50)
        val sad = pet("丧丧", happiness = 10)
        assertSame(sad, pickNeediestPet(listOf(content, sad)))
    }
}
