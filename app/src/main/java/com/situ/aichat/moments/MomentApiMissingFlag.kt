package com.situ.aichat.moments

import android.content.Context

/**
 * Service-local flag: auto moment generation skipped because no API is configured / key is empty
 * (1:1 iOS `MomentGenerationActor.apiMissingKey` UserDefaults bool). SharedPreferences, not
 * AppSettings/Room — same convention as `DiaryApiMissingFlag` / `PendingDeliveryStore`. The 朋友圈
 * feed (7.2.7) can show an "auto posts paused — configure API" banner based on this.
 */
object MomentApiMissingFlag {
    private const val PREFS = "moment_gen_state"
    private const val KEY = "api_missing"

    fun set(context: Context, missing: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, missing).apply()
    }

    fun get(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
}
