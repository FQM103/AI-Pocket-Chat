package com.situ.aichat.ui.world.continent

/**
 * 十大区「变脸」样式表（W9b 图纸 §4.1B 三表逐值·§9 锁死·styleKey 为键）。
 *
 * 四样例区（yunze/nanyu/huangsha/jibei = demo valley/isles/plateau/tundra）逐字段照抄对版 demo
 * `design/world/continent-3d-demo.html` REGIONS；其余六区为图纸作者落值（十大区地形 sanity 已脚本互证·
 * 装机后随两项 9a 复审项一并目验）。颜色用 **Double**（0..1·= demo `C(hex)` float64 语义），供 [ContinentMath.colorFor]
 * 逐值配色；warm/haze/天空停靠色为 GL uniform 用 Float。天空 5 停靠：部分区原为 4 停靠，第 5 停靠 = 共线中点
 * 插值（渲染等价·图纸 §11.Z），表三为互证脚本输出原样。
 */
internal data class RegionStyle(
    val styleKey: String,
    val seed: Double,
    val sea: Double,
    val amp: Double,
    val coast: Double,
    val padH: Double,
    val terrace: Boolean,
    val snowLine: Double, // 99 = 无雪
    val treeN: Int,
    val trunk: Double,
    val treeR: Double,
    val treeH: Double,
    val warm: FloatArray,
    val haze: FloatArray,
    val water: DoubleArray,
    val bed: DoubleArray,
    val beach: DoubleArray,
    val g1: DoubleArray,
    val g2: DoubleArray,
    val cliff: DoubleArray,
    val snow: DoubleArray,
    val rock: DoubleArray,
    val earth: DoubleArray,
    val leafs: List<DoubleArray>,
    val sky: List<SkyStop>,
    val glowA: Float,
)

/** 天空竖向渐变的一个停靠（[pos] = 顶→底比例 0..1·[color] = RGB 0..1 GL uniform）。 */
internal data class SkyStop(val pos: Float, val color: FloatArray)

internal object ContinentStyle {

    /** 无雪线哨兵（demo `snowLine:99`）。 */
    private const val NO_SNOW = 99.0

    /** 颜色 hex → Double RGB 0..1（= demo `C(hex)`·float64）。 */
    private fun c(hex: Int): DoubleArray = doubleArrayOf(
        ((hex shr 16) and 255) / 255.0,
        ((hex shr 8) and 255) / 255.0,
        (hex and 255) / 255.0,
    )

    /** GL uniform 三分量色（Float·warm/haze/天空停靠）。 */
    private fun f3(r: Float, g: Float, b: Float): FloatArray = floatArrayOf(r, g, b)

    /** 天空 5 停靠（`pos% to hex`·pos 传比例 0..1）。 */
    private fun sky5(vararg stops: Pair<Double, Int>): List<SkyStop> = stops.map { (pos, hex) ->
        SkyStop(pos.toFloat(), f3(((hex shr 16) and 255) / 255f, ((hex shr 8) and 255) / 255f, (hex and 255) / 255f))
    }

