package com.situ.aichat.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** 13.10d 系统级单独语言：框架 locale → App 支持标签的归一纯函数单测。 */
class LocaleManagerTest {

    @Test
    fun `chinese language normalizes to zh-CN`() {
        assertEquals("zh-CN", LocaleManager.normalizeLanguageTag("zh"))
        assertEquals("zh-CN", LocaleManager.normalizeLanguageTag("ZH"))
    }

    @Test
    fun `english normalizes to en`() {
        assertEquals("en", LocaleManager.normalizeLanguageTag("en"))
        assertEquals("en", LocaleManager.normalizeLanguageTag("EN"))
    }

    @Test
    fun `null or unsupported language falls back to follow-system`() {
        assertEquals(LocaleManager.SYSTEM, LocaleManager.normalizeLanguageTag(null))
        assertEquals(LocaleManager.SYSTEM, LocaleManager.normalizeLanguageTag("fr"))
        assertEquals(LocaleManager.SYSTEM, LocaleManager.normalizeLanguageTag(""))
    }
}
