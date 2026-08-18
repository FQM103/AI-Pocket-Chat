package com.situ.aichat.util

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.situ.aichat.data.local.SettingsPreferences
import com.situ.aichat.data.model.AppearanceMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C3#0 冷启反色闪屏根治（Fable-5 批 B）：把应用内深浅外观偏好**镜像到系统级 per-app night mode**
 * （[UiModeManager.setApplicationNightMode]·API 31+），让**系统画的启动窗口（splash）**与应用内容深浅同源。
 *
 * 根因：应用内强制深/浅只作用于 Compose 主题（偏好驱动），而冷启 splash 是系统按 values(-night)/themes.xml
 * 用**系统 uiMode** 解析的——「在 App 内强制与系统反向」的用户每次冷启必闪反色帧。窗口主题 parent 是
 * android:Theme.Material 非 AppCompat 系 → AppCompatDelegate 路线无效（且本工程无 appcompat 依赖）；
 * 正解=系统级 per-app 覆盖。
 *
 * 语义依据（AOSP UiModeManagerService.setApplicationNightMode 源码实证）：YES/NO → per-app 配置覆盖并由
 * 系统**持久化**（下次冷启 splash 即正确）；AUTO/CUSTOM → `UI_MODE_NIGHT_UNDEFINED` = **清除覆盖回到跟随
 * 系统**（公开 javadoc 写的「按晨昏自动」语义只属全局 setNightMode 路径，per-app 路径就是清除）。
 *
 * - **单一观察点**：collect [SettingsPreferences.appearanceMode]——设置屏切换、备份导入等任何写入路径都被
 *   覆盖；首个值兼作存量安装的一次性对账。
 * - **lastApplied 本地哨兵**防每次冷启重复 commit（同值 commit 理论无害，省 binder + 防御性）。哨兵存独立
 *   SharedPreferences：不入备份 zip；用户清数据时系统同时清 per-app 覆盖（javadoc 明示）与本哨兵 → 永不漂移。
 * - **API 29/30**：无 per-app night API → no-op。「强制反向」子集冷启反色仍存在（已知边界·真机批登记），
 *   内容主题不受影响。
 * - 切档时本调用会触发应用 uiMode 配置变更（活动重建）——与系统切深浅档行为一致，仅发生在用户改档瞬间。
 */
@Singleton
class AppNightModeSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false

    /** 幂等启动；由 [com.situ.aichat.ui.AppViewModel] 在 init 调用一次（仿 *WidgetSync 习语）。 */
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (started) return
        started = true
        scope.launch {
            settings.appearanceMode.distinctUntilChanged().collect { apply(it) }
        }
    }

    private fun apply(mode: AppearanceMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_APPLIED, null) == mode.raw) return
        val uiModeManager = context.getSystemService(UiModeManager::class.java) ?: return
        runCatching { uiModeManager.setApplicationNightMode(nightModeFor(mode)) }
            .onSuccess { prefs.edit().putString(KEY_LAST_APPLIED, mode.raw).apply() }
            .onFailure { Log.w(TAG, "setApplicationNightMode 失败: ${it.message}") }
    }

    companion object {
        private const val TAG = "AppNightModeSync"
        private const val PREFS_NAME = "night_mode_sync"
        private const val KEY_LAST_APPLIED = "last_applied_mode"

        /** 三档映射（纯函数·单测锁语义）：DARK→YES、LIGHT→NO、SYSTEM→AUTO（=服务端 UNDEFINED 清除覆盖）。 */
        internal fun nightModeFor(mode: AppearanceMode): Int = when (mode) {
            AppearanceMode.DARK -> UiModeManager.MODE_NIGHT_YES
            AppearanceMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            AppearanceMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        }
    }
}
