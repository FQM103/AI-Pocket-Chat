package com.situ.aichat.ui.offline

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import java.util.Locale

// 见面回忆「那晚的天色」调色系统（SKY-1·契约 FABLE5_MEETING_MEMORY_SKY_PROPOSAL §2·色值=图纸 §2 表
// + review-R1 🔴-1 返工落值）。天色 = 时段桶（底色）× 情绪（叠色+天气元素）；文字/底纱配对与停色同源，
// 达标依据 = ColorContrastTest.meetingSky_textBands_compositedContrast 真实文字带扫描（≥4.5:1 红线）。

/** 情绪键解析单源（与 iOS finalMood rawValue 一致；[OfflineMoodTheme.forMood] 委托此处）。 */
enum class OfflineMoodKind {
    WARM, SWEET, MELANCHOLIC, AWKWARD, NEUTRAL;

    companion object {
        fun fromRaw(raw: String?): OfflineMoodKind = when (raw?.lowercase(Locale.ROOT)) {
            "warm" -> WARM
            "sweet" -> SWEET
            "melancholic" -> MELANCHOLIC
            "awkward" -> AWKWARD
            else -> NEUTRAL
        }
    }
}

/** 时段桶（纯视觉分档；文案时段词另走 `scheduleTimeOfDayLabel` 单源，两者互不产出、零耦合）。 */
enum class SkyBucket { DAWN, DAY, DUSK, NIGHT, LATE_NIGHT }

internal fun skyBucketForHour(hour: Int): SkyBucket = when (hour) {
    in 5..7 -> SkyBucket.DAWN
    in 8..15 -> SkyBucket.DAY
    in 16..18 -> SkyBucket.DUSK
    in 19..22 -> SkyBucket.NIGHT
    else -> SkyBucket.LATE_NIGHT
}

/** 天气元素集（绘制层消费；图纸 §0.7 R1 勘误：霞带与活动/meta 带有 y 交叠，带扫描测试按整带叠加保守核）。 */
internal enum class SkyWeather { NONE, GLOW_BANDS, FOG, CLOUDS, SUN_HALO }

/** 一张天色的完整规格：停色已含情绪叠色。 */
internal data class SkySpec(
    val stops: List<Color>,
    val textColor: Color,
    val skyIsLight: Boolean,
    val starCount: Int,
    val starAlphaBoost: Float,
    val moonAlpha: Float,
    val weather: SkyWeather,
    val weatherColor: Color,
    val bottomHaze: Boolean,
)

internal object MeetingSky {
    val WarmWhite = Color(0xFFF5EFEA)
    val Ink = Color(0xFF2E2925)
    val Haze = Color(0xFF221E28)
    val Moon = Color(0xFFEFE6CF)

    // ---- hero 底纱（契约 §2.1：纱 = 为保文字落点 ≥4.5:1 而设的装置）----
    // R1 🔴-1 返工落值（2026-07-10 review·代理点→带扫描勘误）：原「0.74 起线性 →0.5」在 meta 带
    // （y 0.79–0.92 = MeetingSkyTextBands.HERO_META）只余 α0.13–0.29 压不住 4.5 → 改两段折线前移：
    // (HAZE_START,0)→(HAZE_KNEE_Y,HAZE_KNEE_ALPHA)→(1.0,HAZE_ALPHA)，拐点钉在 meta 带起点。
    // α(y) 单源 = [hazeAlphaAt]：MeetingSkyCanvas 的 Brush 三停与 ColorContrastTest 带扫描同出此处。
    const val HAZE_START = 0.50f
    const val HAZE_KNEE_Y = 0.79f
    const val HAZE_KNEE_ALPHA = 0.45f
    const val HAZE_ALPHA = 0.55f
    // 小天窗（52dp）纱：50% 起 0→峰值（R1 🔴-1：0.45→0.55；起点色改 Haze@0 修掉 Color.Transparent
    // 黑分量参与插值的偏黑伪影）。日期带（MeetingSkyTextBands.THUMB_DATE）按此线性 ramp 核对比度。
    const val MINI_HAZE_START = 0.5f
    const val MINI_HAZE_ALPHA = 0.55f

