package com.situ.aichat.prompt

import android.content.Context
import com.situ.aichat.util.LocaleManager

/**
 * Locale-correct string accessor for PromptBuilder module content. Wraps the context with the app's chosen
 * locale ([LocaleManager]) so prompts are emitted in the same language iOS would send via `String(localized:)`
 * — Chinese by default (China market), English when the user picks English / system is English.
 *
 * Resource layout (corrected 2026-07-16 — the old note here had the two sides swapped): `values/` holds the
 * **English** default, `values-zh-rCN/` the Chinese; there is no `values-en/`. Some `pb_*` keys still carry an
 * English string on the zh side — those are untranslated leftovers from the iOS port, not intentional, and are
 * fair game to translate when a change touches them.
 */
class PromptStrings(baseContext: Context) {
    private val ctx: Context = LocaleManager.wrap(baseContext)

    fun s(resId: Int): String = ctx.getString(resId)
    fun s(resId: Int, vararg args: Any?): String = ctx.getString(resId, *args)
}
