package com.situ.aichat.prompt.diary

import android.content.Context

/**
 * 服务局部小标记：自动日记因「未配置 API / Key 为空」而跳过（对齐 iOS `DiaryGenerationActor.apiMissingKey`
 * 的 UserDefaults 布尔）。用 SharedPreferences（项目惯例的服务局部 flag，见 PendingDeliveryStore），
 * 不进 AppSettings/Room。日记列表页（7.1.4）据此显示「自动日记因未配置 API 暂停」提示。
 */
object DiaryApiMissingFlag {
    private const val PREFS = "diary_gen_state"
    private const val KEY = "api_missing"

    fun set(context: Context, missing: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, missing).apply()
    }

    fun get(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
}
