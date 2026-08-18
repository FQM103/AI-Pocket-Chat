package com.situ.aichat.ui.character

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.worldbook.WorldBookRepository
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.member.WorldMembershipService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 城市选择 sheet 的一个大区（id + 名·图纸 §4.2）。 */
data class RegionUi(val id: String, val name: String)

/** 城市选择 sheet 的一座城：是否用户家乡城（金点 + 金底 tag）/ 是否 TA 当前住址城（行尾「当前」）。 */
data class CityUi(val id: String, val name: String, val isUserHome: Boolean, val isCurrentAddress: Boolean)

/**
 * 编辑页世界段状态（图纸 §3.3·仅编辑模式·characterUuid 取 SavedStateHandle）。四态派生：普通关/开/世界书锁/
 * 原住民锁；住址由图集解析。动作委托 [WorldMembershipService]，每次动作后重载。
 *
 * `selectedRegionId` = 图纸 §3.3 UiState 的补全项（§4.2 城市 sheet 需高亮当前大区·见 §11 施工日志）。
 */
data class CharacterWorldUiState(
    val loaded: Boolean = false,
    val joined: Boolean = false,
    val nativeOrigin: Boolean = false,
    val worldbookBound: Boolean = false,
    val homeCityId: String = WorldIds.HOME_CITY_ID,
    val homeCityName: String = "云野镇",
    val sameCityAsUser: Boolean = true,
    val regions: List<RegionUi> = emptyList(),
    val citiesOfRegion: List<CityUi> = emptyList(),
    val selectedRegionId: String = "",
)

@HiltViewModel
class CharacterWorldViewModel @Inject constructor(
    private val characterDao: CharacterDao,
    private val worldNativeDao: WorldNativeDao,
    private val worldDao: WorldDao,
    private val worldBookRepository: WorldBookRepository,
    private val membershipService: WorldMembershipService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val characterUuid: String = savedStateHandle["characterUuid"] ?: ""

    private val _state = MutableStateFlow(CharacterWorldUiState())
    val state: StateFlow<CharacterWorldUiState> = _state.asStateFlow()

    // 图集查询用（世界建成后 seed 固定·WorldAtlas.of 内部单座缓存·反复取零成本）。
    private var seed: Long? = null
    private var userHomeCityId: String = WorldIds.HOME_CITY_ID

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch { loadInternal() }
    }

    private suspend fun loadInternal() {
        val character = characterDao.getByUuid(characterUuid)
        if (character == null) {
            _state.value = CharacterWorldUiState(loaded = true)
            return
        }
        val nativeOrigin = worldNativeDao.getByRecruitedUuid(characterUuid) != null
        val worldbookBound = worldBookRepository.boundBookUuids(characterUuid).isNotEmpty()
        val worldState = worldDao.getState()
        seed = worldState?.seed
        userHomeCityId = worldState?.userHomeCityId ?: WorldIds.HOME_CITY_ID
        val atlas = seed?.let { WorldAtlas.of(it) }
        val homeCityId = character.worldHomeCityId
        val homeCity = atlas?.cityById(homeCityId)
        val homeRegionId = homeCity?.regionId ?: atlas?.regions?.firstOrNull()?.id ?: ""
        _state.value = CharacterWorldUiState(
            loaded = true,
            joined = character.joinedWorld,
            nativeOrigin = nativeOrigin,
            worldbookBound = worldbookBound,
            homeCityId = homeCityId,
            homeCityName = homeCity?.name ?: "云野镇",
            sameCityAsUser = homeCityId == userHomeCityId,
            regions = atlas?.regions?.map { RegionUi(it.id, it.name) } ?: emptyList(),
            citiesOfRegion = citiesFor(homeRegionId, homeCityId),
            selectedRegionId = homeRegionId,
        )
    }

    /** 某大区的城列表（含程序城·奇观不入列·图纸 §4.2）。 */
    private fun citiesFor(regionId: String, currentHomeCityId: String): List<CityUi> {
        val atlas = seed?.let { WorldAtlas.of(it) } ?: return emptyList()
        return atlas.citiesIn(regionId).map { c ->
            CityUi(
                id = c.id,
                name = c.name,
                isUserHome = c.id == userHomeCityId,
                isCurrentAddress = c.id == currentHomeCityId,
            )
        }
    }

    /** 城市 sheet 切大区（只重算城列表·不落库）。 */
    fun selectRegion(regionId: String) {
        _state.value = _state.value.copy(
            selectedRegionId = regionId,
            citiesOfRegion = citiesFor(regionId, _state.value.homeCityId),
        )
    }

    /** 开关 off→on：加入世界（图纸 §3.3·关→开无需确认）。 */
    fun join() {
        viewModelScope.launch {
            membershipService.join(characterUuid, System.currentTimeMillis())
            loadInternal()
        }
    }

    /** 离开确认后：离开世界（图纸 §3.3·on→off 先弹确认由 UI 管）。 */
    fun leave() {
        viewModelScope.launch {
            membershipService.leave(characterUuid, System.currentTimeMillis())
            loadInternal()
        }
    }

    /** 搬家确认后：搬到目标城（图纸 §3.3·同城/查无由服务判 NoOp）。 */
    fun move(toCityId: String) {
        viewModelScope.launch {
            membershipService.move(characterUuid, toCityId, System.currentTimeMillis())
            loadInternal()
        }
    }
}
