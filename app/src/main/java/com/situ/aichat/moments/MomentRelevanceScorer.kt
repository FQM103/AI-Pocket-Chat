package com.situ.aichat.moments

import com.situ.aichat.data.model.DynamicInterest
import com.situ.aichat.data.model.RelationshipQuality
import kotlin.math.abs

/**
 * 1:1 port of iOS `MomentRelevanceScorer` (Services/MomentRelevanceScorer.swift).
 *
 * Pure functions that score a candidate character's affinity for a given post (0–1) and drive the
 * interaction scheduler (who likes / comments, with what probability, reply targeting, time spread).
 * Every constant, formula, clamp, tie-break, and **fixed random-draw count** is reproduced exactly —
 * these were tuned for replayable tests + a believable interaction density; changing any one shifts
 * the feel away from iOS.
 *
 * The only deliberate Android deviation: instead of taking JSON strings and decoding internally (iOS
 * `Input` holds `relationshipQualityJSON` / `dynamicInterestsJSON`), this takes the already-decoded
 * [RelationshipQuality] / [DynamicInterest] (callers use the P4 `CharacterEntity` decode accessors) —
 * the decode is plumbing, not part of the algorithm, so externalizing it keeps the scorer
 * dependency-free and trivially testable. The interest-string splitting + lowercase + max-merge +
 * substring matching IS the algorithm and stays here.
 *
 * RNG is injected as `random: () -> Double` (real use: `Random.nextDouble()`, i.e. 0 inclusive / 1
 * exclusive = Swift `Double.random(in: 0..<1)`); tests pass a deterministic sequence.
 */
object MomentRelevanceScorer {

    // ---- Weight constants ----

    /** Relationship weight (closer ⇒ more likely to interact). */
    const val WEIGHT_RELATIONSHIP = 0.4
    /** Interest weight (post content vs the character's interests). */
    const val WEIGHT_INTEREST = 0.35
    /** Activity weight (last-7-day message volume with the user). */
    const val WEIGHT_ACTIVITY = 0.25

    /** Activity full-score threshold: ≥ 50 messages in the last 7 days = full marks. */
    const val ACTIVITY_FULL_SCORE_MESSAGES = 50.0
    /** Interest normalization threshold: accumulated weight reaching this = full interest score. */
    const val INTEREST_FULL_SCORE_THRESHOLD = 1.5
    /** Tension's negative coefficient on the relationship score. */
    const val TENSION_PENALTY_COEFFICIENT = 0.1
    /** Fixed weight for each initial interest (fallback when dynamic interests haven't caught up). */
    const val INITIAL_INTEREST_WEIGHT = 0.4

    // ---- Output ----

    data class Score(
        val total: Double,
        val relationship: Double,
        val interest: Double,
        val activity: Double,
    )

    /**
     * Composite score = clamp01(rel·0.4 + intr·0.35 + act·0.25). Inputs are pre-decoded by the caller
     * (iOS bundled them in an `Input` struct holding raw JSON; see the class doc).
     */
    fun score(
        relationship: RelationshipQuality,
        initialInterests: String,
        dynamicInterests: List<DynamicInterest>,
        recentMessageCount: Int,
        postContent: String,
    ): Score {
        val rel = scoreRelationship(relationship)
        val intr = scoreInterest(initialInterests, dynamicInterests, postContent)
        val act = scoreActivity(recentMessageCount)
        val total = rel * WEIGHT_RELATIONSHIP + intr * WEIGHT_INTEREST + act * WEIGHT_ACTIVITY
        return Score(total = clamp01(total), relationship = rel, interest = intr, activity = act)
    }

    // ---- Dimension scores ----

    /**
     * Relationship score: mean of familiarity / closeness / attachment / fun (the four dims most
     * predictive of "wants to interact"; trust/rapport/respect deliberately excluded), minus a light
     * tension penalty. Stranger defaults ≈ 0.11; long-term friend (each 80+) ≈ 0.8.
     */
    fun scoreRelationship(quality: RelationshipQuality): Double {
        val sum = (quality.familiarity + quality.closeness + quality.attachment + quality.funValue).toDouble()
        val base = sum / 4.0 / 100.0
        val tensionPenalty = quality.tension / 100.0 * TENSION_PENALTY_COEFFICIENT
        return clamp01(base - tensionPenalty)
    }

