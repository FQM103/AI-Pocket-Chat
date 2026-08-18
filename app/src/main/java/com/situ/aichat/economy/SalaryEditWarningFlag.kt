package com.situ.aichat.economy

import android.content.Context

/**
 * 「角色月薪手动编辑」首次提醒只弹一次的设备本地标记（14.6b·1:1 iOS `AppSettings.hasShownSalaryEditWarning`）。
 *
 * SharedPreferences·设备本地（同 [com.situ.aichat.moments.MomentApiMissingFlag] 约定）：这是纯 UI 防打扰标记，
 * per-device「别再唠叨」语义恰当，无需进备份。提醒不是劝阻，是让用户知道「手改月薪会让比例式肉痛反馈失真」。
 */
object SalaryEditWarningFlag {
    private const val PREFS = "economy_ui_state"
    private const val KEY = "salary_edit_warning_shown"

    fun hasShown(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun markShown(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, true).apply()
    }
}
