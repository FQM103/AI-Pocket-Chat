package com.situ.aichat.moments

import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.moments.MomentRelevanceScorer.CandidateScore
import com.situ.aichat.moments.MomentRelevanceScorer.ReplyCandidate
import com.situ.aichat.moments.MomentRelevanceScorer.ReplyConfig
import com.situ.aichat.moments.MomentRelevanceScorer.Score
import com.situ.aichat.moments.MomentRelevanceScorer.SelectionConfig
import com.situ.aichat.moments.MomentRelevanceScorer.TimingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with iOS `MomentRelevanceScorer` (Services/MomentRelevanceScorer.swift). Every assertion is
 * reverse-derived from the iOS formula + constants (NOT from the Kotlin output), per the project's
 * verification process — so these catch porting bugs (wrong constant, clamp order, missing/extra
 * random draw, flipped comparator). [Rng] both feeds a deterministic draw sequence and asserts the
 * exact number of draws consumed (the "fixed N draws per candidate" invariant).
 */
class MomentRelevanceScorerTest {

    private val DELTA = 1e-9

    /** Deterministic RNG: yields the given values in order; over-consuming throws; [consumed] is checked. */
    private class Rng(vararg values: Double) {
        private val values = values
        var consumed = 0
            private set
        val draw: () -> Double = {
            check(consumed < values.size) { "RNG over-consumed at draw ${consumed + 1}" }
            values[consumed++]
        }
    }

    private fun cand(uuid: String, total: Double) = CandidateScore(uuid, Score(total, 0.0, 0.0, 0.0))

    // ---- scoreRelationship ----

    @Test fun `relationship score averages four dims minus tension penalty`() {
        // (80+80+80+80)/4/100 = 0.8; tension 0 → no penalty.
        val good = RelationshipQuality(familiarity = 80, closeness = 80, attachment = 80, funValue = 80, tension = 0)
        assertEquals(0.8, MomentRelevanceScorer.scoreRelationship(good), DELTA)

        // (50*4)/4/100 = 0.5; tension 100 → penalty 100/100*0.1 = 0.1 → 0.4.
        val tense = RelationshipQuality(familiarity = 50, closeness = 50, attachment = 50, funValue = 50, tension = 100)
        assertEquals(0.4, MomentRelevanceScorer.scoreRelationship(tense), DELTA)

        // All zero → 0.
        val none = RelationshipQuality(familiarity = 0, closeness = 0, attachment = 0, funValue = 0, tension = 0)
        assertEquals(0.0, MomentRelevanceScorer.scoreRelationship(none), DELTA)
    }

    @Test fun `relationship score excludes trust rapport respect`() {
        // Only familiarity/closeness/attachment/fun count; trust/rapport/respect must NOT affect it.
        val a = RelationshipQuality(familiarity = 40, closeness = 40, attachment = 40, funValue = 40, tension = 0,
            trust = 0, rapport = 0, respect = 0)
        val b = RelationshipQuality(familiarity = 40, closeness = 40, attachment = 40, funValue = 40, tension = 0,
            trust = 100, rapport = 100, respect = 100)
        assertEquals(MomentRelevanceScorer.scoreRelationship(a), MomentRelevanceScorer.scoreRelationship(b), DELTA)
        assertEquals(0.4, MomentRelevanceScorer.scoreRelationship(a), DELTA)
    }

    @Test fun `stranger default relationship is about 0_11`() {
        // iOS default quality (fam10/clo10/att5/fun20/tension5): 45/4/100 - 5/100*0.1 = 0.1125 - 0.005.
        assertEquals(0.1075, MomentRelevanceScorer.scoreRelationship(RelationshipQuality()), 1e-6)
    }

    // ---- scoreInterest ----

    @Test fun `interest is zero with no interests`() {
        assertEquals(0.0, MomentRelevanceScorer.scoreInterest("", emptyList(), "anything"), DELTA)
    }

    @Test fun `initial interest matched substring scores weight over threshold`() {
        // one initial interest weight 0.4, matched → 0.4 / 1.5.
        assertEquals(0.4 / 1.5, MomentRelevanceScorer.scoreInterest("coffee", emptyList(), "I love coffee"), DELTA)
        // not matched → 0.
        assertEquals(0.0, MomentRelevanceScorer.scoreInterest("coffee", emptyList(), "I love tea"), DELTA)
    }

    @Test fun `interest splits on both ascii and fullwidth comma and matches chinese substrings`() {
        // "咖啡" matches "喝咖啡", "茶" doesn't appear → only 0.4 accrues.
        assertEquals(0.4 / 1.5, MomentRelevanceScorer.scoreInterest("咖啡，茶", emptyList(), "今天喝咖啡"), DELTA)
    }

