package com.situ.aichat.moments

import android.content.Context
import com.situ.aichat.util.DateFormatters
import java.time.ZoneId

/**
 * "Owed post" tracking (1:1 iOS `MomentGenerationService.markOwedPost`/`hasOwedPost`/`clearOwedPost`).
 * When a character is asleep at generation time, the post is skipped and a same-day debt is recorded;
 * the next chat reply triggers a catch-up post (`catchUpPost`, 40–80s later — wired in 7.2.5).
 *
 * SharedPreferences per uuid (key `MomentOwedPost_{uuid}` = the timestamp it was owed), valid only for
 * the same calendar day (iOS `Calendar.isDateInToday`). Service-local store, same convention as
 * [MomentApiMissingFlag].
 */
object MomentOwedPostStore {
    private const val PREFS = "moment_owed_posts"
    private const val KEY_PREFIX = "MomentOwedPost_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Mark that [characterUuid] owes a post today. */
    fun markOwedPost(context: Context, characterUuid: String, nowMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_PREFIX + characterUuid, nowMillis).apply()
    }

    /** True if [characterUuid] has a still-valid (same calendar day) owed post. */
    fun hasOwedPost(
        context: Context,
        characterUuid: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val owed = prefs(context).getLong(KEY_PREFIX + characterUuid, 0L)
        if (owed <= 0L) return false
        return DateFormatters.startOfDayMillis(owed, zone) == DateFormatters.startOfDayMillis(nowMillis, zone)
    }

    /** Clear the owed-post mark (after a catch-up post, or when consumed). */
    fun clearOwedPost(context: Context, characterUuid: String) {
        prefs(context).edit().remove(KEY_PREFIX + characterUuid).apply()
    }
}
