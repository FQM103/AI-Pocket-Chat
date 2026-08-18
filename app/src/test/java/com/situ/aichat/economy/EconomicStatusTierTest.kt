package com.situ.aichat.economy

import com.situ.aichat.data.model.EconomicStatusTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 日程侧 3 档 [economicTier] 阈值单测（断言反推 iOS `CharacterEconomicStateService.tier`：tightThreshold=0.5 /
 * comfortableThreshold=1.5；欠租一票否决；月薪 ≤ 0 → null）。纯函数，无需 Robolectric。
 */
class EconomicStatusTierTest {

    @Test fun salary0_returns_null() {
        assertNull(economicTier(monthlySalary = 0, coinBalance = 5000, hasArrears = false))
        assertNull(economicTier(monthlySalary = -100, coinBalance = 5000, hasArrears = false))
    }

    @Test fun arrears_forces_tight_even_when_rich() {
        // 欠租一票否决：余额 = 3 倍月薪也判 tight
        assertEquals(EconomicStatusTier.TIGHT, economicTier(monthlySalary = 10000, coinBalance = 30000, hasArrears = true))
    }

    @Test fun ratio_below_half_is_tight() {
        // 0.2 → tight
        assertEquals(EconomicStatusTier.TIGHT, economicTier(monthlySalary = 10000, coinBalance = 2000, hasArrears = false))
    }

    @Test fun ratio_at_half_is_normal_boundary() {
        // 0.5 正好不 < 0.5 → normal（iOS buildContext 测试注释"正好正常档位边界"）
        assertEquals(EconomicStatusTier.NORMAL, economicTier(monthlySalary = 8000, coinBalance = 4000, hasArrears = false))
    }

    @Test fun ratio_just_below_comfortable_is_normal() {
        // 1.4999 → normal
        assertEquals(EconomicStatusTier.NORMAL, economicTier(monthlySalary = 10000, coinBalance = 14999, hasArrears = false))
    }

    @Test fun ratio_at_comfortable_threshold_is_comfortable() {
        // 1.5 正好 ≥ 1.5 → comfortable
        assertEquals(EconomicStatusTier.COMFORTABLE, economicTier(monthlySalary = 10000, coinBalance = 15000, hasArrears = false))
    }

    @Test fun negative_balance_clamps_to_zero_then_tight() {
        // max(0, balance) → 0/月薪 = 0 → tight
        assertEquals(EconomicStatusTier.TIGHT, economicTier(monthlySalary = 10000, coinBalance = -500, hasArrears = false))
    }
}