    @Test fun `interest matching is case-insensitive`() {
        assertEquals(0.4 / 1.5, MomentRelevanceScorer.scoreInterest("Coffee", emptyList(), "I LOVE COFFEE"), DELTA)
    }

    @Test fun `dynamic interest uses heat over 100 and same name takes max`() {
        // dynamic only: heat 75 → 0.75; matched → 0.75/1.5 = 0.5.
        val dyn = listOf(DynamicInterest(name = "music", heat = 75))
        assertEquals(0.75 / 1.5, MomentRelevanceScorer.scoreInterest("", dyn, "music festival"), DELTA)

        // same name as initial (0.4) vs dynamic heat 90 (0.9) → max 0.9 → 0.9/1.5 = 0.6.
        val dyn2 = listOf(DynamicInterest(name = "coffee", heat = 90))
        assertEquals(0.9 / 1.5, MomentRelevanceScorer.scoreInterest("coffee", dyn2, "coffee time"), DELTA)
    }

    @Test fun `interest is capped at one`() {
        // four initial interests (0.4 each = 1.6) all present → 1.6/1.5 capped to 1.0.
        assertEquals(1.0, MomentRelevanceScorer.scoreInterest("a,b,c,d", emptyList(), "a b c d"), DELTA)
    }

    // ---- scoreActivity ----

    @Test fun `activity is message count over 50 clamped`() {
        assertEquals(0.0, MomentRelevanceScorer.scoreActivity(0), DELTA)
        assertEquals(0.5, MomentRelevanceScorer.scoreActivity(25), DELTA)
        assertEquals(1.0, MomentRelevanceScorer.scoreActivity(50), DELTA)
        assertEquals(1.0, MomentRelevanceScorer.scoreActivity(100), DELTA)  // clamped
    }

    // ---- composite score ----

    @Test fun `composite score is weighted sum of dims`() {
        val s = MomentRelevanceScorer.score(
            relationship = RelationshipQuality(familiarity = 80, closeness = 80, attachment = 80, funValue = 80, tension = 0),
            initialInterests = "coffee",
            dynamicInterests = emptyList(),
            recentMessageCount = 50,
            postContent = "I love coffee",
        )
        assertEquals(0.8, s.relationship, DELTA)
        assertEquals(0.4 / 1.5, s.interest, DELTA)
        assertEquals(1.0, s.activity, DELTA)
        // 0.8*0.4 + (0.4/1.5)*0.35 + 1.0*0.25
        assertEquals(0.8 * 0.4 + (0.4 / 1.5) * 0.35 + 1.0 * 0.25, s.total, DELTA)
    }

    @Test fun `composite total never exceeds one`() {
        val s = MomentRelevanceScorer.score(
            relationship = RelationshipQuality(familiarity = 100, closeness = 100, attachment = 100, funValue = 100, tension = 0),
            initialInterests = "a,b,c,d",
            dynamicInterests = emptyList(),
            recentMessageCount = 100,
            postContent = "a b c d",
        )
        assertEquals(1.0, s.total, DELTA)  // 0.4 + 0.35 + 0.25
    }

    // ---- probability curves ----

    private val curve = SelectionConfig.withUserSettings(likeUpperBound = 5, commentUpperBound = 2, autoLikeEnabled = true)

    @Test fun `like probability is slope-intercept clamped to ceiling`() {
        assertEquals(0.1, MomentRelevanceScorer.likeProbability(0.0, curve), DELTA)   // 0*1.4+0.1
        assertEquals(0.8, MomentRelevanceScorer.likeProbability(0.5, curve), DELTA)   // 0.5*1.4+0.1
        assertEquals(0.95, MomentRelevanceScorer.likeProbability(0.7, curve), DELTA)  // 1.08 → 0.95
        assertEquals(0.95, MomentRelevanceScorer.likeProbability(1.0, curve), DELTA)  // 1.5 → 0.95
    }

    @Test fun `comment probability has offset slope ceiling and cold-start floor`() {
        assertEquals(0.05, MomentRelevanceScorer.commentProbability(0.0, curve), DELTA)   // floor
        assertEquals(0.05, MomentRelevanceScorer.commentProbability(0.15, curve), DELTA)  // (0)*2.2 → floor
        assertEquals(0.35 * 2.2, MomentRelevanceScorer.commentProbability(0.5, curve), DELTA)  // 0.77
        assertEquals(0.9, MomentRelevanceScorer.commentProbability(0.6, curve), DELTA)   // 0.99 → ceiling
        assertEquals(0.9, MomentRelevanceScorer.commentProbability(1.0, curve), DELTA)   // 1.87 → ceiling
    }

