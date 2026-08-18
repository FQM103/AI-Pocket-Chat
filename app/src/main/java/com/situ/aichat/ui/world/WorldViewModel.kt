package com.situ.aichat.ui.world

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.situ.aichat.BuildConfig
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.ui.world.continent.ContinentCamSnapshot
import com.situ.aichat.ui.world.continent.ContinentSceneData
import com.situ.aichat.ui.world.interior.InteriorData
import com.situ.aichat.ui.world.interior.InteriorSceneData
import com.situ.aichat.ui.world.planet.PlanetMath
import com.situ.aichat.ui.world.town.TownCamSnapshot
import com.situ.aichat.ui.world.town.TownData
import com.situ.aichat.ui.world.town.TownSceneData
import com.situ.aichat.world.WorldBootstrap
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.cast.WorldResidentService
import com.situ.aichat.world.WorldDebugEntry
import com.situ.aichat.world.WorldFocusEntry
import com.situ.aichat.world.parseDebugScene
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.atlas.WorldCuratedCities
import com.situ.aichat.world.atlas.WorldRegions
import com.situ.aichat.world.notify.WorldNotifyStateStore
import com.situ.aichat.world.stage.UserTravelChip
import com.situ.aichat.world.stage.WorldStageService
import com.situ.aichat.world.stage.WorldTownCast
import com.situ.aichat.world.stage.WorldWeather
import com.situ.aichat.world.travel.DepartResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 世界屏当前场景（星球 / 大陆-某大区 / 小镇-某城·转场瞬时态不进此·恒回星球=进程死亡后回星球静止态·E15）。 */
sealed interface WorldScene {
    data object Planet : WorldScene
    data class Continent(val regionId: String) : WorldScene
    data class Town(val cityId: String) : WorldScene // W9c
    data class Interior(val cityId: String, val placeId: String) : WorldScene // W9d
    data object StarMap : WorldScene // W10 关系星图（非 GL·从 Planet 入口 chip 进·进程死亡恒回 Planet）
}

/** 用户位置态（gate 用·§3.4·cityId = 当前所在城·traveling = 在途）。 */
data class WorldPresenceUi(val cityId: String, val traveling: Boolean)

/**
 * 世界屏状态（W9a 图纸 §3.2）：进屏静默建世 → 由 seed 取图集家乡城 + 派生 seedOff → [ready]；
 * markWorldEntered（W8 挂账落地）；低电量/发热 → [staticMode] 退静帧（§17）。
 */
data class WorldUiState(
    val ready: Boolean = false,
    val seed: Long = 0L,
    val seedOff: Float = 0f,
    val homeCityName: String = "",
    val homeX: Int = 0,
    val homeY: Int = 0,
    val staticMode: Boolean = false,
    val scene: WorldScene = WorldScene.Planet,
    val regionChips: List<WorldRegionChip> = emptyList(),
    val userTimezoneId: String? = null, // 🔵-4：世界时区（bootstrap 后填·chrome 天气词/旅行 ETA/在途 chip 展示时刻用之·与窗景几何一致）
)

/** 送 TA 离开确认弹窗态（战役 B·O6·图纸 §4.3）：暖纸弹窗标题/正文用 name·确认走 nativeId。 */
data class PendingDismissUi(val nativeId: String, val name: String)

