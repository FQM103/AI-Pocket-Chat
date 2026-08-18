package com.situ.aichat.ui.story

import androidx.compose.ui.graphics.Color

/**
 * 程序化封面调色与种子（ST7a·契约 §6.1 / D6·照过审 mockup 封面全览取值）。
 *
 * `coverColorScheme`（建故事时 [com.situ.aichat.story.StoryCreationCatalog.coverColorScheme] 落库）→ 莫兰迪双 stop
 * 渐变基色 + 题材纹样（[drawStoryGlyph]）。storyId 作确定性种子做微变化（同题材不同书封面有别）。零图片素材、零第三方。
 */
internal data class StoryCoverPalette(
    val start: Color,
    val end: Color,
    /** 浅底封面（日常米杏）→ 深色竖排书名 + 深色纹样；其余深底 → 暖白。 */
    val lightSurface: Boolean,
)

internal object StoryCoverArt {
    /** 竖排书名 / 纹样描边色（mockup .vt 与 .glyph stroke）。 */
    val titleOnDark = Color(0xFFF5EFEA)
    val titleOnLight = Color(0xFF4A4036)
    val glyphInkOnLight = Color(0xFF7A6A52)

    /** coverColorScheme → 莫兰迪基色（1:1 mockup cvN·题材代表色）。未知/自定义题材回落米杏浅底。 */
    fun palette(scheme: String): StoryCoverPalette = when (scheme) {
        "rose" -> StoryCoverPalette(Color(0xFFBE9A90), Color(0xFFA57F73), false)    // 言情·暖玫
        "amber" -> StoryCoverPalette(Color(0xFF8B979C), Color(0xFF71818A), false)   // 悬疑·灰青
        "violet" -> StoryCoverPalette(Color(0xFF7C9095), Color(0xFF5F757C), false)  // 奇幻·黛青
        "cyan" -> StoryCoverPalette(Color(0xFF3D4A64), Color(0xFF2C374E), false)    // 科幻·深空蓝
        "slate" -> StoryCoverPalette(Color(0xFF4C5E71), Color(0xFF39495B), false)   // 都市·墨蓝
        "crimson" -> StoryCoverPalette(Color(0xFF413C44), Color(0xFF2C2830), false) // 恐怖·玄黑
        "mint" -> StoryCoverPalette(Color(0xFF9DB3A4), Color(0xFF83997F), false)    // 校园·青碧
        "sepia" -> StoryCoverPalette(Color(0xFF8D8298), Color(0xFF746B85), false)   // 历史·黛紫
        "rust" -> StoryCoverPalette(Color(0xFF8B8073), Color(0xFF6F6558), false)    // 末日·灰褐
        else -> StoryCoverPalette(Color(0xFFE7D8C2), Color(0xFFD3BFA2), true)       // 日常/兜底·米杏(浅底)
    }

    fun titleColor(p: StoryCoverPalette): Color = if (p.lightSurface) titleOnLight else titleOnDark

    fun glyphInk(p: StoryCoverPalette): Color = if (p.lightSurface) glyphInkOnLight else titleOnDark

    /** storyId 确定性纹样微旋转（°，[-6,6]）——同书恒定、同题材不同书略有别（§6.1 微变化）。 */
    fun glyphJitterDeg(storyId: String): Float {
        val m = ((storyId.hashCode() % 13) + 13) % 13 // 0..12（floorMod·防负）
        return (m - 6).toFloat()
    }
}
