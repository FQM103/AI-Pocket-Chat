package com.situ.aichat.work

import android.content.Context
import com.situ.aichat.data.local.SettingsPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主动弹一次「HyperOS 后台可靠性引导」的单一来源（13.7a；用户拍板「首次开启后台功能时弹」）。
 *
 * 真后台基建（周期 worker / 精确闹钟 / 前台服务）已就位，但国行 ROM 默认会杀后台 → 不引导用户加
 * 「电池无限制 + 自启动白名单」基建就半废。各「依赖后台」的功能开关（定时提醒 / 自动备份 / 最新优先
 * 通知 / 日记自动生成…）在被开启时调 [onBackgroundFeatureEnabled]；本控制器决定是否弹（一次性）。
 *
 * 由 [com.situ.aichat.ui.AppViewModel] 暴露 [visible] 给 app 根观察、弹对话框；点「去设置」跳后台运行
 * 保障页（电池 + 自启动两张卡都在那）。@Singleton 让任意屏的开关都能触发同一个一次性弹窗。
 */
@Singleton
class ReliabilityPromptController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SettingsPreferences,
) {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /**
     * 用户首次开启某个依赖后台的功能时调用（suspend，跑在调用方 scope）。
     * 一次性：已弹过则跳过；已豁免电池优化（用户显然到过这些设置）也跳过、不打扰。
     * 决定要弹时立即记 flag —— 即便用户点「暂不」，也只弹这一次。
     */
    suspend fun onBackgroundFeatureEnabled() {
        if (preferences.hasPromptedReliability.first()) return
        if (BackgroundReliability.isIgnoringBatteryOptimizations(context)) return
        preferences.markReliabilityPrompted()
        _visible.value = true
    }

    /** 关闭对话框（「去设置」或「暂不」都调；flag 已在弹出时记下，不会再弹）。 */
    fun dismiss() {
        _visible.value = false
    }
}