    /** styleKey → 样式（键集 == [com.situ.aichat.world.atlas.WorldRegions] styleKey 双射·ContinentStyleTest 守）。 */
    val STYLES: Map<String, RegionStyle> = mapOf(
        "willow_mist" to RegionStyle(
            styleKey = "willow_mist", seed = 11.7, sea = 0.46, amp = 5.2, coast = 0.60, padH = 1.5,
            terrace = false, snowLine = 4.4, treeN = 60, trunk = 0.7, treeR = 0.8, treeH = 1.5,
            warm = f3(1.0f, 0.86f, 0.70f), haze = f3(0.79f, 0.54f, 0.46f),
            water = c(0x3E5C6E), bed = c(0x51606A), beach = c(0xD9C3A3), g1 = c(0x8FA37E), g2 = c(0x7E926E),
            cliff = c(0xC4A484), snow = c(0xEFEDE9), rock = c(0x9A8B7C), earth = c(0x6B5A48),
            leafs = listOf(c(0x7E926E), c(0x8FA37E)),
            sky = sky5(0.0 to 0x16203A, 0.34 to 0x3A4874, 0.58 to 0x8A6E86, 0.74 to 0xC98A76, 1.0 to 0xE8B87E),
            glowA = 1.0f,
        ),
        "fern_rain" to RegionStyle(
            styleKey = "fern_rain", seed = 19.3, sea = 0.50, amp = 4.6, coast = 0.64, padH = 1.4,
            terrace = false, snowLine = NO_SNOW, treeN = 78, trunk = 0.8, treeR = 0.85, treeH = 1.7,
            warm = f3(0.90f, 0.92f, 0.88f), haze = f3(0.62f, 0.68f, 0.66f),
            water = c(0x3A5A64), bed = c(0x4A5A5E), beach = c(0xC2B393), g1 = c(0x6E8A66), g2 = c(0x59754F),
            cliff = c(0x8A7E6E), snow = c(0xEFEDE9), rock = c(0x84796A), earth = c(0x5E5142),
            leafs = listOf(c(0x4F7350), c(0x6E8A66), c(0x3F6244)),
            sky = sky5(0.0 to 0x131A2E, 0.38 to 0x2C3E58, 0.62 to 0x5E7286, 0.82 to 0x8FA08F, 1.0 to 0xC8B98F),
            glowA = 0.55f,
        ),
        "ochre_dry" to RegionStyle(
            styleKey = "ochre_dry", seed = 41.2, sea = 0.38, amp = 6.0, coast = 0.58, padH = 2.4,
            terrace = true, snowLine = NO_SNOW, treeN = 10, trunk = 0.4, treeR = 0.55, treeH = 0.9,
            warm = f3(1.0f, 0.84f, 0.62f), haze = f3(0.79f, 0.60f, 0.43f),
            water = c(0x4E7080), bed = c(0x6B6455), beach = c(0xD9B98A), g1 = c(0xC9A46B), g2 = c(0xB8935E),
            cliff = c(0x8E6B4E), snow = c(0xFFFFFF), rock = c(0xA67B5C), earth = c(0x7A5B44),
            leafs = listOf(c(0x6B7A52)),
            sky = sky5(0.0 to 0x2A2438, 0.42 to 0x7A5A56, 0.72 to 0xC98A5E, 0.86 to 0xDCAA76, 1.0 to 0xEFC98F),
            glowA = 1.0f,
        ),
        "broadleaf_glow" to RegionStyle(
            styleKey = "broadleaf_glow", seed = 33.8, sea = 0.47, amp = 4.8, coast = 0.62, padH = 1.4,
            terrace = false, snowLine = NO_SNOW, treeN = 84, trunk = 0.7, treeR = 0.9, treeH = 1.5,
            warm = f3(1.0f, 0.88f, 0.72f), haze = f3(0.70f, 0.58f, 0.52f),
            water = c(0x3E5C6E), bed = c(0x50605F), beach = c(0xD3BE9C), g1 = c(0x7E9A6A), g2 = c(0x668653),
            cliff = c(0xB09878), snow = c(0xEFEDE9), rock = c(0x93876F), earth = c(0x64543F),
            leafs = listOf(c(0x5E8A50), c(0x7E9A6A)),
            sky = sky5(0.0 to 0x141B32, 0.36 to 0x34406A, 0.60 to 0x6E5E80, 0.78 to 0xB7856F, 1.0 to 0xE2B380),
            glowA = 0.85f,
        ),
        "tea_terrace" to RegionStyle(
            styleKey = "tea_terrace", seed = 27.5, sea = 0.42, amp = 3.6, coast = 0.58, padH = 1.3,
            terrace = true, snowLine = NO_SNOW, treeN = 34, trunk = 0.5, treeR = 0.7, treeH = 0.9,
            warm = f3(1.0f, 0.90f, 0.74f), haze = f3(0.74f, 0.66f, 0.50f),
            water = c(0x4A6A6E), bed = c(0x566258), beach = c(0xD6C49E), g1 = c(0x8FA860), g2 = c(0x74924E),
            cliff = c(0xB49C7A), snow = c(0xEFEDE9), rock = c(0x9A8B7C), earth = c(0x6B5A44),
            leafs = listOf(c(0x6E9A50), c(0x86AC64)),
            sky = sky5(0.0 to 0x16203A, 0.40 to 0x3A5568, 0.66 to 0x7E9A80, 0.84 to 0xC9BC82, 1.0 to 0xEFD9A0),
            glowA = 0.9f,
        ),
        "pine_snow" to RegionStyle(
            styleKey = "pine_snow", seed = 57.9, sea = 0.50, amp = 5.0, coast = 0.62, padH = 1.5,
            terrace = false, snowLine = 1.2, treeN = 24, trunk = 0.5, treeR = 0.5, treeH = 1.9,
            warm = f3(0.88f, 0.92f, 1.0f), haze = f3(0.62f, 0.70f, 0.77f),
            water = c(0x4E7290), bed = c(0x5E6C7A), beach = c(0xC9CDD2), g1 = c(0xE2E4E4), g2 = c(0xD5DADC),
            cliff = c(0x6E7686), snow = c(0xEDEBE6), rock = c(0x6E7686), earth = c(0x55606E),
            leafs = listOf(c(0x46604E)),
            sky = sky5(0.0 to 0x101828, 0.40 to 0x2E4258, 0.72 to 0x8FA6B8, 0.86 to 0xB4C4D0, 1.0 to 0xD9E2E8),
            glowA = 0.35f,
        ),
        "slate_peak" to RegionStyle(
            styleKey = "slate_peak", seed = 64.2, sea = 0.44, amp = 7.2, coast = 0.55, padH = 2.0,
            terrace = false, snowLine = 4.6, treeN = 40, trunk = 0.6, treeR = 0.6, treeH = 1.7,
            warm = f3(1.0f, 0.84f, 0.66f), haze = f3(0.76f, 0.58f, 0.48f),
            water = c(0x466A7E), bed = c(0x5C6068), beach = c(0xC9B694), g1 = c(0x7E9068), g2 = c(0x687E56),
            cliff = c(0x7E7276), snow = c(0xF2EFE9), rock = c(0x8A7E80), earth = c(0x5A5250),
            leafs = listOf(c(0x4E6E52), c(0x5E7E5A)),
            sky = sky5(0.0 to 0x191A30, 0.38 to 0x4A3A56, 0.62 to 0x9A5E60, 0.80 to 0xD98A5E, 1.0 to 0xF2C284),
            glowA = 1.0f,
        ),
        "palm_sand" to RegionStyle(
            styleKey = "palm_sand", seed = 23.4, sea = 0.54, amp = 4.2, coast = 0.86, padH = 1.2,
            terrace = false, snowLine = NO_SNOW, treeN = 18, trunk = 1.3, treeR = 0.9, treeH = 0.8,
            warm = f3(1.0f, 0.92f, 0.78f), haze = f3(0.50f, 0.69f, 0.62f),
            water = c(0x2E6E72), bed = c(0x3E7268), beach = c(0xEDD9AC), g1 = c(0x6FA86E), g2 = c(0x8FBF7E),
            cliff = c(0xB99A74), snow = c(0xFFFFFF), rock = c(0x9A8B7C), earth = c(0x7A6B52),
            leafs = listOf(c(0x5F9E6E), c(0x74B07A)),
            sky = sky5(0.0 to 0x1B2B44, 0.38 to 0x3E6C86, 0.66 to 0x7FBFB0, 0.83 to 0xB8CCA8, 1.0 to 0xF2D9A0),
            glowA = 0.9f,
        ),
        "cypress_star" to RegionStyle(
            styleKey = "cypress_star", seed = 72.6, sea = 0.52, amp = 4.4, coast = 0.70, padH = 1.4,
            terrace = false, snowLine = NO_SNOW, treeN = 46, trunk = 0.9, treeR = 0.55, treeH = 1.9,
            warm = f3(0.92f, 0.90f, 0.96f), haze = f3(0.55f, 0.60f, 0.72f),
            water = c(0x2E5670), bed = c(0x3E5866), beach = c(0xD9CBA8), g1 = c(0x6E8A62), g2 = c(0x587450),
            cliff = c(0x8A8072), snow = c(0xEFEDE9), rock = c(0x847A6C), earth = c(0x585244),
            leafs = listOf(c(0x3E6248), c(0x4E7254)),
            sky = sky5(0.0 to 0x0E1630, 0.40 to 0x22315A, 0.66 to 0x40598A, 0.84 to 0x7E86A8, 1.0 to 0xC9BFA0),
            glowA = 0.5f,
        ),
        "storm_cape" to RegionStyle(
            styleKey = "storm_cape", seed = 86.1, sea = 0.55, amp = 5.4, coast = 0.74, padH = 1.6,
            terrace = false, snowLine = NO_SNOW, treeN = 14, trunk = 0.5, treeR = 0.5, treeH = 1.1,
            warm = f3(0.90f, 0.88f, 0.82f), haze = f3(0.60f, 0.62f, 0.64f),
            water = c(0x3A5262), bed = c(0x4A545A), beach = c(0xC4B694), g1 = c(0x7E8A62), g2 = c(0x6A7852),
            cliff = c(0x6E6A62), snow = c(0xEFEDE9), rock = c(0x767061), earth = c(0x524C40),
            leafs = listOf(c(0x556A4A)),
            sky = sky5(0.0 to 0x10141F, 0.42 to 0x2A3444, 0.68 to 0x55606C, 0.86 to 0x8A8478, 1.0 to 0xB8A484),
            glowA = 0.3f,
        ),
    )
}
