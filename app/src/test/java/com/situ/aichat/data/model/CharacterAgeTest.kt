package com.situ.aichat.data.model

import com.situ.aichat.data.local.entity.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * [currentAge] 测试，反推 iOS `AICharacter.currentAge` :211-221：
 * fixed → fixedAge(>0 否则 null)；growing → 生日到 now 的整年(无生日 null)。
 *
 * growing 用例选正午 UTC 的生日/now，避免构建机时区把本地日期推过年界，保证整年差与时区无关。
 */
class CharacterAgeTest {

    private fun character(
        ageModeRaw: String = "growing",
        fixedAge: Int = 0,
        birthday: Long? = null,
    ) = CharacterEntity(
        uuid = "c1", name = "测试角色", creationDate = 0L,
        ageModeRaw = ageModeRaw, fixedAge = fixedAge, birthday = birthday,
    )

    private val now = Instant.parse("2026-06-15T12:00:00Z")

    @Test fun fixed_positive_returns_fixed_age() {
        assertEquals(25, character(ageModeRaw = "fixed", fixedAge = 25).currentAge(now))
    }

    @Test fun fixed_zero_returns_null() {
        assertNull(character(ageModeRaw = "fixed", fixedAge = 0).currentAge(now))
    }

    @Test fun growing_full_year_gap() {
        val birthday = Instant.parse("2000-06-15T12:00:00Z").toEpochMilli()
        assertEquals(26, character(ageModeRaw = "growing", birthday = birthday).currentAge(now))
    }

    @Test fun growing_birthday_later_in_year_not_yet_reached() {
        // 生日在 12 月，now 在 6 月 → 还差半年，整年数 = 25
        val birthday = Instant.parse("2000-12-15T12:00:00Z").toEpochMilli()
        assertEquals(25, character(ageModeRaw = "growing", birthday = birthday).currentAge(now))
    }

    @Test fun growing_no_birthday_returns_null() {
        assertNull(character(ageModeRaw = "growing", birthday = null).currentAge(now))
    }

    @Test fun unknown_age_mode_falls_back_to_growing() {
        // iOS `CharacterAgeMode(rawValue:) ?? .growing` → 未知模式按 growing
        val birthday = Instant.parse("2000-06-15T12:00:00Z").toEpochMilli()
        assertEquals(26, character(ageModeRaw = "weird", birthday = birthday).currentAge(now))
        assertNull(character(ageModeRaw = "weird", birthday = null).currentAge(now))
    }
}
