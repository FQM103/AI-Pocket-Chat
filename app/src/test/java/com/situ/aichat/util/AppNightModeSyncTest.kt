package com.situ.aichat.util

import android.app.UiModeManager
import com.situ.aichat.data.model.AppearanceMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C3#0 三档映射语义锁（从行为规格独立反推，非照搬实现）：
 * 强制深/浅必须落 YES/NO（系统持久化 per-app 覆盖 → 下次冷启 splash 同向）；
 * 跟随系统必须落 AUTO——AOSP UiModeManagerService 把 per-app 的 AUTO 映射为
 * UI_MODE_NIGHT_UNDEFINED（=清除覆盖），这是「回到跟随系统」的唯一公开通路，
 * 误落 YES/NO 会把跟随系统用户钉死在切换瞬间的档位上。
 */
class AppNightModeSyncTest {

    @Test
    fun forcedDark_mapsToNightYes() {
        assertEquals(UiModeManager.MODE_NIGHT_YES, AppNightModeSync.nightModeFor(AppearanceMode.DARK))
    }

    @Test
    fun forcedLight_mapsToNightNo() {
        assertEquals(UiModeManager.MODE_NIGHT_NO, AppNightModeSync.nightModeFor(AppearanceMode.LIGHT))
    }

    @Test
    fun followSystem_mapsToAuto_theOnlyClearOverridePath() {
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, AppNightModeSync.nightModeFor(AppearanceMode.SYSTEM))
    }
}