    // ---- selectInteractions ----

    @Test fun `selectInteractions comments force-like and independent like, two draws each`() {
        val cands = listOf(cand("A", 0.5), cand("B", 0.5))
        // order consumed: A.comment, A.like, B.comment, B.like
        // A: comment 0.5<0.77 → comment + forced like (like draw 0.99 ignored).
        // B: comment 0.9<0.77? no. like 0.5<0.8 → like.
        val rng = Rng(0.5, 0.99, 0.9, 0.5)
        val sel = MomentRelevanceScorer.selectInteractions(cands, curve, rng.draw)
        assertEquals(listOf("A"), sel.commentUuids)
        assertEquals(listOf("A", "B"), sel.likeUuids)
        assertEquals(4, rng.consumed)  // exactly 2 draws per candidate
    }

    @Test fun `selectInteractions consumes two draws per candidate even when caps exhausted`() {
        val cands = listOf(cand("A", 0.9), cand("B", 0.9))
        val cfg = SelectionConfig.withUserSettings(likeUpperBound = 0, commentUpperBound = 0, autoLikeEnabled = false)
        val rng = Rng(0.0, 0.0, 0.0, 0.0)
        val sel = MomentRelevanceScorer.selectInteractions(cands, cfg, rng.draw)
        assertTrue(sel.commentUuids.isEmpty())
        assertTrue(sel.likeUuids.isEmpty())
        assertEquals(4, rng.consumed)  // both draws still taken at top of loop
    }

    @Test fun `selectInteractions sorts by descending score then ascending uuid`() {
        // Provided reversed; tie at 0.5 → uuid asc (A before B); both comment (budget 2).
        val cands = listOf(cand("B", 0.5), cand("A", 0.5))
        val rng = Rng(0.0, 0.0, 0.0, 0.0)
        val sel = MomentRelevanceScorer.selectInteractions(cands, curve, rng.draw)
        assertEquals(listOf("A", "B"), sel.commentUuids)
    }

    @Test fun `selectInteractions gives the single comment slot to the highest score`() {
        val cands = listOf(cand("low", 0.2), cand("high", 0.9))
        val cfg = SelectionConfig.withUserSettings(likeUpperBound = 5, commentUpperBound = 1, autoLikeEnabled = true)
        // sorted [high, low]. high.comment 0.0<0.9 → comment (budget now full). low.comment 0.0<0.11 but budget 1==1 → no.
        val rng = Rng(0.0, 0.0, 0.0, 0.0)
        val sel = MomentRelevanceScorer.selectInteractions(cands, cfg, rng.draw)
        assertEquals(listOf("high"), sel.commentUuids)
    }

    @Test fun `selectInteractions respects like cap`() {
        val cands = listOf(cand("A", 0.9), cand("B", 0.9))
        val cfg = SelectionConfig.withUserSettings(likeUpperBound = 1, commentUpperBound = 0, autoLikeEnabled = true)
        // No comments (budget 0). likeProb(0.9)=0.95. A likes; B blocked by cap.
        val rng = Rng(0.5, 0.0, 0.5, 0.0)
        val sel = MomentRelevanceScorer.selectInteractions(cands, cfg, rng.draw)
        assertEquals(listOf("A"), sel.likeUuids)
    }

    @Test fun `selectInteractions with autoLike off yields no likes`() {
        val cands = listOf(cand("A", 0.5))
        val cfg = SelectionConfig.withUserSettings(likeUpperBound = 5, commentUpperBound = 2, autoLikeEnabled = false)
        val rng = Rng(0.0, 0.0)
        val sel = MomentRelevanceScorer.selectInteractions(cands, cfg, rng.draw)
        assertEquals(listOf("A"), sel.commentUuids)
        assertTrue(sel.likeUuids.isEmpty())
    }

    // ---- selectReplyTarget ----

    private fun rc(uuid: String, author: String, ts: Long, replies: Int = 0) =
        ReplyCandidate(commentUuid = uuid, authorCharacterUuid = author, timestamp = ts, existingReplyCount = replies)

    @Test fun `reply target is null when reply draw misses probability, still two draws`() {
        val rng = Rng(0.5, 0.0)  // 0.5 >= 0.4
        val target = MomentRelevanceScorer.selectReplyTarget(
            listOf(rc("c1", "X", 100)), "me", ReplyConfig(), rng.draw,
        )
        assertNull(target)
        assertEquals(2, rng.consumed)
    }

