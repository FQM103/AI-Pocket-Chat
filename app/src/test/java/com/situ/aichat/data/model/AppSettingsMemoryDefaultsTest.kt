package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 记忆字数上限默认值 T1（2026-07-11 拍板·微图纸「记忆护栏自愈泄压与默认5000」G1）：
 * 默认 5000 为用户拍板锁定值——断言重新打字为字面量（不引用实现常量），实现改值必在此撞墙。
 * [AppSettings.DEFAULT_MEMORY_SUMMARY_MAX_LENGTH] 是 AppSettings 默认参与 SettingsRepository
 * 回退的单源，双保险第二针钉「默认参 == 单源常量」。
 */
class AppSettingsMemoryDefaultsTest {

    @Test
    fun `记忆字数上限默认值为5000_拍板锁定`() {
        assertEquals(5000, AppSettings().memorySummaryMaxLength)
    }

    @Test
    fun `默认参与单源常量一致`() {
        assertEquals(AppSettings.DEFAULT_MEMORY_SUMMARY_MAX_LENGTH, AppSettings().memorySummaryMaxLength)
    }
}
