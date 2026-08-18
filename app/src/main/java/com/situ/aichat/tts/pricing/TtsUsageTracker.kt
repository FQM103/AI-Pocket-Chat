package com.situ.aichat.tts.pricing

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset

/**
 * TTS usage tracker (1:1 iOS `TTSUsageTracker`). Stores characters consumed per UTC day in a
 * `{"2026-06-02": 1234}` map (one bucket per provider) in plain SharedPreferences — NOT Room: usage
 * is display-only, high-frequency, needs no transaction, and must not pollute the backup system or
 * trigger DB writes (mirrors iOS's reasoning for using UserDefaults).
 *
 * Only successful MiniMax synthesis is logged today (other providers aren't metered yet).
 * The pure bucketing/prune/snapshot helpers are `internal` so unit tests can reverse-derive them
 * from the iOS values without a device.
 */
enum class TtsUsageProvider(val raw: String) {
    MINIMAX("minimax");

    val displayName: String get() = when (this) { MINIMAX -> "MiniMax" }
}

/** Last-7-days usage snapshot for one provider (iOS `TTSUsageSnapshot`). */
data class TtsUsageSnapshot(
    val charactersLast7Days: Int,
    /** Days (≤7) that had a non-zero record. */
    val daysWithData: Int,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
)

object TtsUsageTracker {

    private const val PREFS_NAME = "tts_usage"
    private const val KEY_PREFIX = "com.situ.AIChat.tts.usage."
    private const val MILLIS_PER_DAY = 86_400_000L

    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - Public storage API (call sites pass a Context; pure logic lives in the internal fns)

    /** Record one successful synthesis. characters ≤ 0 is ignored. */
    fun log(
        context: Context,
        characters: Int,
        provider: TtsUsageProvider,
        referenceMillis: Long = System.currentTimeMillis(),
    ) {
        if (characters <= 0) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_PREFIX + provider.raw
        synchronized(this) {
            val bucket = readBucket(prefs, key).toMutableMap()
            val todayKey = bucketKey(referenceMillis)
            bucket[todayKey] = (bucket[todayKey] ?: 0) + characters
            // Symmetric ±90-day prune so out-of-order (test-injected) dates don't drop newer buckets.
            val pruned = prune(bucket, referenceMillis, keepDaysEachSide = 90)
            prefs.edit().putString(key, json.encodeToString(pruned)).apply()
        }
    }

    /** Read the last-7-days snapshot for a provider. */
    fun snapshot(
        context: Context,
        provider: TtsUsageProvider,
        referenceMillis: Long = System.currentTimeMillis(),
    ): TtsUsageSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = KEY_PREFIX + provider.raw
        val bucket = synchronized(this) { readBucket(prefs, key) }
        return computeSnapshot(bucket, referenceMillis)
    }

    /** Clear all usage for a provider (tests + the settings "reset" button). */
    fun reset(context: Context, provider: TtsUsageProvider) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        synchronized(this) { prefs.edit().remove(KEY_PREFIX + provider.raw).apply() }
    }

    private fun readBucket(prefs: android.content.SharedPreferences, key: String): Map<String, Int> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Int>>(raw) }.getOrDefault(emptyMap())
    }

    // MARK: - Pure helpers (UTC; testable without a device)

    /** UTC day bucket key "yyyy-MM-dd" for an epoch-millis instant. */
    internal fun bucketKey(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /** Keep only buckets within ±[keepDaysEachSide] UTC days of the reference instant. */
    internal fun prune(bucket: Map<String, Int>, referenceMillis: Long, keepDaysEachSide: Int): Map<String, Int> {
        val refDate = Instant.ofEpochMilli(referenceMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val valid = (-keepDaysEachSide..keepDaysEachSide)
            .mapTo(HashSet()) { refDate.plusDays(it.toLong()).toString() }
        return bucket.filterKeys { valid.contains(it) }
    }

    /** Sum the last 7 UTC days (offsets 0..6 back from the reference), counting non-zero days. */
    internal fun computeSnapshot(bucket: Map<String, Int>, referenceMillis: Long): TtsUsageSnapshot {
        val refDate = Instant.ofEpochMilli(referenceMillis).atZone(ZoneOffset.UTC).toLocalDate()
        var total = 0
        var daysWithData = 0
        for (offset in 0 until 7) {
            val value = bucket[refDate.minusDays(offset.toLong()).toString()] ?: 0
            if (value > 0) {
                total += value
                daysWithData++
            }
        }
        return TtsUsageSnapshot(
            charactersLast7Days = total,
            daysWithData = daysWithData,
            windowStartMillis = referenceMillis - 6L * MILLIS_PER_DAY,
            windowEndMillis = referenceMillis,
        )
    }
}