    @Test fun `reply target weights newest highest via pick draw`() {
        val cands = listOf(rc("c1", "X", 100), rc("c2", "Y", 200), rc("c3", "Z", 300))
        // sorted desc ts → [c3, c2, c1], weights [3,2,1] total 6.
        // pick 0.0*6=0 → c3 (acc 3). pick 0.5*6=3.0 → c2 (acc 5). pick 0.9*6=5.4 → c1 (acc 6).
        assertEquals("c3", MomentRelevanceScorer.selectReplyTarget(cands, "me", ReplyConfig(), Rng(0.0, 0.0).draw))
        assertEquals("c2", MomentRelevanceScorer.selectReplyTarget(cands, "me", ReplyConfig(), Rng(0.0, 0.5).draw))
        assertEquals("c1", MomentRelevanceScorer.selectReplyTarget(cands, "me", ReplyConfig(), Rng(0.0, 0.9).draw))
    }

    @Test fun `reply target excludes self`() {
        val cands = listOf(rc("mine", "me", 200), rc("theirs", "Y", 100))
        // self filtered → only "theirs" remains.
        assertEquals("theirs", MomentRelevanceScorer.selectReplyTarget(cands, "me", ReplyConfig(), Rng(0.0, 0.0).draw))
    }

    @Test fun `reply target excludes comments already replied to the max`() {
        val cands = listOf(rc("full", "Y", 200, replies = 2), rc("open", "Z", 100, replies = 1))
        // "full" has 2 replies (>= maxRepliesPerComment 2) → excluded; "open" (1 < 2) remains.
        assertEquals("open", MomentRelevanceScorer.selectReplyTarget(cands, "me", ReplyConfig(), Rng(0.0, 0.0).draw))
    }

    @Test fun `reply target is null when all candidates filtered out, still two draws`() {
        val rng = Rng(0.0, 0.0)  // passes reply gate, but no eligible candidate
        val target = MomentRelevanceScorer.selectReplyTarget(emptyList(), "me", ReplyConfig(), rng.draw)
        assertNull(target)
        assertEquals(2, rng.consumed)
    }

    @Test fun `reply target only considers the most recent window`() {
        // window 3 → oldest (ts 100) never selectable. Highest weight (pick 0) is the newest (ts 400).
        val cands = listOf(rc("c1", "A", 100), rc("c2", "B", 200), rc("c3", "C", 300), rc("c4", "D", 400))
        val picked = MomentRelevanceScorer.selectReplyTarget(cands, "me", ReplyConfig(), Rng(0.0, 0.0).draw)
        assertEquals("c4", picked)
        // Sweep all pick values: c1 (oldest, outside window) must never be returned.
        for (p in listOf(0.0, 0.34, 0.67, 0.99)) {
            assertTrue("c1" != MomentRelevanceScorer.selectReplyTarget(cands, "me", ReplyConfig(), Rng(0.0, p).draw))
        }
    }

    // ---- interactionDelaySeconds ----

    @Test fun `delay is 18s when fully active and 150s when inactive`() {
        // multiplier active=1 → activityMin 0.3 → 1*60*0.3 = 18; active=0 → activityMax 2.5 → 150.
        assertEquals(18.0, MomentRelevanceScorer.interactionDelaySeconds(1.0, randomJitter = 0.0), DELTA)
        assertEquals(150.0, MomentRelevanceScorer.interactionDelaySeconds(0.0, randomJitter = 0.0), DELTA)
    }

    @Test fun `delay interpolates and adds jitter`() {
        // active 0.5 → multiplier 2.5 - 2.2*0.5 = 1.4 → 84s, no jitter.
        assertEquals(84.0, MomentRelevanceScorer.interactionDelaySeconds(0.5, randomJitter = 0.0), DELTA)
        // active 1, jitter 1 → 18 + 1*90 = 108.
        assertEquals(108.0, MomentRelevanceScorer.interactionDelaySeconds(1.0, randomJitter = 1.0), DELTA)
    }

    @Test fun `delay clamps activity and jitter to 0_1`() {
        assertEquals(18.0, MomentRelevanceScorer.interactionDelaySeconds(2.0, randomJitter = 0.0), DELTA)   // activity clamped to 1
        assertEquals(240.0, MomentRelevanceScorer.interactionDelaySeconds(0.0, randomJitter = 2.0), DELTA)  // jitter clamped to 1 → 150+90
    }

    @Test fun `timing config default matches iOS plan B`() {
        val c = TimingConfig()
        assertEquals(1.0, c.baseDelayMinutes, DELTA)
        assertEquals(0.3, c.activityMin, DELTA)
        assertEquals(2.5, c.activityMax, DELTA)
        assertEquals(90.0, c.jitterSeconds, DELTA)
    }
}
