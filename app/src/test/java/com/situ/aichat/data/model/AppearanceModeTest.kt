package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反推 iOS `AppearanceMode.colorScheme` 语义：system→跟随、light→浅、dark→深。
 */
class AppearanceModeTest {

    @Test
    fun system_followsSystemFlag() {
        assertTrue(AppearanceMode.SYSTEM.resolveDarkTheme(systemInDark = true))
        assertFalse(AppearanceMode.SYSTEM.resolveDarkTheme(systemInDark = false))
    }

    @Test
    fun light_alwaysFalse_regardlessOfSystem() {
        assertFalse(AppearanceMode.LIGHT.resolveDarkTheme(systemInDark = true))
        assertFalse(AppearanceMode.LIGHT.resolveDarkTheme(systemInDark = false))
    }

    @Test
    fun dark_alwaysTrue_regardlessOfSystem() {
        assertTrue(AppearanceMode.DARK.resolveDarkTheme(systemInDark = true))
        assertTrue(AppearanceMode.DARK.resolveDarkTheme(systemInDark = false))
    }

    @Test
    fun fromRaw_mapsKnownRaws() {
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromRaw("system"))
        assertEquals(AppearanceMode.LIGHT, AppearanceMode.fromRaw("light"))
        assertEquals(AppearanceMode.DARK, AppearanceMode.fromRaw("dark"))
    }

    @Test
    fun fromRaw_unknownOrNull_fallsBackToSystem() {
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromRaw(null))
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromRaw(""))
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromRaw("midnight"))
    }

    @Test
    fun rawValues_match_iOS() {
        // 与 iOS Models/AppSettings.swift enum raw 字节级一致（backup/迁移口径稳定）
        assertEquals("system", AppearanceMode.SYSTEM.raw)
        assertEquals("light", AppearanceMode.LIGHT.raw)
        assertEquals("dark", AppearanceMode.DARK.raw)
    }
}
