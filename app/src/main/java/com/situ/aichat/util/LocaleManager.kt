package com.situ.aichat.util

import android.app.LocaleManager as SystemLocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language switching (no AppCompat dependency, no GMS).
 *
 * **Android 13+ (API 33)：系统级单独语言（13.10d · C7）** — 框架 [android.app.LocaleManager] 成为唯一真相源，
 * 配合 `res/xml/locales_config.xml` + manifest `android:localeConfig`，App 出现在「设置 ▸ 系统 ▸ 语言 ▸ 应用语言」里；
 * 在那儿改语言能真正切换本 App，应用内切换器与系统设置双向同步、由系统自动重建界面。
 *
 * **Android 12 及以下**：沿用原 SharedPreferences + attachBaseContext 包裹机制（不变），全 API 可用。
 *
 * 默认 = 简体中文（面向中国大陆）；用户可切英文或「跟随系统」。
 */
object LocaleManager {
    private const val PREFS = "app_locale"
    private const val KEY_TAG = "lang_tag"

    const val SYSTEM = ""           // 内部哨兵：未支持语言 / 残留「跟随系统」状态（已非用户可选项，见 ensureDefaultLocale）
    const val DEFAULT_TAG = "zh-CN" // first-launch default: Simplified Chinese

    private val usesFramework: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** 当前生效语言标签（"zh-CN" / "en" / [SYSTEM]）。33+ 读框架、<33 读 SharedPreferences。 */
    fun currentTag(context: Context): String {
        if (usesFramework) {
            val locales = context.getSystemService(SystemLocaleManager::class.java)?.applicationLocales
            if (locales == null || locales.isEmpty) return SYSTEM
            return normalizeLanguageTag(locales.get(0)?.language)
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAG, DEFAULT_TAG) ?: DEFAULT_TAG
    }

    /** 把框架 locale 的语言码归一到本 App 支持的标签（"zh-CN" / "en" / [SYSTEM]）。纯函数，便于单测。 */
    internal fun normalizeLanguageTag(language: String?): String = when (language?.lowercase()) {
        "zh" -> "zh-CN"
        "en" -> "en"
        else -> SYSTEM
    }

    /**
     * 设置语言。返回是否需要调用方手动 `recreate()`：33+ 由框架设值并自动重建界面 → false；<33 写 SharedPreferences
     * 后需手动 recreate → true。
     */
    fun setLanguage(context: Context, tag: String): Boolean {
        if (usesFramework) {
            val lm = context.getSystemService(SystemLocaleManager::class.java) ?: return false
            lm.applicationLocales =
                if (tag == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return false
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TAG, tag)
            .apply()
        return true
    }

    /** Wrap a base context with the chosen locale. 33+ 框架已应用 per-app locale → no-op；<33 走 SharedPreferences 包裹。 */
    fun wrap(base: Context): Context {
        if (usesFramework) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAG, DEFAULT_TAG) ?: DEFAULT_TAG
        if (tag == SYSTEM) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * 启动时确保 App 处于「显式语言」（zh-CN 或 en），**绝不停留在「跟随系统」**（i18n Phase 0 已移除该选项）。
     * - 新装：首启默认简体中文（[DEFAULT_TAG]）。
     * - 13.10d 老用户：把旧 SharedPreferences 的 [KEY_TAG] 选择迁进框架（曾选 en→en，不丢选择）。
     * - 曾选「跟随系统」/ 框架 applicationLocales 仍为空者：按**当前系统语言**归一到 zh-CN/en（保留其当下实际所见的语言）。
     * 幂等：一旦设过显式 locale，applicationLocales 即非空 → 后续启动直接 no-op（已无清空它的入口）。
     * 由 [com.situ.aichat.AIChatApplication.onCreate] 调用。
     */
    fun ensureDefaultLocale(context: Context) {
        if (!usesFramework) {
            // <33：把可能残留的「跟随系统」([SYSTEM]) 选择迁成显式标签（wrap() 才能锁定单一语言解析）。
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if ((prefs.getString(KEY_TAG, DEFAULT_TAG) ?: DEFAULT_TAG) == SYSTEM) {
                prefs.edit().putString(KEY_TAG, resolveSystemTag(context)).apply()
            }
            return
        }
        val lm = context.getSystemService(SystemLocaleManager::class.java) ?: return
        if (!lm.applicationLocales.isEmpty) return
        val oldTag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAG, DEFAULT_TAG) ?: DEFAULT_TAG
        val tag = if (oldTag == SYSTEM) resolveSystemTag(context) else oldTag
        lm.applicationLocales = LocaleList.forLanguageTags(tag)
    }

    /** 把当前系统语言归一到 App 支持的显式标签（zh→zh-CN, en→en, 其它→[DEFAULT_TAG]）。 */
    private fun resolveSystemTag(context: Context): String =
        normalizeLanguageTag(context.resources.configuration.locales.get(0)?.language).ifEmpty { DEFAULT_TAG }
}
