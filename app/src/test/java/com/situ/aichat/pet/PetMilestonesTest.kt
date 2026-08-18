package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * [PetMilestones] 单测——断言反推 iOS `PetMilestones`：100 个成就的数量/分类构成、ID 唯一、`achievedIDs` 各 kind
 * 阈值边界（>=）、综合成就与基础成就的同阈值共解锁、evolved 仅 isSpecial、全解锁=100，以及 [daysSinceAdoption]
 * 的日历整天语义（无 +1）。
 */
class PetMilestonesTest {

    private val noneAchieved = PetMilestones.achievedIDs(0, 0, 0, 0, false, 0, 0)
    private val allAchieved = PetMilestones.achievedIDs(100000, 100000, 100, 100, true, 100000, 100000)

    // MARK: - 数据结构

    @Test fun totalIsExactly100() {
        assertEquals(100, PetMilestones.all.size)
    }

    @Test fun idsAreUnique() {
        assertEquals(PetMilestones.all.size, PetMilestones.all.map { it.id }.toSet().size)
    }

    @Test fun categoryCountsMatchIos() {
        fun count(pred: (PetMilestones.MilestoneKind) -> Boolean) = PetMilestones.all.count { pred(it.kind) }
        // 14 days + 14 interactions + 12 play + 20 souvenirs + 14 growth + 4 tricks + 1 evolved = 79；其余 21 为综合(复用 kind)。
        assertEquals(14 + 3, count { it is PetMilestones.MilestoneKind.Days })          // 14 基础 + 3 综合(250/400/600)
        assertEquals(14 + 4, count { it is PetMilestones.MilestoneKind.Interactions })  // 14 + 4 综合(10/400/600/800)
        assertEquals(12 + 4, count { it is PetMilestones.MilestoneKind.PlayCount })     // 12 + 4 综合(30/400/600/800)
        assertEquals(20, count { it is PetMilestones.MilestoneKind.Souvenirs })
        assertEquals(14 + 10, count { it is PetMilestones.MilestoneKind.GrowthPoints }) // 14 + 10 综合
        assertEquals(4, count { it is PetMilestones.MilestoneKind.Tricks })
        assertEquals(1, count { it == PetMilestones.MilestoneKind.Evolved })
    }

    @Test fun noEmptyNamesOrEmojis() {
        assertTrue(PetMilestones.all.all { it.name.isNotBlank() && it.emoji.isNotBlank() && it.id.isNotBlank() })
    }

    // MARK: - achievedIDs 边界

    @Test fun zeroInputsUnlockNothing() {
        assertTrue(noneAchieved.isEmpty())
    }

    @Test fun maxInputsUnlockAll100() {
        assertEquals(100, allAchieved.size)
    }

    @Test fun daysThresholdInclusive() {
        // days7 = days(7)：6 天不解锁，7 天解锁；同时 days1/days3 解锁，days14 不解锁。
        val at6 = PetMilestones.achievedIDs(6, 0, 0, 0, false, 0, 0)
        assertFalse(at6.contains("days7"))
        assertTrue(at6.contains("days3"))
        val at7 = PetMilestones.achievedIDs(7, 0, 0, 0, false, 0, 0)
        assertTrue(at7.contains("days7"))
        assertTrue(at7.contains("days1"))
        assertTrue(at7.contains("days3"))
        assertFalse(at7.contains("days14"))
    }

    @Test fun interactionsThresholdAndComposite() {
        // interactions=10 解锁 interact5/interact10 + 综合 days2_int10（同阈值 interactions(10)）。
        val at10 = PetMilestones.achievedIDs(0, 10, 0, 0, false, 0, 0)
        assertTrue(at10.contains("interact5"))
        assertTrue(at10.contains("interact10"))
        assertTrue(at10.contains("days2_int10"))
        assertFalse(at10.contains("interact25"))
        assertFalse(at10.contains("int400"))
    }

    @Test fun playCountThresholdAndComposite() {
        // play=30 解锁 play5/10/20 + 综合 play30_d14（playCount(30)），不解锁 play50。
        val at30 = PetMilestones.achievedIDs(0, 0, 0, 0, false, 30, 0)
        assertTrue(at30.contains("play20"))
        assertTrue(at30.contains("play30_d14"))
        assertFalse(at30.contains("play50"))
    }

    @Test fun growthThresholdAndComposite() {
        // growth=50 解锁 growth10/25/50 + 综合 growth50_d7（growthPoints(50)），不解锁 growth100。
        val at50 = PetMilestones.achievedIDs(0, 0, 0, 0, false, 0, 50)
        assertTrue(at50.contains("growth50"))
        assertTrue(at50.contains("growth50_d7"))
        assertFalse(at50.contains("growth100"))
        assertFalse(at50.contains("growth150"))
    }

