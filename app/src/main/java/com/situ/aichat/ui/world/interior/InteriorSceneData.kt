package com.situ.aichat.ui.world.interior

import com.situ.aichat.ui.world.continent.TriStream
import com.situ.aichat.world.stage.WorldWeatherKind

/** 室内世界锚点（世界坐标·投影用·W9d 图纸 §3.3）。 */
data class InteriorAnchor(val x: Float, val y: Float, val z: Float)

/** 可点场景点（锚 + §4.10 键后缀 [id]·UI 解析 `world_spot_title_<id>`/`world_spot_body_<id>`·§3.3/§4.10）。 */
data class InteriorFlavorSpot(val anchor: InteriorAnchor, val id: String)

/** 室内三角流几何（lit/emis/precip 三流·按天气昼夜已定型·W9d 图纸 §3.3）。 */
class InteriorGeometryData(val lit: FloatArray, val emis: FloatArray, val precip: FloatArray)

/**
 * 一间室内的装载数据（W9d 图纸 §3.3）：几何 + 演员锚位（native/guest≤2·宠物仅 yunye_home×3）+ 可点场景点 +
 * 热气锚 + 当前天气/昼夜。由 [InteriorSceneData.of] 按 (placeId, weather, night) 建。
 */
class InteriorData(
    val placeId: String,
    val cityId: String,
    val placeName: String,
    val geometry: InteriorGeometryData,
    val nativeAnchor: InteriorAnchor?,
    val guestSlots: List<InteriorAnchor>,
    val petSpots: List<InteriorAnchor>,
    val flavorSpots: List<InteriorFlavorSpot>,
    val steamSpots: List<InteriorAnchor>,
    val weather: WorldWeatherKind,
    val night: Boolean,
)

/**
 * 一间室内的静态定义（布局文件产出·W9d 图纸 §4.2B/C）：per-间调色 + 家具建造闭包（调 [InteriorKit]）+ 锚位表。
 * 房壳与窗景/降水由 [InteriorSceneData.of] 统一补，[build] 只摆本间家具。
 */
internal class InteriorRoomDef(
    val cityId: String,
    val placeName: String,
    val plankA: DoubleArray,
    val plankB: DoubleArray,
    val skirt: DoubleArray,
    val nativeAnchor: InteriorAnchor?,
    val guestSlots: List<InteriorAnchor>,
    val petSpots: List<InteriorAnchor>,
    val flavorSpots: List<InteriorFlavorSpot>,
    val steamSpots: List<InteriorAnchor>,
    val build: (lit: TriStream, emis: TriStream) -> Unit,
)

/**
 * 室内场景装载单源（W9d 图纸 §3.3）：placeId → [InteriorData]（含 §0 列的 10 间可进入建筑）。
 * [hasInterior] = 「走进去」按钮 / 转场守卫的单源。房壳共用、窗景/降水按 (weather, night) 变体（§4.2D）。
 */
object InteriorSceneData {

    // 三城布局合表（§4.2A/B/C·各布局文件产出）。
    internal val ROOMS: Map<String, InteriorRoomDef> =
        InteriorLayoutYunye.ROOMS + InteriorLayoutTaoqiu.ROOMS + InteriorLayoutXiyu.ROOMS

    /** 该 placeId 是否有室内（= §0 的 10 个可进入建筑·「走进去」/转场守卫单源）。 */
    fun hasInterior(placeId: String): Boolean = ROOMS.containsKey(placeId)

    /**
     * 装载 [placeId] 在 (weather, night) 下的室内几何与锚位（无该室内 → null）。
     * 房壳 [InteriorKit.buildShell] + 本间家具 [InteriorRoomDef.build] + 窗景 [InteriorKit.buildSky] +
     * 降水 [InteriorKit.buildPrecip]。纯计算（Default 线程调用·VM 带缓存）。
     */
    fun of(placeId: String, weather: WorldWeatherKind, night: Boolean): InteriorData? {
        val def = ROOMS[placeId] ?: return null
        val lit = TriStream(1 shl 14)
        val emis = TriStream(4096)
        val precip = TriStream(2048)
        InteriorKit.buildShell(lit, def.plankA, def.plankB, def.skirt)
        def.build(lit, emis)
        InteriorKit.buildSky(emis, night)
        InteriorKit.buildPrecip(precip, weather)
        return InteriorData(
            placeId = placeId,
            cityId = def.cityId,
            placeName = def.placeName,
            geometry = InteriorGeometryData(lit.toFloatArray(), emis.toFloatArray(), precip.toFloatArray()),
            nativeAnchor = def.nativeAnchor,
            guestSlots = def.guestSlots,
            petSpots = def.petSpots,
            flavorSpots = def.flavorSpots,
            steamSpots = def.steamSpots,
            weather = weather,
            night = night,
        )
    }
}
