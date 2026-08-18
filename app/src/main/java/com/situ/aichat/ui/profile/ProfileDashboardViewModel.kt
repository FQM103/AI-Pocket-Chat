package com.situ.aichat.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.dao.WorldMemoryDao
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.repository.MomentRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.economy.CurrencyService
import com.situ.aichat.gift.GiftCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * 「我」页仪表盘 VM（主角卡 + 资产格统计）。契约见 `FABLE5_PROFILE_REDESIGN_PROPOSAL.md` §4/§9.3。
 *
 * 全部只读聚合：用户资料、货币系统开关（gate 钱包/礼物条）、资产统计、主角卡陪伴统计三件
 * （一起走过天数 / 角色数 / 共同回忆数=见面回忆行+世界记忆行）。计数一律 COUNT 直查（K5 纪律），
 * 不搬全量行。与设置页**解耦**：设置页仍用 [UserProfileViewModel]，本 VM 只服务仪表盘。
 */
@HiltViewModel
class ProfileDashboardViewModel @Inject constructor(
    profileDao: UserProfileDao,
    settings: SettingsRepository,
    momentRepository: MomentRepository,
    currencyService: CurrencyService,
    giftDao: GiftDao,
    characterDao: CharacterDao,
    offlineMeetingMemoryDao: OfflineMeetingMemoryDao,
    worldMemoryDao: WorldMemoryDao,
) : ViewModel() {

    /** 单例用户资料（头像 / 昵称 / bio）。 */
    val profile: StateFlow<UserProfileEntity?> =
        profileDao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 货币系统总开关——关 → 仪表盘隐藏钱包/礼物条（含礼物店与礼物盒），仅留「我的动态」（并改全宽卡，用户拍板 2026-06-18）。
     * 初值 true = 货币系统默认开（与 [SettingsRepository] 默认一致），避免加载瞬间闪烁隐藏。
     */
    val currencyEnabled: StateFlow<Boolean> =
        settings.appSettings
            .map { it.currencySystemEnabled }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 「我的动态」统计 = 用户本人非删朋友圈条数（实时·COUNT 直查，不再搬全量帖+关系只为数个数，K5）。 */
    val momentsCount: StateFlow<Int> =
        momentRepository.observeUserFeedCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 「我的钱包」统计 = 金币余额（实时·[CurrencyService] 单一事实源；未建钱包回退 100）。 */
    val coinBalance: StateFlow<Int> =
        currencyService.observeUserCoinBalance()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 「礼物盒」统计 = 收到的礼物件数（receiverType=user·实时·COUNT 直查，K5）。 */
    val receivedGiftsCount: StateFlow<Int> =
        giftDao.observeUserReceivedGiftCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 「礼物店」统计 = 在售礼物款数（静态目录，随 App 版本更新）。 */
    val giftCatalogCount: Int = GiftCatalog.allItems.size

    /** 陪伴统计·身边朋友数（=0 时主角卡统计行整行隐藏·契约 §9.1）。 */
    val charactersCount: StateFlow<Int> =
        characterDao.observeCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 陪伴统计·一起走过天数 = 最早角色 creationDate 至今的自然日差 + 1（当天=第 1 天）。无角色 = null。
     * 「今天」在每次上游发射时取值：跨天不主动重算，但 WhileSubscribed(5s) 下重进页面即重订阅刷新，仪表盘足够。
     */
    val companionDays: StateFlow<Int?> =
        characterDao.observeEarliestCreationDate()
            .map { companionDaysSince(it, LocalDate.now(), ZoneId.systemDefault()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 陪伴统计·共同回忆 = 线下见面回忆行 + 世界记忆行（口径注记见契约 §9.6·两 COUNT 直查相加）。 */
    val memoriesCount: StateFlow<Int> =
        combine(
            offlineMeetingMemoryDao.observeCountAll(),
            worldMemoryDao.observeCountAll(),
        ) { meetings, world -> meetings + world }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

/**
 * 「一起走过 N 天」纯函数（契约 §9.1）：最早角色创建时刻（epoch ms）在 [zone] 下的自然日 → [today] 的
 * 日差 + 1；创建当天 = 1。无角色（null）= null；未来时间戳（时钟回拨等）钳到 1，绝不出 0 或负数。
 */
internal fun companionDaysSince(earliestMillis: Long?, today: LocalDate, zone: ZoneId): Int? {
    if (earliestMillis == null) return null
    val firstDay = Instant.ofEpochMilli(earliestMillis).atZone(zone).toLocalDate()
    val diff = ChronoUnit.DAYS.between(firstDay, today).toInt()
    return (diff + 1).coerceAtLeast(1)
}