    /**
     * Interest score: merge initial interests (weight 0.4 each) with dynamic interests (weight
     * heat/100, same name → max), substring-match against the lowercased post content, accumulate
     * matched weight, normalize by 1.5 (capped at 1.0). Matching is `content.contains(name)` — no
     * tokenization (1:1 iOS; works for Chinese substrings, intentionally no segmentation).
     */
    fun scoreInterest(
        initialInterests: String,
        dynamicInterests: List<DynamicInterest>,
        postContent: String,
    ): Double {
        val nameWeights = HashMap<String, Double>()

        // Initial interests: split on ASCII + fullwidth comma; trim; drop blanks.
        initialInterests
            .split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { nameWeights[it.lowercase()] = INITIAL_INTEREST_WEIGHT }

        // Dynamic interests: same name as an initial one → keep the larger weight.
        for (interest in dynamicInterests) {
            val key = interest.name.lowercase()
            if (key.isEmpty()) continue
            val dynamicWeight = interest.heat / 100.0
            nameWeights[key] = maxOf(nameWeights[key] ?: 0.0, dynamicWeight)
        }

        if (nameWeights.isEmpty()) return 0.0

        val content = postContent.lowercase()
        var matchedWeight = 0.0
        for ((name, weight) in nameWeights) {
            if (content.contains(name)) matchedWeight += weight
        }

        return minOf(matchedWeight / INTEREST_FULL_SCORE_THRESHOLD, 1.0)
    }

    /** Activity score: last-7-day message count over the full-score threshold, clamped to [0,1]. */
    fun scoreActivity(recentMessageCount: Int): Double =
        clamp01(recentMessageCount / ACTIVITY_FULL_SCORE_MESSAGES)

    // ---- Candidate selection (stage 3) ----

    data class CandidateScore(val characterUuid: String, val score: Score)

    /**
     * Selection params: user-set caps + probability-curve coefficients. Defaults match iOS
     * `SelectionConfig.withUserSettings`. [minCommentProbability] is a cold-start floor so a brand-new
     * character (composite ≈ 0.04, curve ⇒ 0) still occasionally comments (~1 in 10–20 posts).
     */
    data class SelectionConfig(
        val likeUpperBound: Int,
        val commentUpperBound: Int,
        val autoLikeEnabled: Boolean,
        val likeSlope: Double = 1.4,
        val likeIntercept: Double = 0.1,
        val likeCeiling: Double = 0.95,
        val commentSlope: Double = 2.2,
        val commentOffset: Double = 0.15,
        val commentCeiling: Double = 0.9,
        val minCommentProbability: Double = 0.05,
    ) {
        companion object {
            fun withUserSettings(
                likeUpperBound: Int,
                commentUpperBound: Int,
                autoLikeEnabled: Boolean,
            ) = SelectionConfig(
                likeUpperBound = likeUpperBound,
                commentUpperBound = commentUpperBound,
                autoLikeEnabled = autoLikeEnabled,
            )
        }
    }

    /** Invariant: when [SelectionConfig.autoLikeEnabled], `commentUuids ⊆ likeUuids`. */
    data class Selection(val likeUuids: List<String>, val commentUuids: List<String>)

    /** Like probability: clamp(score·slope + intercept, 0, ceiling). */
    fun likeProbability(score: Double, config: SelectionConfig): Double {
        val p = score * config.likeSlope + config.likeIntercept
        return maxOf(0.0, minOf(p, config.likeCeiling))
    }

    /** Comment probability with cold-start floor: max(clamp(max(0, score−offset)·slope, 0, ceiling), floor). */
    fun commentProbability(score: Double, config: SelectionConfig): Double {
        val p = maxOf(0.0, score - config.commentOffset) * config.commentSlope
        val clamped = maxOf(0.0, minOf(p, config.commentCeiling))
        return maxOf(clamped, config.minCommentProbability)
    }

