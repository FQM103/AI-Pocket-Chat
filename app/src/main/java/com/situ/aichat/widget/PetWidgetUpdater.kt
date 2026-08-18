package com.situ.aichat.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 让宠物小组件重新渲染（1:1 iOS `PetCareService.syncToWidget` → `reloadTimelines`）。
 *
 * **安卓地道适配**：小组件同进程直接读 Room，无需写共享存储——只需在宠物状态变化后 nudge 一次重渲染。
 * 由宠物护理/散步/启动维护服务在 upsert 后调用。无已添加的小组件时 [updateAll] 安全无操作。
 */
@Singleton
class PetWidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun refresh() {
        try {
            PetGlanceWidget().updateAll(context)
        } catch (_: Exception) {
            // 无小组件实例 / 系统拒绝时静默：小组件是可选锦上添花，绝不影响主流程。
        }
    }
}