@HiltViewModel
class WorldViewModel @Inject constructor(
    private val bootstrap: WorldBootstrap,
    private val notifyStateStore: WorldNotifyStateStore,
    private val sceneStrings: WorldSceneStrings,
    private val stageService: WorldStageService,
    private val worldDao: WorldDao,
    private val travelVm: WorldTravelSheetViewModel,
    private val conversationRepo: ConversationRepository,
    private val eavesdropService: com.situ.aichat.world.live.WorldEavesdropService,
    private val loreService: com.situ.aichat.world.live.WorldLoreService,
    private val residentService: WorldResidentService,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(WorldUiState())
    val ui: StateFlow<WorldUiState> = _ui.asStateFlow()

    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalStatus = status
        refreshStaticMode()
    }
    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshStaticMode()
    }

    // ⚠️ 必须声明在 init 之前：init 派出的 IO 协程（refreshPresence/refreshOnce）会立即写这些流（W10 R1 🔴-1·构造竞态 NPE）。
    private val _cast = MutableStateFlow<WorldTownCast?>(null)
    val cast: StateFlow<WorldTownCast?> = _cast.asStateFlow()
    private val _travelChip = MutableStateFlow<UserTravelChip?>(null)
    val travelChip: StateFlow<UserTravelChip?> = _travelChip.asStateFlow()
    private val _presence = MutableStateFlow<WorldPresenceUi?>(null)
    val presence: StateFlow<WorldPresenceUi?> = _presence.asStateFlow()

    private val _travelQuote = MutableStateFlow<TravelQuote?>(null)
    val travelQuote: StateFlow<TravelQuote?> = _travelQuote.asStateFlow()
    private val _travelResult = MutableStateFlow<TravelSheetResult?>(null)
    val travelResult: StateFlow<TravelSheetResult?> = _travelResult.asStateFlow()

    private val _chatNav = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** 去聊天导航（conversationUuid·WorldScreen 收 → 导航 chat/{uuid}）。 */
    val chatNav: SharedFlow<String> = _chatNav.asSharedFlow()

    // 战役 B（O6·图纸 §4.3）：送 TA 离开——暖纸确认弹窗态 + 送离 toast 事件。
    private val _pendingDismiss = MutableStateFlow<PendingDismissUi?>(null)
    val pendingDismiss: StateFlow<PendingDismissUi?> = _pendingDismiss.asStateFlow()
    private val _dismissedToast = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val dismissedToast: SharedFlow<String> = _dismissedToast.asSharedFlow()

    init {
        // W8 挂账落地：每次进屏都调（语义=打开世界界面）→「回世界即恢复通知封顶曲线」闭环。
        notifyStateStore.markWorldEntered(System.currentTimeMillis())

        ContextCompat.registerReceiver(
            appContext,
            powerSaveReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // 发热监听在部分 OEM ROM/测试环境不受支持 → best-effort：不支持则不做发热降档（省电模式仍生效）。
        runCatching {
            powerManager.addThermalStatusListener(thermalListener)
            thermalStatus = powerManager.currentThermalStatus
        }
        refreshStaticMode()

        viewModelScope.launch(Dispatchers.IO) {
            val state = bootstrap.ensureCreated(System.currentTimeMillis())
            val atlas = WorldAtlas.of(state.seed)
            val home = atlas.cityById(state.userHomeCityId)
            val hx = home?.x ?: 0
            val hy = home?.y ?: 0
            val seedOff = PlanetMath.deriveSeedOff(state.seed, PlanetMath.homeUnitVector(hx, hy))
            val chips = WorldRegions.ALL.map { WorldRegionChip(it.id, it.name, it.id == "yunze", it.flavor) }
            _ui.update {
                it.copy(
                    ready = true,
                    seed = state.seed,
                    seedOff = seedOff,
                    homeCityName = home?.name.orEmpty(),
                    homeX = hx,
                    homeY = hy,
                    regionChips = chips,
                    userTimezoneId = state.userTimezoneId,
                )
            }
            refreshPresence() // 🔴-1：bootstrap 完成即刷位置/在途 chip（大陆城卡「出发」钮/常驻 chip 不再等进小镇）。
            // W10 debug 直达（效率契约·仅 DEBUG·ready 就绪后消费一次·非法/查无 → 忽略留 Planet）。
            if (BuildConfig.DEBUG) WorldDebugEntry.take()?.let(::applyDebugScene)
            // W13 聊天状态行跳转（生产·非 DEBUG 门·复用 parseDebugScene 验真·非法 spec 安全落空·图纸 §3.6）。
            WorldFocusEntry.take()?.let(::applyDebugScene)
        }

        // 🔴-1：位置/在途 chip 场景无关轮询（VM 存活期恒跑·与 cast 循环解耦·§4.8「chip 所有场景常驻至 arriveAt」）。
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                refreshPresence()
                delay(60_000L)
            }
        }
    }

    // ── W9b 场景状态机 + 大陆装载（图纸 §3.1）──

    private val continentCache = mutableMapOf<String, ContinentSceneData>()
    private val continentCacheLock = Mutex()

    /** 星球出发前相机快照（VM 私有·非 UiState·进程死亡即丢 = 恒回星球初始位·可接受）。 */
    var savedPlanetCamera: Triple<Float, Float, Float>? = null
        private set

    /** 进大陆（家乡恒为入口·拍板①·从星球进 = 新鲜 intro·清大陆快照）。 */
    fun enterContinent() {
        savedContinentCamera = null
        _ui.update { it.copy(scene = WorldScene.Continent("yunze")) }
    }

    /** 切大区（同 id 忽略·拍板②）。 */
    fun selectRegion(regionId: String) = _ui.update {
        if (it.scene == WorldScene.Continent(regionId)) it else it.copy(scene = WorldScene.Continent(regionId))
    }

    /** 回星球（清大陆快照·下次进大陆为新鲜 intro）。 */
    fun backToPlanet() {
        savedContinentCamera = null
        _ui.update { it.copy(scene = WorldScene.Planet) }
    }

    /** 存星球出发前相机姿态（回星球时恢复）。 */
    fun savePlanetCamera(yaw: Float, pitch: Float, dist: Float) {
        savedPlanetCamera = Triple(yaw, pitch, dist)
    }

    // ── W10 关系星图（非 GL·§3.1·进程死亡恒回 Planet=scene 不持久化）──

    /** 进星图（仅从 Planet·入口 chip·转场编排见 [WorldTransitions.startFadeToStarmap]）。 */
    fun enterStarmap() = _ui.update { it.copy(scene = WorldScene.StarMap) }

    /** 回星球（星球重建走 savedPlanetCamera 恢复姿态）。 */
    fun backFromStarmap() = _ui.update { it.copy(scene = WorldScene.Planet) }

    /** debug 直达（§3.3·仅 DEBUG 调用方）：解析成合法场景即切场景·否则忽略留 Planet。 */
    private fun applyDebugScene(spec: String) {
        parseDebugScene(spec, WorldAtlas.of(_ui.value.seed))?.let { scene -> _ui.update { it.copy(scene = scene) } }
    }

    /** 某大区盒景数据（Default 线程算站位 + 文案·每区只算一次·缓存幂等·E13）。W12 C4：带入首访点亮 canon lore（存在则城市卡优先显示）。 */
    internal suspend fun continentOf(regionId: String): ContinentSceneData = withContext(Dispatchers.Default) {
        continentCacheLock.withLock {
            continentCache[regionId] ?: run {
                val canonLore = worldDao.getAllLore().mapNotNull { l ->
                    com.situ.aichat.world.live.WorldLoreService.loreTextOf(l.loreJson)?.let { l.cityId to it }
                }.toMap()
                ContinentSceneData.fromAtlas(WorldAtlas.of(_ui.value.seed), regionId, sceneStrings.continent, canonLore)
                    .also { continentCache[regionId] = it }
            }
        }
    }

    /**
     * 首访点亮（W12 C4·§7.A 第三时刻）：进小镇即试点亮该城风物志（服务层含 E13 全条件矩阵·幂等·失败静默）。
     * 新写入 → 失效该区大陆缓存，令城市卡下次改显 canon lore。fire-and-forget（不阻塞进屏）。
     */
    fun tryLightUpLore(cityId: String) = viewModelScope.launch(Dispatchers.IO) {
        if (!loreService.tryLightUp(cityId, System.currentTimeMillis())) return@launch
        val regionId = WorldAtlas.of(_ui.value.seed).cityById(cityId)?.regionId ?: return@launch
        continentCacheLock.withLock { continentCache.remove(regionId) }
    }

    // ── W9c 小镇场景 + 装载（图纸 §3.1）──

    private val townCache = mutableMapOf<String, TownData>()
    private val townCacheLock = Mutex()

    /** 大陆出发前相机恢复态（进小镇存·回大陆恢复·进程死亡即丢 = 恒回大陆入口·可接受·internal=含内部类型）。 */
    internal var savedContinentCamera: ContinentCamRestore? = null
        private set

    /** 进小镇。 */
    fun enterTown(cityId: String) = _ui.update { it.copy(scene = WorldScene.Town(cityId)) }

    /** 回大陆（cityId → regionId 由图集查回·保留大陆快照供恢复）。 */
    fun backToContinent() = _ui.update {
        val cityId = (it.scene as? WorldScene.Town)?.cityId ?: return@update it
        val regionId = WorldAtlas.of(it.seed).cityById(cityId)?.regionId ?: "yunze"
        it.copy(scene = WorldScene.Continent(regionId))
    }

    /** 存大陆出发前相机（回大陆恢复·含 target/tDist·internal=含内部类型）。 */
    internal fun saveContinentCamera(snapshot: ContinentCamSnapshot, tDist: Float) {
        savedContinentCamera = snapshot to tDist
    }

    /** 某城小镇装载数据（Default 线程建布局 + 文案 + 天空·每城只算一次·缓存幂等）。 */
    internal suspend fun townOf(cityId: String): TownData = withContext(Dispatchers.Default) {
        townCacheLock.withLock {
            townCache.getOrPut(cityId) { TownSceneData.of(WorldAtlas.of(_ui.value.seed), cityId, sceneStrings.town) }
        }
    }

    /** 小镇 chrome 标题（城名 / 副标「{大区名} · {specialty}」·atlas 直取·同步·§4.3）。 */
    fun townChrome(cityId: String): Pair<String, String> {
        val atlas = WorldAtlas.of(_ui.value.seed)
        val city = atlas.cityById(cityId) ?: return "" to ""
        val region = atlas.regionById(city.regionId)
        val subtitle = if (region != null) sceneStrings.townSubtitle(region.name, city.specialty) else ""
        return city.name to subtitle
    }

    // ── W9d 室内场景 + 演员表 + 旅行 + 去聊天（图纸 §3.1/§3.2/§3.3/§3.4）──

    private val interiorCache = mutableMapOf<String, InteriorData>()
    private val interiorCacheLock = Mutex()

    /** 进室内前的小镇相机快照（VM 私有·进程死亡即丢 = 恒回 Planet·§3.1）。 */
    internal var savedTownCamera: Pair<TownCamSnapshot, Float>? = null
        private set

    private var refreshJob: Job? = null

    fun enterInterior(cityId: String, placeId: String) = _ui.update { it.copy(scene = WorldScene.Interior(cityId, placeId)) }

    /** 回小镇（Interior → Town·§3.1）。 */
    fun backToTown() = _ui.update {
        val cityId = (it.scene as? WorldScene.Interior)?.cityId ?: return@update it
        it.copy(scene = WorldScene.Town(cityId))
    }

    /** 存进室内前的小镇相机（回小镇恢复·§4.9·internal=含内部类型）。 */
    internal fun saveTownCamera(snapshot: TownCamSnapshot, tDist: Float) { savedTownCamera = snapshot to tDist }

    /** 室内装载（按 (placeId, 天气, 昼夜) 缓存·Default 建几何·§3.3·无室内/无世界态 → null）。 */
    internal suspend fun interiorOf(placeId: String): InteriorData? = withContext(Dispatchers.Default) {
        if (!InteriorSceneData.hasInterior(placeId)) return@withContext null
        val state = worldDao.getState() ?: return@withContext null
        val zone = WorldClock.resolveZone(state.userTimezoneId)
        val now = System.currentTimeMillis()
        val night = WorldWeather.isNight(now, zone)
        val kind = WorldWeather.kindOf(state.seed, cityIdOfPlace(placeId), WorldClock.localDateOf(now, zone))
        interiorCacheLock.withLock {
            interiorCache.getOrPut("$placeId:$kind:$night") { InteriorSceneData.of(placeId, kind, night)!! }
        }
    }

    /** placeId → cityId（前缀·yunye_/taoqiu_/xiyu_）。 */
    private fun cityIdOfPlace(placeId: String): String = when {
        placeId.startsWith("taoqiu_") -> "city_taoqiu"
        placeId.startsWith("xiyu_") -> "city_xiyu"
        else -> "city_yunye"
    }

    /** 演员表/在途/位置刷新（进 Town/Interior 装载 + 60s 轮询·离场即停·§3.2）。 */
    fun startCastRefresh(cityId: String) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                refreshOnce(cityId)
                delay(60_000L)
            }
        }
    }

    fun stopCastRefresh() { refreshJob?.cancel(); refreshJob = null; _cast.value = null } // 只清 cast·不碰 presence 轮询（🔴-1）

    private suspend fun refreshOnce(cityId: String) {
        _cast.value = stageService.castOf(cityId, System.currentTimeMillis())
    }

    /** 🔴-1：位置/在途 chip 刷新（场景无关·init 轮询 + depart 后调）。 */
    private suspend fun refreshPresence() {
        val now = System.currentTimeMillis()
        _travelChip.value = stageService.userTravel(now)
        _presence.value = WorldPresenceUi(stageService.userPresenceCityId(now), stageService.isUserTraveling(now))
    }

    /** 室内 chrome（地点名 / 副标「{城名} · {天气词}」·§4.10·🔵-4：天气词与窗景几何同用世界时区）。 */
    fun interiorChrome(cityId: String, placeId: String): Pair<String, String> {
        val placeName = WorldCuratedCities.PLACES.firstOrNull { it.id == placeId }?.name ?: ""
        val cityName = WorldAtlas.of(_ui.value.seed).cityById(cityId)?.name ?: ""
        val zone = WorldClock.resolveZone(_ui.value.userTimezoneId) // 🔵-4：与窗景几何同用世界时区（原 null=系统时区）
        val now = System.currentTimeMillis()
        val kind = WorldWeather.kindOf(_ui.value.seed, cityId, WorldClock.localDateOf(now, zone))
        val word = WorldWeather.word(kind, WorldWeather.isNight(now, zone))
        return placeName to appContext.getString(R.string.world_interior_subtitle, cityName, word)
    }

    /** 用户家 placeId（宠物落位·仅当当前小镇 = 用户家城·§4.6A）。 */
    suspend fun homePlaceIdFor(cityId: String): String? =
        if (worldDao.getState()?.userHomeCityId == cityId) "yunye_home" else null

    /** W6 招募触发转发（§3.2·幂等·成功刷 cast）。 */
    suspend fun onMeetNative(nativeId: String): Boolean {
        val newly = stageService.onMeetNative(nativeId, System.currentTimeMillis())
        if (newly) (ui.value.scene as? WorldScene.Town)?.let { refreshOnce(it.cityId) }
            ?: (ui.value.scene as? WorldScene.Interior)?.let { refreshOnce(it.cityId) }
        return newly
    }

    /** 送 TA 离开（战役 B·O6·§4.3）：仅自建未招募居民由人物卡幽灵按钮打开暖纸确认弹窗。 */
    fun requestDismissResident(nativeId: String, name: String) {
        _pendingDismiss.value = PendingDismissUi(nativeId, name)
    }

    fun cancelDismissResident() {
        _pendingDismiss.value = null
    }

    /** 确认送离：deleteUnrecruited（已招募会返 false·卡片本就不显示=双保险）→ 刷当前场景 cast（TA 从地图/卡消失）+ toast。 */
    fun confirmDismissResident() {
        val p = _pendingDismiss.value ?: return
        _pendingDismiss.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val slug = p.nativeId.removePrefix(WorldIds.NATIVE_PREFIX)
            if (residentService.deleteUnrecruited(slug)) {
                _dismissedToast.tryEmit(p.name)
                (ui.value.scene as? WorldScene.Town)?.let { refreshOnce(it.cityId) }
                    ?: (ui.value.scene as? WorldScene.Interior)?.let { refreshOnce(it.cityId) }
            }
        }
    }

    /** 去聊天（getOrCreateForCharacter 幂等复用会话·emit conversationUuid·§4.6C/E19）。 */
    fun openChat(characterUuid: String, name: String) = viewModelScope.launch(Dispatchers.IO) {
        _chatNav.tryEmit(conversationRepo.getOrCreateForCharacter(characterUuid, name))
    }

    /**
     * 偷听一次（W12 C3·§3/§4.6）：由 InteriorSceneView 装配好当前地点可偷听池（guests ∪ discovered 原住民）→
     * 从当前 Interior 场景取城名/地点名 → 交 [eavesdropService]（选对/冷却/预算/生成/记事件全在服务层·旁观边界）。
     */
    suspend fun eavesdrop(pool: List<com.situ.aichat.world.live.EavesdropEntity>): com.situ.aichat.world.live.EavesdropOutcome =
        withContext(Dispatchers.IO) {
            val scene = _ui.value.scene as? WorldScene.Interior ?: return@withContext com.situ.aichat.world.live.EavesdropOutcome.Unavailable
            val cityName = WorldAtlas.of(_ui.value.seed).cityById(scene.cityId)?.name.orEmpty()
            val placeName = WorldCuratedCities.PLACES.firstOrNull { it.id == scene.placeId }?.name.orEmpty()
            eavesdropService.eavesdrop(pool, scene.cityId, scene.placeId, cityName, placeName, System.currentTimeMillis())
        }

    // 旅行单（§3.4·钱路只调不改）。
    fun openTravel(destCityId: String) = viewModelScope.launch(Dispatchers.IO) {
        _travelResult.value = null
        _travelQuote.value = travelVm.quote(destCityId, System.currentTimeMillis())
    }

    fun closeTravel() { _travelQuote.value = null; _travelResult.value = null }

    fun departTravel(mode: String) = viewModelScope.launch(Dispatchers.IO) {
        val dest = _travelQuote.value?.destCityId ?: return@launch
        when (val r = travelVm.depart(dest, mode, System.currentTimeMillis())) {
            is DepartResult.Departed -> {
                _travelQuote.value = null; _travelResult.value = null
                (ui.value.scene as? WorldScene.Town)?.let { refreshOnce(it.cityId) }
                refreshPresence() // 🔴-1：出发后刷 presence（traveling=true → 城卡「出发」钮消失）+ 在途 chip。
            }
            is DepartResult.InsufficientGold -> _travelResult.value = TravelSheetResult.InsufficientGold(r.need, r.have)
            else -> _travelResult.value = TravelSheetResult.Failed
        }
    }

    private fun refreshStaticMode() {
        val static = powerManager.isPowerSaveMode || thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        _ui.update { it.copy(staticMode = static) }
    }

    override fun onCleared() {
        runCatching { powerManager.removeThermalStatusListener(thermalListener) }
        runCatching { appContext.unregisterReceiver(powerSaveReceiver) }
    }
}