    /** hero 底纱在 y（0..1 卡高分数）处的实际 α——两段折线（与 Canvas 渐变三停严格同构）。 */
    fun hazeAlphaAt(y: Float): Float = when {
        y <= HAZE_START -> 0f
        y <= HAZE_KNEE_Y -> HAZE_KNEE_ALPHA * (y - HAZE_START) / (HAZE_KNEE_Y - HAZE_START)
        else -> HAZE_KNEE_ALPHA + (HAZE_ALPHA - HAZE_KNEE_ALPHA) * (y - HAZE_KNEE_Y) / (1f - HAZE_KNEE_Y)
    }

    // 停色 R1 🔴-1 返工（图纸 §2 授权=只准加深，不准降 4.5 线）：DAWN 停2 605C86→544F78 / 停3
    // C99887→A87B6B、DUSK 停2 8A5250→6E4342 / 停3 D08A66→AC7050（停1 与 DAY/NIGHT/LATE 三桶
    // 维持图纸 §2 原值）。依据 = 带扫描 25 组合×全带 ≥4.5，最紧点清晨×温暖日期带 4.74；含药丸底/
    // 霞带整带叠加的离线保守模型最差 4.54（复算脚本见返工提交说明）。
    private val dawn = listOf(Color(0xFF4A5A84), Color(0xFF544F78), Color(0xFFA87B6B))
    private val day = listOf(Color(0xFFA8C4DC), Color(0xFFC9DAE8), Color(0xFFE8E3D5))
    private val dusk = listOf(Color(0xFF3E3450), Color(0xFF6E4342), Color(0xFFAC7050))
    private val night = listOf(Color(0xFF2E3450), Color(0xFF4A4668), Color(0xFF7A5E74))
    private val late = listOf(Color(0xFF232A44), Color(0xFF2C3350), Color(0xFF4A4260))

    fun spec(bucket: SkyBucket, kind: OfflineMoodKind): SkySpec {
        val base = when (bucket) {
            SkyBucket.DAWN -> dawn
            SkyBucket.DAY -> day
            SkyBucket.DUSK -> dusk
            SkyBucket.NIGHT -> night
            SkyBucket.LATE_NIGHT -> late
        }
        val (tint, amount) = when (kind) {
            OfflineMoodKind.WARM -> Color(0xFFE08A3C) to 0.18f
            OfflineMoodKind.SWEET -> Color(0xFFD4537E) to 0.16f
            OfflineMoodKind.MELANCHOLIC -> Color(0xFF5C54A0) to 0.20f
            OfflineMoodKind.AWKWARD -> Color(0xFF8E8AA0) to 0.22f
            OfflineMoodKind.NEUTRAL -> Color(0xFF6B7488) to 0.22f
        }
        val light = bucket == SkyBucket.DAY
        val baseStars = when (bucket) {
            SkyBucket.LATE_NIGHT -> 14
            SkyBucket.NIGHT -> 10
            SkyBucket.DAWN -> 2
            else -> 0
        }
        val baseMoon = if (bucket == SkyBucket.NIGHT || bucket == SkyBucket.LATE_NIGHT) 1f else 0f
        return SkySpec(
            stops = base.map { lerp(it, tint, amount) },
            textColor = if (light) Ink else WarmWhite,
            skyIsLight = light,
            starCount = if (kind == OfflineMoodKind.MELANCHOLIC || kind == OfflineMoodKind.AWKWARD) baseStars / 2 else baseStars,
            starAlphaBoost = if (kind == OfflineMoodKind.MELANCHOLIC) 0.2f else 0f,
            moonAlpha = if (kind == OfflineMoodKind.NEUTRAL) baseMoon * 0.6f else baseMoon,
            weather = when (kind) {
                OfflineMoodKind.WARM -> if (light) SkyWeather.SUN_HALO else SkyWeather.GLOW_BANDS
                OfflineMoodKind.SWEET -> SkyWeather.GLOW_BANDS
                OfflineMoodKind.MELANCHOLIC -> SkyWeather.NONE
                OfflineMoodKind.AWKWARD -> SkyWeather.FOG
                OfflineMoodKind.NEUTRAL -> SkyWeather.CLOUDS
            },
            weatherColor = when (kind) {
                OfflineMoodKind.WARM -> if (light) Color(0xFFF2C978) else Color(0xFFF2B98A)
                OfflineMoodKind.SWEET -> Color(0xFFEFA8B8)
                else -> WarmWhite
            },
            bottomHaze = !light,
        )
    }
}
