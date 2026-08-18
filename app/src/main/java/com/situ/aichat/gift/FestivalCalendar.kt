package com.situ.aichat.gift

import android.icu.util.ChineseCalendar
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * 节日日期规则（1:1 iOS `FestivalDateRule`）。公历用 [GregorianCalendar]，农历用系统 [ChineseCalendar]
 * （`android.icu`，API 24+，**无 GMS**，与 iOS `Calendar(identifier:.chinese)` 同为 ICU）——无需第三方农历库。
 */
sealed interface FestivalDateRule {
    /** 公历固定月/日（如 2/14 情人节）。 */
    data class GregorianFixed(val month: Int, val day: Int) : FestivalDateRule

    /** 公历月 + 第 N 个星期几（如 5 月第二个周日=母亲节）。weekday 1=周日~7=周六；ordinal 1~5。 */
    data class GregorianNthWeekday(val month: Int, val weekday: Int, val ordinal: Int) : FestivalDateRule

    /** 农历固定月/日（如 正月初一 春节）。 */
    data class ChineseFixed(val month: Int, val day: Int) : FestivalDateRule
}

/**
 * 单个节日定义（1:1 iOS `Festival`）。[id] 稳定键用作幂等 key；[emoji] GiftCardData note 前缀；[promptHint]
 * 一句话氛围给 LLM 参考（保持简短，让 LLM 按角色个性自由发挥，不写死礼物类型）。
 */
data class Festival(
    val id: String,
    val name: String,
    val emoji: String,
    val promptHint: String,
    val dateRule: FestivalDateRule,
) {
    /**
     * 判定 [dateMillis]（设备默认时区，= iOS `Calendar.current`）是否命中本节日。农历的 month 经 ICU 0-based → +1
     * 对齐 iOS 1-based 农历月；不查 isLeapMonth（极罕见的闰正月初一等也算命中，与 iOS 一致）。
     */
    fun matches(dateMillis: Long): Boolean = when (val rule = dateRule) {
        is FestivalDateRule.GregorianFixed -> {
            val cal = GregorianCalendar().apply { timeInMillis = dateMillis }
            cal.get(Calendar.MONTH) + 1 == rule.month && cal.get(Calendar.DAY_OF_MONTH) == rule.day
        }

        is FestivalDateRule.GregorianNthWeekday -> {
            val cal = GregorianCalendar().apply { timeInMillis = dateMillis }
            cal.get(Calendar.MONTH) + 1 == rule.month &&
                cal.get(Calendar.DAY_OF_WEEK) == rule.weekday &&
                cal.get(Calendar.DAY_OF_WEEK_IN_MONTH) == rule.ordinal
        }

        is FestivalDateRule.ChineseFixed -> {
            val cal = ChineseCalendar().apply { timeInMillis = dateMillis }
            cal.get(Calendar.MONTH) + 1 == rule.month && cal.get(Calendar.DAY_OF_MONTH) == rule.day
        }
    }
}

/**
 * 16 个节日静态表（1:1 iOS `FestivalCalendar`：9 公历固定 + 2 公历第 N 周日 + 5 农历）。**不含清明/重阳/感恩节**
 * （祭扫/敬老/中国用户少，注释见 iOS）。扩展只改本数组，其它层不动。
 */
object FestivalCalendar {

    val allFestivals: List<Festival> = listOf(
        // 公历固定（9 个）
        Festival("new_year", "元旦", "🎊", "新年第一天 · 崭新开始", FestivalDateRule.GregorianFixed(1, 1)),
        Festival("valentines_day", "情人节", "💝", "浪漫节日 · 爱的表达", FestivalDateRule.GregorianFixed(2, 14)),
        Festival("womens_day", "妇女节", "🌸", "对 TA 表达关心和尊重", FestivalDateRule.GregorianFixed(3, 8)),
        Festival("white_valentines", "白色情人节", "🤍", "回礼节日 · 纯净的心意", FestivalDateRule.GregorianFixed(3, 14)),
        Festival("labor_day", "劳动节", "🌿", "假期问候 · 放松时光", FestivalDateRule.GregorianFixed(5, 1)),
        Festival("confession_day", "520", "💘", "我爱你 · 中国网络情人节", FestivalDateRule.GregorianFixed(5, 20)),
        Festival("national_day", "国庆", "🎆", "长假陪伴 · 秋意渐浓", FestivalDateRule.GregorianFixed(10, 1)),
        Festival("christmas", "圣诞", "🎄", "西方浪漫 · 温暖氛围", FestivalDateRule.GregorianFixed(12, 25)),
        Festival("new_year_eve", "跨年夜", "🌠", "辞旧迎新 · 一起看烟花", FestivalDateRule.GregorianFixed(12, 31)),

        // 公历第 N 个周日（2 个，weekday 1=周日）
        Festival("mothers_day", "母亲节", "🌷", "对妈妈或重要女性表达爱", FestivalDateRule.GregorianNthWeekday(5, 1, 2)),
        Festival("fathers_day", "父亲节", "👔", "对爸爸或重要男性表达感谢", FestivalDateRule.GregorianNthWeekday(6, 1, 3)),

        // 农历（5 个，system ICU ChineseCalendar）
        Festival("chinese_new_year", "春节", "🏮", "阖家团圆 · 最重要的节日", FestivalDateRule.ChineseFixed(1, 1)),
        Festival("lantern_festival", "元宵节", "🎇", "团圆 · 花灯满街", FestivalDateRule.ChineseFixed(1, 15)),
        Festival("dragon_boat", "端午节", "🐉", "传统祝福 · 粽叶飘香", FestivalDateRule.ChineseFixed(5, 5)),
        Festival("qixi", "七夕", "✨", "中国传统情人节 · 鹊桥相会", FestivalDateRule.ChineseFixed(7, 7)),
        Festival("mid_autumn", "中秋", "🌕", "团圆 · 思念 · 月圆之夜", FestivalDateRule.ChineseFixed(8, 15)),
    )

    /** 给定日期命中的所有节日（0 或多个，多个同日由调用方按需选）。 */
    fun festivalsMatching(dateMillis: Long): List<Festival> = allFestivals.filter { it.matches(dateMillis) }

    /** 通过 id 查节日。 */
    fun festivalById(id: String): Festival? = allFestivals.firstOrNull { it.id == id }
}
