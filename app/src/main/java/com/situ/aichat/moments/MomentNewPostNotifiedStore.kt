package com.situ.aichat.moments

import android.content.Context
import com.situ.aichat.util.DateFormatters
import java.time.ZoneId

/**
 * 「X 发了新动态」系统通知的「每角色每天≤1」节流台账（13.7e）。记下每个角色今天是否已就「新动态」推过一条系统
 * 通知，杜绝同一角色一天多帖（发帖上限可达 5）被重复打扰。
 *
 * SharedPreferences 按 uuid 存（`MomentNewPostNotified_{uuid}` = 上次推送时刻），仅当天有效（同 iOS
 * `Calendar.isDateInToday`）。SharedPreferences 持久化 → 跨 15min 周期 worker 进程、跨 App 被杀仍生效（不能用内存 map）。
 * 套路 1:1 复用 [MomentOwedPostStore]。
 */
object MomentNewPostNotifiedStore {
    private const val PREFS = "moment_new_post_notified"
    private const val KEY_PREFIX = "MomentNewPostNotified_"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 标记 [characterUuid] 今天已就新动态推过一条系统通知。 */
    fun markNotified(context: Context, characterUuid: String, nowMillis: Long = System.currentTimeMillis()) {
        prefs(context).edit().putLong(KEY_PREFIX + characterUuid, nowMillis).apply()
    }

    /** [characterUuid] 今天是否已推过（同一日历日内有效）。 */
    fun wasNotifiedToday(
        context: Context,
        characterUuid: String,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val last = prefs(context).getLong(KEY_PREFIX + characterUuid, 0L)
        if (last <= 0L) return false
        return DateFormatters.startOfDayMillis(last, zone) == DateFormatters.startOfDayMillis(nowMillis, zone)
    }
}