    @Test fun souvenirsThreshold() {
        val at50 = PetMilestones.achievedIDs(0, 0, 0, 50, false, 0, 0)
        assertTrue(at50.contains("souv50"))
        assertTrue(at50.contains("souv1"))
        assertFalse(at50.contains("souv60"))
        assertFalse(at50.contains("souv100"))
    }

    @Test fun tricksThreshold() {
        val at2 = PetMilestones.achievedIDs(0, 0, 2, 0, false, 0, 0)
        assertTrue(at2.contains("trick1"))
        assertTrue(at2.contains("trick2"))
        assertFalse(at2.contains("trick3"))
    }

    @Test fun evolvedOnlyWhenSpecial() {
        assertFalse(PetMilestones.achievedIDs(0, 0, 0, 0, false, 0, 0).contains("evolved"))
        assertTrue(PetMilestones.achievedIDs(0, 0, 0, 0, true, 0, 0).contains("evolved"))
        // isSpecial 不应误解锁其它 kind 的成就。
        assertEquals(setOf("evolved"), PetMilestones.achievedIDs(0, 0, 0, 0, true, 0, 0))
    }

    @Test fun crossKindIndependence() {
        // 仅 growthPoints 高，不应解锁 days/interactions/play/souvenir/trick 的成就。
        val onlyGrowth = PetMilestones.achievedIDs(0, 0, 0, 0, false, 0, 10000)
        assertTrue(onlyGrowth.all { id ->
            PetMilestones.all.first { it.id == id }.kind is PetMilestones.MilestoneKind.GrowthPoints
        })
        assertEquals(24, onlyGrowth.size) // 14 基础 growth + 10 综合 growth
    }

    // MARK: - daysSinceAdoption（日历整天·无 +1）

    @Test fun daysSinceAdoption_floorsToWholeDays() {
        val utc = ZoneId.of("UTC")
        val day = 86_400_000L
        val base = 1_700_000_000_000L
        assertEquals(0, PetMilestones.daysSinceAdoption(base, base, utc))                 // 同刻 = 0（无 +1）
        assertEquals(0, PetMilestones.daysSinceAdoption(base, base + day - 1, utc))       // 差 1ms 不足整天 = 0
        assertEquals(1, PetMilestones.daysSinceAdoption(base, base + day, utc))           // 整 1 天 = 1
        assertEquals(7, PetMilestones.daysSinceAdoption(base, base + 7 * day, utc))       // 7 天
        assertEquals(0, PetMilestones.daysSinceAdoption(base, base - day, utc))           // 未来领养（now<adopted）钳 0
    }

    // MARK: - P1-34 newlyUnlocked（成就解锁 diff·oldSet+newSet→批 payload|null）

    @Test
    fun `newlyUnlocked null baseline seeds silently`() {
        // 回填安全：老宠物/新宠物首算（基线缺省 null）绝不把历史成就当新解锁批量补播。
        assertEquals(null, PetMilestones.newlyUnlocked(null, setOf("days1", "interact5")))
    }

    @Test
    fun `newlyUnlocked no change returns null`() {
        assertEquals(null, PetMilestones.newlyUnlocked(setOf("days1"), setOf("days1")))
        assertEquals(null, PetMilestones.newlyUnlocked(emptySet(), emptySet()))
    }

    @Test
    fun `newlyUnlocked single unlock carries name for single-line toast`() {
        val u = PetMilestones.newlyUnlocked(setOf("days1"), setOf("days1", "interact5"))!!
        assertEquals(1, u.size)
        assertEquals("interact5", u[0].id)
        assertEquals("初次互动", u[0].name)
    }

    @Test
    fun `newlyUnlocked multi unlock keeps all-list definition order`() {
        // 注意 old=emptySet() ≠ null（已 seed 且零成就）；顺序按 PetMilestones.all 定义序。
        val u = PetMilestones.newlyUnlocked(emptySet(), setOf("play5", "interact10", "interact5"))!!
        assertEquals(listOf("interact5", "interact10", "play5"), u.map { it.id })
    }

    @Test
    fun `newlyUnlocked shrink is silent`() {
        // 导入旧备份等集合收缩：只随基线覆写、不误报解锁。
        assertEquals(null, PetMilestones.newlyUnlocked(setOf("souv1", "days1"), setOf("days1")))
    }

    @Test
    fun `newlyUnlocked cross-checked against real achievedIDs formula`() {
        // 一次 play：互动 4→5 + 玩耍 4→5 同帧解锁两项 → multi 文案路径（N=2）。
        val old = PetMilestones.achievedIDs(0, 4, 0, 0, false, 4, 0)
        val new = PetMilestones.achievedIDs(0, 5, 0, 0, false, 5, 0)
        val u = PetMilestones.newlyUnlocked(old, new)!!
        assertEquals(listOf("interact5", "play5"), u.map { it.id })
    }
}
