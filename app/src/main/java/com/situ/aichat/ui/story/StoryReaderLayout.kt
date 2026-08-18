package com.situ.aichat.ui.story

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.designsystem.ColorContrast
import com.situ.aichat.ui.designsystem.Palette
import com.situ.aichat.story.StoryReaderTypography

/**
 * 阅读器布局常量 + 纸面自适应配色（源自 iOS `StoryReaderLayout`，`AppTheme.swift:174-247`）。
 *
 * **2026-08-03 心情视觉层退役**：原「心情是否深色系 × 系统深浅模式」二维收缩为**单轴 `isDark`**——
 * 纸面恒为中性双档（见 [StoryMoodPalette]），`mood` 形参、`darkMoods` 四值集合与 `isDarkMood` 谓词一并删除。
 * 纯函数（返回 Compose [Color]，无 @Composable 依赖），便于复用与核对。
 */
object StoryReaderLayout {
    // 正文/whisper/shout/dropCap 字号已收编进 StoryReaderTypography 四档查表（P1-6 字号分档；
    // 默认档=iOS 原值 18/15/22/38），此处只留与档位无关的布局常量。
    /** 行高倍率（ST7d·契约 §6.4-③「行高提至 ~1.8」——超越 iOS 旧「字号+12」加法，更贴长文阅读呼吸）。 */
    const val LINE_HEIGHT_MULTIPLIER = 1.8f
    /** 正文水平内边距。 */
    val horizontalPadding = 28.dp
    /** 最大内容宽度（大屏防一行过长）。 */
    val maxContentWidth = 600.dp
    /** 场景分隔上下间距。 */
    val sceneDividerSpacing = 32.dp
    /** 封面章节号字间距。 */
    val chapterNumberKerning = 4.sp
    /** 装饰文字字间距（场景/选择区头部）。 */
    val ornamentKerning = 3.sp
    /** 章节封面占屏幕高度比例。 */
    const val coverHeightRatio = 0.55f

    /** 按字号推导 lineHeight（= 字号 × [LINE_HEIGHT_MULTIPLIER]·随字号档同比放大）。 */
    fun lineHeight(fontSizeSp: TextUnit): TextUnit = (fontSizeSp.value * LINE_HEIGHT_MULTIPLIER).sp

    /**
     * 段落（文字块）间距，随字号档同比缩放（契约 §6.4-③「段距随字号档缩放」）：小档更紧、大档更松，
     * 与 [lineHeight] 一起构成随档呼吸的排版。默认档（body 18）≈ 18dp，贴 mockup 段距观感。
     */
    fun paragraphSpacing(typography: StoryReaderTypography): Dp = typography.bodySp.dp

    /**
     * 顶栏/底部胶囊等「悬浮控件」的半透明垫底色，随当前背景明暗自适应（深背景→深色垫底衬浅字，
     * 浅背景→浅色垫底衬深字），与 [textColor] 配套。
     */
    fun chromeScrimColor(isDark: Boolean): Color = when {
        isDark -> Color.Black.copy(alpha = 0.30f)
        else -> Color.White.copy(alpha = 0.72f)
    }

    /**
     * 悬浮胶囊（顶栏 / 底部进度条）的毛玻璃发丝描边（迎光边·= GlassBackdrop 要素④在纯渐变背景上的等价）：
     * 深底 → 白高光细边，浅底 → 暖墨极淡线，让胶囊在纸面渐变上清楚成形。与 [chromeScrimColor] 配套。
     */
    fun chromeBorderColor(isDark: Boolean): Color = when {
        isDark -> Color.White.copy(alpha = 0.18f)
        else -> Color.Black.copy(alpha = 0.12f)
    }

    /**
     * 悬浮 chrome 小岛（顶栏三件 / 底部进度胶囊 / ⋮ 触发钮）的玻璃垫底：岛浮在**正文文字**上，
     * [chromeScrimColor] 的 72% 会让字影渗上来串行（2026-07-04 真机反馈）→ 换菜单同族暖纸色并提实
     * （浅 90% / 深 85%·保留一丝透底当玻璃味），随明暗换肤。卡片类（选项卡等·底下无字）仍用 chromeScrimColor。
     */
    fun islandScrimColor(isDark: Boolean): Color = when {
        isDark -> Color(0xFF221E19).copy(alpha = 0.85f)
        else -> Color(0xFFFDFAF4).copy(alpha = 0.90f)
    }

    /** 悬浮 chrome 小岛统一高度（顶栏圆钮/标题胶囊/底部进度胶囊同一尺度·头脚协调）。 */
    val islandHeight = 44.dp

