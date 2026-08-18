package com.situ.aichat.ui.moments

import com.situ.aichat.data.local.entity.CharacterPetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界卡信息条段派生纯函数单测（W11 图纸 §7 T1·断言从 §3 段语义独立反推）：
 * 段序锁死 Around→Pending→PetNeeds；人数 >0 才出；宠物仅 SICK/HUNGRY/SAD/RAN_AWAY 出段、HAPPY/CONTENT 不出。
 */
class WorldCardInfoTest {

    // pet() 默认 happiness=80 → HAPPY；hunger=0 / neglect=none（同 MomentsHubGlanceTest 惯例）。
    private fun pet(name: String, hunger: Int = 0, happiness: Int = 80, neglect: String = "none") =
        CharacterPetEntity(name = name, hunger = hunger, happiness = happiness, neglectPhaseRaw = neglect)

    // ── 全空 → 无段（E3）──
    @Test fun allEmpty_noSegments() {
        assertTrue(WorldCardInfo.buildSegments(0, 0, null).isEmpty())
    }

    // ── 三段全有·段序锁死 Around → Pending → PetNeeds ──
    @Test fun allThree_inLockedOrder() {
        assertEquals(
            listOf(
                InfoSegment.Around(3),
                InfoSegment.Pending(1),
                InfoSegment.PetNeeds("团子", PetNeedKind.HUNGRY),
            ),
            WorldCardInfo.buildSegments(3, 1, pet("团子", hunger = 80)),
        )
    }

    // ── 各自单独出 ──
    @Test fun onlyAround() {
        assertEquals(listOf(InfoSegment.Around(5)), WorldCardInfo.buildSegments(5, 0, null))
    }

    @Test fun onlyPending() {
        assertEquals(listOf(InfoSegment.Pending(2)), WorldCardInfo.buildSegments(0, 2, null))
    }

    @Test fun onlyPetNeed_countsZeroOmitted() {
        assertEquals(
            listOf(InfoSegment.PetNeeds("咪咪", PetNeedKind.SICK)),
            WorldCardInfo.buildSegments(0, 0, pet("咪咪", neglect = "sick")),
        )
    }

    // ── 人数段 0 省略但宠物段照出（省略是每段独立判定）──
    @Test fun aroundOmitted_pendingAndPetKept() {
        assertEquals(
            listOf(InfoSegment.Pending(1), InfoSegment.PetNeeds("旺财", PetNeedKind.SAD)),
            WorldCardInfo.buildSegments(0, 1, pet("旺财", happiness = 10)),
        )
    }

    // ── 宠物四态出段（E4）·名字随段带出 ──
    @Test fun petSick_segment() = assertNeed("咪咪", pet("咪咪", neglect = "sick"), PetNeedKind.SICK)

    @Test fun petRanAway_segment() = assertNeed("阿黄", pet("阿黄", neglect = "ranAway"), PetNeedKind.RAN_AWAY)

    @Test fun petHungry_segment() = assertNeed("小七", pet("小七", hunger = 90), PetNeedKind.HUNGRY)

    @Test fun petSad_segment() = assertNeed("团团", pet("团团", happiness = 10), PetNeedKind.SAD)

    // ── HAPPY / CONTENT 不出段（E4·信息条只顶「需要你」）──
    @Test fun petHappy_noSegment() {
        assertTrue(WorldCardInfo.buildSegments(0, 0, pet("乐乐", happiness = 90)).isEmpty())
    }

    @Test fun petContent_noSegment() {
        assertTrue(WorldCardInfo.buildSegments(0, 0, pet("安安", happiness = 50)).isEmpty())
    }

    // ── W12.5 蛋段「蛋要孵出来了」（决策 42④·§3/§4.4/§5）──

    // 可孵化单独出（无宠无人数时）。
    @Test fun eggHatchable_only() {
        assertEquals(listOf(InfoSegment.EggHatchable), WorldCardInfo.buildSegments(0, 0, null, eggHatchable = true))
    }

    // 段序锁死：Around → Pending → EggHatchable（蛋段永远在末）。
    @Test fun eggHatchable_lockedOrder_afterCounts() {
        assertEquals(
            listOf(InfoSegment.Around(2), InfoSegment.Pending(1), InfoSegment.EggHatchable),
            WorldCardInfo.buildSegments(2, 1, null, eggHatchable = true),
        )
    }

    // E9 单可点段：有 needs-attention 宠 + 可孵化蛋 → 宠物段优先、蛋段不出（绝不叠段）。
    @Test fun petNeeds_preempts_egg() {
        assertEquals(
            listOf(InfoSegment.PetNeeds("团子", PetNeedKind.HUNGRY)),
            WorldCardInfo.buildSegments(0, 0, pet("团子", hunger = 80), eggHatchable = true),
        )
    }

    // 乐宠不产 PetNeeds → 让位蛋段（只顶「需要你」，乐宠不算）。
    @Test fun happyPet_yields_to_egg() {
        assertEquals(
            listOf(InfoSegment.EggHatchable),
            WorldCardInfo.buildSegments(0, 0, pet("乐乐", happiness = 90), eggHatchable = true),
        )
    }

    // E10 无宠无蛋（可孵化=false）→ 零宠物维度段（仅人数·quiet 哲学）。
    @Test fun noPet_noEgg_noPetDimensionSegment() {
        val segs = WorldCardInfo.buildSegments(2, 1, null, eggHatchable = false)
        assertEquals(listOf(InfoSegment.Around(2), InfoSegment.Pending(1)), segs)
        assertTrue(segs.none { it is InfoSegment.PetNeeds || it is InfoSegment.EggHatchable })
    }

    private fun assertNeed(name: String, pet: CharacterPetEntity, kind: PetNeedKind) {
        assertEquals(
            InfoSegment.PetNeeds(name, kind),
            WorldCardInfo.buildSegments(0, 0, pet).single(),
        )
    }
}
