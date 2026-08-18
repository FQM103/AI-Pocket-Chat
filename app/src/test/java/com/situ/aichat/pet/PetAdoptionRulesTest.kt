package com.situ.aichat.pet

import com.situ.aichat.data.model.RelationshipQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 领养解锁条件（1:1 iOS PetCareService.canAdopt / AdoptionProgress）。断言反推 iOS 阈值：陪伴≥14/trust≥40/
 * familiarity≥35/closeness≥30/消息≥100；companionDays = 24h 段数 + 1；overallPercent = 5 项等权平均。
 */
class PetAdoptionRulesTest {

    private val DAY = 86_400_000L
    private val NOW = 1_700_000_000_000L

    private fun rq(trust: Int = 40, fam: Int = 35, close: Int = 30) =
        RelationshipQuality(familiarity = fam, trust = trust, closeness = close)

    @Test fun `companion day creation-day counts as 1`() {
        assertEquals(1, PetAdoptionRules.companionDays(NOW, NOW))
    }

    @Test fun `companion days 13 days ago is 14`() {
        assertEquals(14, PetAdoptionRules.companionDays(NOW - 13 * DAY, NOW))
    }

    @Test fun `canAdopt true when all thresholds met`() {
        val r = PetAdoptionRules.evaluate(rq(40, 35, 30), NOW - 13 * DAY, 100, NOW)
        assertTrue(r.canAdopt)
        assertEquals(14, r.progress.companionDays)
    }

    @Test fun `canAdopt false when trust below 40`() {
        assertFalse(PetAdoptionRules.evaluate(rq(trust = 39), NOW - 13 * DAY, 100, NOW).canAdopt)
    }

    @Test fun `canAdopt false when companion days below 14`() {
        assertFalse(PetAdoptionRules.evaluate(rq(), NOW - 12 * DAY, 100, NOW).canAdopt) // 13 天
    }

    @Test fun `canAdopt false when messages below 100`() {
        assertFalse(PetAdoptionRules.evaluate(rq(), NOW - 13 * DAY, 99, NOW).canAdopt)
    }

    @Test fun `percents clamp and overall is equal-weight average`() {
        val p = AdoptionProgress(companionDays = 7, trust = 20, familiarity = 35, closeness = 30, messageCount = 100)
        assertEquals(0.5f, p.companionDaysPercent, 1e-4f) // 7/14
        assertEquals(0.5f, p.trustPercent, 1e-4f) // 20/40
        assertEquals(1f, p.familiarityPercent, 1e-4f) // 35/35
        assertEquals(1f, p.closenessPercent, 1e-4f)
        assertEquals(1f, p.messageCountPercent, 1e-4f)
        assertEquals((0.5f + 0.5f + 1f + 1f + 1f) / 5f, p.overallPercent, 1e-4f)
    }

    @Test fun `overall caps at 1 when every metric exceeds target (孵化就绪)`() {
        val p = AdoptionProgress(companionDays = 30, trust = 99, familiarity = 99, closeness = 99, messageCount = 500)
        assertEquals(1f, p.companionDaysPercent, 1e-4f)
        assertEquals(1f, p.overallPercent, 1e-4f) // 蛋巢 hero「准备好」态领衔的总进度 = 100%
    }

    @Test fun `overall is zero when no relationship yet`() {
        val p = AdoptionProgress(companionDays = 0, trust = 0, familiarity = 0, closeness = 0, messageCount = 0)
        assertEquals(0f, p.overallPercent, 1e-4f)
    }
}
