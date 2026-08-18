package com.situ.aichat.ui.profile

import android.os.Looper
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.dao.WorldMemoryDao
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.economy.CurrencyService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「我」页主角卡陪伴统计的 T1+T2（PROFILE 契约 §9.3/§9.7 D4）。
 *
 * T1：[companionDaysSince] 边界从规格独立反推——当天=1、昨天=2、127 天前=128、未来钳 1、无角色=null、
 * 日界按 zone 的自然日（23:59 建的角色到次日 0:01 也算第 2 天）。
 * T2：VM 三新流按规格聚合（角色数直通、回忆=见面行+世界行相加、天数=MIN(creationDate) 过纯函数）。
 * WhileSubscribed 需订阅者才拉上游——Robolectric 主循环驱动（同 SettingsOverviewViewModelTest 惯例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileDashboardStatsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    // ── T1 纯函数 ──────────────────────────────────────────────

    @Test
    fun companionDays_creationToday_isDayOne() {
        val today = LocalDate.of(2026, 7, 12)
        val noonToday = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(1, companionDaysSince(noonToday, today, zone))
    }

    @Test
    fun companionDays_lateNightYesterday_isDayTwo() {
        // 昨晚 23:59 建的角色，今天就是「一起走过 2 天」——按自然日差，不按 24h 时长。
        val today = LocalDate.of(2026, 7, 12)
        val lateYesterday = today.minusDays(1).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        assertEquals(2, companionDaysSince(lateYesterday, today, zone))
    }

    @Test
    fun companionDays_127daysAgo_is128_crossYear() {
        // 契约示例数：127 个自然日前 → 128 天；起点落在上一年，验跨年不断档。
        val today = LocalDate.of(2026, 3, 1)
        val start = today.minusDays(127) // 2025-10-25
        val millis = start.atTime(8, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(128, companionDaysSince(millis, today, zone))
    }

    @Test
    fun companionDays_futureTimestamp_clampsToOne() {
        // 时钟回拨/导入异常数据：未来 creationDate 钳到 1，绝不出 0 或负数。
        val today = LocalDate.of(2026, 7, 12)
        val tomorrow = today.plusDays(3).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(1, companionDaysSince(tomorrow, today, zone))
    }

    @Test
    fun companionDays_noCharacters_isNull() {
        assertNull(companionDaysSince(null, LocalDate.of(2026, 7, 12), zone))
    }

    // ── T2 VM 聚合 ─────────────────────────────────────────────

    private fun buildVm(
        charactersCount: Int,
        earliestCreation: Long?,
        meetingMemories: Int,
        worldMemories: Int,
    ): ProfileDashboardViewModel {
        val profileDao = mockk<UserProfileDao> { every { observe() } returns flowOf(null) }
        val appSettingsMock = mockk<AppSettings> { every { currencySystemEnabled } returns true }
        val settingsFlow = flowOf(appSettingsMock)
        val settingsRepo = mockk<SettingsRepository> { every { appSettings } returns settingsFlow }
        val momentRepo = mockk<MomentRepository> { every { observeUserFeedCount() } returns flowOf(0) }
        val currencyService = mockk<CurrencyService> { every { observeUserCoinBalance() } returns flowOf(100) }
        val giftDao = mockk<GiftDao> { every { observeUserReceivedGiftCount() } returns flowOf(0) }
        val characterDao = mockk<CharacterDao> {
            every { observeCount() } returns flowOf(charactersCount)
            every { observeEarliestCreationDate() } returns flowOf(earliestCreation)
        }
        val meetingDao = mockk<OfflineMeetingMemoryDao> { every { observeCountAll() } returns flowOf(meetingMemories) }
        val worldDao = mockk<WorldMemoryDao> { every { observeCountAll() } returns flowOf(worldMemories) }
        return ProfileDashboardViewModel(
            profileDao, settingsRepo, momentRepo, currencyService, giftDao,
            characterDao, meetingDao, worldDao,
        )
    }

    /** 订阅三条陪伴统计流（WhileSubscribed 才开闸）并驱动主循环，读完 value 再退订。 */
    private fun <T> withSubscriptions(vm: ProfileDashboardViewModel, block: () -> T): T {
        val scope = CoroutineScope(Dispatchers.Main + Job())
        val flows: List<StateFlow<*>> = listOf(vm.charactersCount, vm.companionDays, vm.memoriesCount)
        flows.forEach { flow -> scope.launch { flow.collect {} } }
        shadowOf(Looper.getMainLooper()).idle()
        return try {
            block()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun vm_aggregatesCompanionStats() {
        // 昨天中午建号（自然日差 1 → 2 天）；回忆 = 200 见面行 + 16 世界行 = 216。
        // 「今天」用 VM 同款默认时钟——取昨天正午作起点，除非测试恰跨日界毫秒级窗口，否则恒 2。
        val defaultZone = ZoneId.systemDefault()
        val earliest = LocalDate.now(defaultZone).minusDays(1).atTime(12, 0).atZone(defaultZone).toInstant().toEpochMilli()
        val vm = buildVm(charactersCount = 3, earliestCreation = earliest, meetingMemories = 200, worldMemories = 16)
        withSubscriptions(vm) {
            assertEquals(3, vm.charactersCount.value)
            assertEquals(216, vm.memoriesCount.value)
            assertEquals(2, vm.companionDays.value)
        }
    }

    @Test
    fun vm_emptyLibrary_hidesStatsInputs() {
        // 新装机：0 角色 → 统计行隐藏的判据（charactersCount=0）+ companionDays=null + 回忆 0。
        val vm = buildVm(charactersCount = 0, earliestCreation = null, meetingMemories = 0, worldMemories = 0)
        withSubscriptions(vm) {
            assertEquals(0, vm.charactersCount.value)
            assertEquals(0, vm.memoriesCount.value)
            assertNull(vm.companionDays.value)
        }
    }
}