    /**
     * ⋮ 菜单的玻璃垫底（与 [chromeScrimColor] 同族随明暗换肤，但提到 94% 不透明——菜单浮在正文文字上，
     * 弹窗又拿不到系统级背景模糊（第三方模糊库为铁律禁区），垫底不够实会串行影响可读）。
     */
    fun menuSurfaceColor(isDark: Boolean): Color = when {
        isDark -> Color(0xFF1E1814).copy(alpha = 0.94f)
        else -> Color(0xFFFDFAF4).copy(alpha = 0.94f)
    }

    /** ⋮ 菜单内的发丝分隔线（比 [chromeBorderColor] 更淡一档，只分组不抢戏）。 */
    fun menuDividerColor(isDark: Boolean): Color = when {
        isDark -> Color.White.copy(alpha = 0.12f)
        else -> Color.Black.copy(alpha = 0.10f)
    }

    /**
     * ⋮ 菜单动作行前导图标的暖陶点缀。**脱主题固定值**（同 StoryShareCardRenderer「固定暖陶色板」先例）：
     * 阅读器纸面与 App 主题（暖中性 / 青花）正交，深玻璃取浅陶、浅玻璃取深陶保对比。
     */
    fun menuAccentColor(isDark: Boolean): Color = when {
        isDark -> Color(0xFFD8A188)
        else -> Color(0xFF9A5B3E)
    }

    /** ⋮ 菜单开关的陶土选中轨道（脱主题固定值·白拇指在其上两种玻璃均清晰）。 */
    val menuSwitchTrack = Color(0xFFA96B4F)

    /** [onAccentTextColor] 的两个候选：暖白 / 深墨（脱主题固定值·理由同 [menuAccentColor]）。 */
    private val accentTextWarmWhite = Color(0xFFF5EFEA)
    private val accentTextDeepInk = Color(0xFF2E2925)

    /**
     * 陶土**实底**上的文字色（卷三 §4.3 首用：三档快评的已选胶囊 = 字覆于 [menuAccentColor] 实底）。
     *
     * 不写硬分支，而是**当场比对比度、取高的那个**——日后谁调了 [menuAccentColor] 的落值，
     * 字色会自动跟着翻面，不会静默糊掉。深浅两档的 ≥4.5:1 由
     * [com.situ.aichat.ui.designsystem.ColorContrastTest] 看门（实测浅纸 4.67 / 深纸 6.42）。
     */
    fun onAccentTextColor(isDark: Boolean): Color {
        val accent = menuAccentColor(isDark)
        val warm = ColorContrast.ratio(accentTextWarmWhite, accent)
        val ink = ColorContrast.ratio(accentTextDeepInk, accent)
        return if (warm >= ink) accentTextWarmWhite else accentTextDeepInk
    }

    /**
     * 建议完结卡（ST11 §4.2）的金色：图标 / 边 / 底 / 右上角「✦」同取此色。
     *
     * 取值 = `economy.gold` 的两档同源色（[Palette.Gold] / [Palette.GoldDark]），但**按纸面深浅取档、
     * 不跟 App 主题走**——理由同 [menuAccentColor]：阅读器纸面与 App 主题（暖中性 / 青花）正交，
     * 直接读 `AppTheme.colors.economy.gold` 会在「浅主题 + 深纸面」撞成深金压深纸，
     * 实测 **2.97:1 < 3.0** 不达标（ColorContrastTest 抓到）。浅纸取深金 / 深纸取浅金才保对比。
     */
    fun suggestGoldColor(isDark: Boolean): Color =
        if (isDark) Palette.GoldDark else Palette.Gold

    /** 正文颜色：暗色纸面 → 白字，浅色纸面 → 近黑。 */
    fun textColor(isDark: Boolean): Color = when {
        isDark -> Color.White.copy(alpha = 0.88f)
        else -> Color(0xFF1A1A1A)
    }

    /** 次要文字颜色（场景分隔、章节号等）。 */
    fun secondaryTextColor(isDark: Boolean): Color = when {
        isDark -> Color.White.copy(alpha = 0.5f)
        else -> Color.Black.copy(alpha = 0.4f)
    }

    /** 装饰符颜色。 */
    fun ornamentColor(isDark: Boolean): Color = when {
        isDark -> Color.White.copy(alpha = 0.25f)
        else -> Color.Black.copy(alpha = 0.2f)
    }

    /** 正文区半透明遮罩颜色（提升文字对比度）。 */
    fun readingOverlayColor(isDark: Boolean): Color = when {
        isDark -> Color.Black.copy(alpha = 0.08f)
        else -> Color.White.copy(alpha = 0.1f)
    }
}
