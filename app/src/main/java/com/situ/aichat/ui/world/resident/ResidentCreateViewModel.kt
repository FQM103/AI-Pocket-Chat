package com.situ.aichat.ui.world.resident

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.world.WorldBootstrap
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.cast.CreateResult
import com.situ.aichat.world.cast.ResidentDraft
import com.situ.aichat.world.cast.WorldResidentService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 出生地选择用的大区 / 城市轻模型（atlas 运行时派生·非硬编码·图纸 §3.4·用户拍板未来新地图自动纳入）。 */
data class ResidentRegionUi(val id: String, val name: String)
data class ResidentCityUi(val id: String, val name: String)

/**
 * 创建居民表单态（图纸 §3.4·初值全空/默认）。UI-only 折叠/输入可见态由 composable 的 remember 持有，本 state 只装数据。
 * [genderPreset] ∈ "male"/"female"/"custom"（提交时 custom → [genderCustom] 原文·§3.1）。
 */
data class ResidentFormState(
    val avatarPath: String? = null,
    val name: String = "",
    val genderPreset: String = "female",
    val genderCustom: String = "",
    val ageText: String = "26",
    val occupation: String = "",
    val personaBrief: String = "",
    val traits: List<String> = emptyList(),
    val customTraitDraft: String = "",
    val cityId: String = WorldIds.HOME_CITY_ID,
    val cityName: String = "",
    val regionName: String = "",
    val initialRelationText: String = "",
    val fuelBias: String = "balanced",
    val freeformLore: String = "",
    val submitting: Boolean = false,
    val nameError: Boolean = false,
    val result: CreateResult? = null,
    // 出生地选择（WorldAtlas 现算·图纸 §3.4）。
    val regions: List<ResidentRegionUi> = emptyList(),
    val selectedRegionId: String = "",
    val citiesOfRegion: List<ResidentCityUi> = emptyList(),
)

/**
 * 创建居民表单 VM（战役 B·图纸 §3.4）：表单态 + 出生地列表（[WorldAtlas] 实时派生）+ 提交 →
 * [WorldResidentService.create]。init 先幂等静默建世取种子（create 落户事件/刷花名册需要种子）。
 * 草稿不持久化（进程死即弃·图纸 E11 拍板可接受）。
 */
@HiltViewModel
class ResidentCreateViewModel @Inject constructor(
    private val residentService: WorldResidentService,
    private val bootstrap: WorldBootstrap,
) : ViewModel() {

    private val _state = MutableStateFlow(ResidentFormState())
    val state: StateFlow<ResidentFormState> = _state.asStateFlow()

    private var seed: Long = 0L

    init {
        viewModelScope.launch {
            seed = bootstrap.ensureCreated(System.currentTimeMillis()).seed
            val atlas = WorldAtlas.of(seed)
            val home = atlas.cityById(WorldIds.HOME_CITY_ID)
            val regionId = home?.regionId ?: atlas.regions.firstOrNull()?.id.orEmpty()
            _state.update {
                it.copy(
                    regions = atlas.regions.map { r -> ResidentRegionUi(r.id, r.name) },
                    selectedRegionId = regionId,
                    citiesOfRegion = atlas.citiesIn(regionId).map { c -> ResidentCityUi(c.id, c.name) },
                    cityId = home?.id ?: WorldIds.HOME_CITY_ID,
                    cityName = home?.name.orEmpty(),
                    regionName = atlas.regionById(regionId)?.name.orEmpty(),
                )
            }
        }
    }

    fun setAvatar(path: String?) = _state.update { it.copy(avatarPath = path) }
    fun setName(v: String) = _state.update { it.copy(name = v, nameError = false, result = if (it.result == CreateResult.InvalidName) null else it.result) }
    fun setGenderPreset(v: String) = _state.update { it.copy(genderPreset = v) }
    fun setGenderCustom(v: String) = _state.update { it.copy(genderCustom = v) }
    fun setAge(v: String) = _state.update { it.copy(ageText = v.filter(Char::isDigit).take(3)) } // 仅收数字·E5
    fun setOccupation(v: String) = _state.update { it.copy(occupation = v) }
    fun setPersonaBrief(v: String) = _state.update { it.copy(personaBrief = v) }
    fun setInitialRelation(v: String) = _state.update { it.copy(initialRelationText = v) }
    fun setFuelBias(v: String) = _state.update { it.copy(fuelBias = v) }
    fun setFreeformLore(v: String) = _state.update { it.copy(freeformLore = v) }
    fun setCustomTraitDraft(v: String) = _state.update { it.copy(customTraitDraft = v) }

    /** 点性格底色 chip：已选 → 取消；未选且不足 3 个 → 加；已满 3 个 → 静默忽略（footer 常显「最多选 3 个」·§4.1-g）。 */
    fun toggleTrait(trait: String) = _state.update { s ->
        when {
            trait in s.traits -> s.copy(traits = s.traits - trait)
            s.traits.size >= MAX_TRAITS -> s // 静默忽略
            else -> s.copy(traits = s.traits + trait)
        }
    }

    /** 提交自定义性格词：非空、未满 3、未重复 → 成新选中 chip 并清草稿。 */
    fun commitCustomTrait() = _state.update { s ->
        val t = s.customTraitDraft.trim()
        if (t.isEmpty() || t in s.traits || s.traits.size >= MAX_TRAITS) s.copy(customTraitDraft = "")
        else s.copy(traits = s.traits + t, customTraitDraft = "")
    }

    /** 选大区（城市列表现算切换）。 */
    fun selectRegion(regionId: String) = _state.update {
        it.copy(
            selectedRegionId = regionId,
            citiesOfRegion = WorldAtlas.of(seed).citiesIn(regionId).map { c -> ResidentCityUi(c.id, c.name) },
        )
    }

    /** 选城（回填出生地·regionName 取当前选中大区名）。 */
    fun selectCity(cityId: String, cityName: String) = _state.update {
        it.copy(cityId = cityId, cityName = cityName, regionName = WorldAtlas.of(seed).regionById(it.selectedRegionId)?.name.orEmpty())
    }

    fun clearResult() = _state.update { it.copy(result = null) }

    /**
     * 搬来世界（§3.3/§3.4·E12 门闩）：submitting 期间再点直接返回（同步 check-and-set 在主线程原子·至多落库一次）；
     * 空名先本地拦（nameError）；否则组 [ResidentDraft] → [WorldResidentService.create]，结果回 state 由 UI 消费。
     */
    fun submit() {
        val s = _state.value
        if (s.submitting) return // E12 门闩
        if (s.name.trim().isEmpty()) {
            _state.update { it.copy(nameError = true, result = CreateResult.InvalidName) }
            return
        }
        _state.update { it.copy(submitting = true, nameError = false, result = null) }
        viewModelScope.launch {
            val gender = if (s.genderPreset == "custom") s.genderCustom.trim().ifEmpty { "female" } else s.genderPreset
            val result = residentService.create(
                ResidentDraft(
                    name = s.name,
                    gender = gender,
                    ageText = s.ageText,
                    cityId = s.cityId,
                    occupation = s.occupation,
                    personaBrief = s.personaBrief,
                    traits = s.traits,
                    freeformLore = s.freeformLore,
                    initialRelationText = s.initialRelationText,
                    fuelBias = s.fuelBias,
                    avatarPath = s.avatarPath,
                ),
            )
            _state.update { it.copy(submitting = false, result = result) }
        }
    }

    companion object {
        const val MAX_TRAITS = 3
    }
}
