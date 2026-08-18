package com.situ.aichat.sticker

import android.content.Context
import com.situ.aichat.util.StringListJson

/**
 * Recently-used sticker IDs (1:1 iOS `StickerService` recent-used: key `StickerRecentUsedIDs`,
 * max 20, most-recent-first). Only recorded when the **user** sends a sticker (AI sends do not).
 *
 * iOS stores an ordered `[String]` in `UserDefaults`; a `StringSet` pref loses order, so this keeps
 * a JSON list (via [StringListJson]) in a string pref instead.
 */
object StickerRecentStore {
    private const val PREFS = "sticker_prefs"
    private const val KEY = "StickerRecentUsedIDs"
    private const val MAX_RECENT = 20

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 最近使用的表情包 ID（最新在前）。 */
    fun recentStickerIds(context: Context): List<String> =
        StringListJson.decode(prefs(context).getString(KEY, "") ?: "")

    /** 记录一次使用：移到最前，超 20 截断（1:1 iOS `recordUsage`）。 */
    fun recordUsage(context: Context, stickerId: String) {
        val list = recentStickerIds(context).toMutableList()
        list.removeAll { it == stickerId }
        list.add(0, stickerId)
        val trimmed = if (list.size > MAX_RECENT) list.take(MAX_RECENT) else list
        prefs(context).edit().putString(KEY, StringListJson.encode(trimmed)).apply()
    }
}
