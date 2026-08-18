package com.situ.aichat.sticker

import android.content.Context

/**
 * Soft-hidden built-in stickers (1:1 iOS `DisabledBuiltInStickerStore`, Models/StickerTypes.swift).
 * Hiding only affects prompt injection / picker / management "enabled" group — history rendering &
 * validation always use the full [BuiltInStickerCatalog.byId], so old chats never turn into
 * "missing sticker".
 *
 * iOS uses global `UserDefaults`; Android needs a Context, so callers read [disabledIds] and pass
 * the set into the pure catalog/service functions. Reactive UI re-reads after each mutation (the
 * management VM owns the StateFlow), replacing iOS's `NotificationCenter` broadcast.
 */
object DisabledBuiltInStickerStore {
    private const val PREFS = "sticker_prefs"
    private const val KEY = "DisabledBuiltInStickerIDs"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前被隐藏的内置表情 ID 集合。 */
    fun disabledIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun isDisabled(context: Context, id: String): Boolean = disabledIds(context).contains(id)

    /** 隐藏一个内置表情（软删除）。 */
    fun disable(context: Context, id: String) {
        val set = disabledIds(context).toMutableSet()
        if (set.add(id)) save(context, set)
    }

    /** 恢复一个被隐藏的内置表情。 */
    fun enable(context: Context, id: String) {
        val set = disabledIds(context).toMutableSet()
        if (set.remove(id)) save(context, set)
    }

    /** 恢复所有被隐藏的内置表情。 */
    fun restoreAll(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun save(context: Context, set: Set<String>) {
        prefs(context).edit().putStringSet(KEY, set).apply()
    }
}