    /**
     * Walk candidates by descending relevance (tie-break: ascending uuid, for replayability) and
     * independently decide like/comment for each, bounded by the caps. **Each candidate consumes
     * exactly 2 `random()` draws — comment draw first, then like draw — unconditionally** (both are
     * drawn at the top of the loop, before any cap/guard), so callers' tests stay replayable. A
     * commenting character is force-liked when [SelectionConfig.autoLikeEnabled] (keeps the invariant).
     */
    fun selectInteractions(
        candidates: List<CandidateScore>,
        config: SelectionConfig,
        random: () -> Double,
    ): Selection {
        val sorted = candidates.sortedWith { a, b ->
            if (abs(a.score.total - b.score.total) < 0.0001) a.characterUuid.compareTo(b.characterUuid)
            else b.score.total.compareTo(a.score.total)
        }

        val likes = ArrayList<String>()
        val comments = ArrayList<String>()

        for (candidate in sorted) {
            val commentDraw = random()
            val likeDraw = random()

            var willComment = false
            if (comments.size < config.commentUpperBound) {
                val p = commentProbability(candidate.score.total, config)
                if (commentDraw < p) {
                    willComment = true
                    comments.add(candidate.characterUuid)
                }
            }

            if (!config.autoLikeEnabled || likes.size >= config.likeUpperBound) continue
            if (willComment) {
                // Commenter must like (keeps commentUuids ⊆ likeUuids).
                likes.add(candidate.characterUuid)
            } else {
                val p = likeProbability(candidate.score.total, config)
                if (likeDraw < p) likes.add(candidate.characterUuid)
            }
        }

        return Selection(likeUuids = likes, commentUuids = comments)
    }

    // ---- Reply-target selection (AI-replies-to-AI · version A) ----

    /** Reply decision params (version A: one round, no follow-up turns). Defaults match iOS. */
    data class ReplyConfig(
        val replyProbability: Double = 0.4,
        val candidateWindow: Int = 3,
        val maxRepliesPerComment: Int = 2,
    )

    /** A snapshot of a top-level character comment that could be replied to (caller fetches it). */
    data class ReplyCandidate(
        val commentUuid: String,
        val authorCharacterUuid: String,
        val timestamp: Long,
        val existingReplyCount: Int,
    )

    /**
     * Decide whether the current commenter replies to an earlier comment, and which.
     * **Consumes exactly 2 `random()` draws (reply decision, then weighted pick) regardless of
     * whether there are candidates** — replayability. Returns the target comment uuid, or null to
     * comment directly on the post.
     */
    fun selectReplyTarget(
        existingComments: List<ReplyCandidate>,
        currentCharacterUuid: String,
        config: ReplyConfig,
        random: () -> Double,
    ): String? {
        val replyDraw = random()
        val pickDraw = random()

        // Decision 1: reply to a comment at all?
        if (replyDraw >= config.replyProbability) return null

        // Exclude self + comments already replied to enough.
        val filtered = existingComments.filter {
            it.authorCharacterUuid != currentCharacterUuid && it.existingReplyCount < config.maxRepliesPerComment
        }
        if (filtered.isEmpty()) return null

        // Most-recent N (simulate "only sees the latest").
        val recent = filtered.sortedByDescending { it.timestamp }.take(maxOf(1, config.candidateWindow))

        // Linear decay weights: newest rank 0 → highest. e.g. 3 → [3,2,1], 2 → [2,1], 1 → [1].
        val weights = recent.indices.map { (recent.size - it).toDouble() }
        val totalWeight = weights.sum()
        if (totalWeight <= 0) return null

        val pick = pickDraw * totalWeight
        var acc = 0.0
        for (i in weights.indices) {
            acc += weights[i]
            if (pick < acc) return recent[i].commentUuid
        }
        // Floating-point edge: pick landed exactly at the end.
        return recent.lastOrNull()?.commentUuid
    }

    // ---- Time spread (stage 4) ----

    /**
     * Comment-delay params (seconds). `delay = baseDelayMinutes·60·multiplier + jitter·jitterSeconds`
     * where `multiplier = activityMax − (activityMax − activityMin)·activityScore`. Defaults match
     * iOS `TimingConfig.default` (Plan B tuning): active=1 → 18s, active=0 → 150s.
     */
    data class TimingConfig(
        val baseDelayMinutes: Double = 1.0,
        val activityMin: Double = 0.3,
        val activityMax: Double = 2.5,
        val jitterSeconds: Double = 90.0,
    )

    /**
     * Additional delay (seconds, ≥ 0) for a candidate after `autoInteractWithPost` fires.
     * [randomJitter] is 0–1 (real use `Random.nextDouble()`; tests pass a fixed value).
     */
    fun interactionDelaySeconds(
        activityScore: Double,
        config: TimingConfig = TimingConfig(),
        randomJitter: Double,
    ): Double {
        val score = clamp01(activityScore)
        val jitter = clamp01(randomJitter)
        val multiplier = config.activityMax - (config.activityMax - config.activityMin) * score
        val base = config.baseDelayMinutes * 60 * multiplier
        return base + jitter * config.jitterSeconds
    }

    // ---- Private ----

    private fun clamp01(value: Double): Double = minOf(maxOf(value, 0.0), 1.0)
}
