package com.situ.aichat.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 宠物状态小组件的 AppWidget 接收器（系统通过它创建/更新组件）。
 * 依赖注入走 [PetGlanceWidget] 内的 Hilt EntryPoint，这里无需 @AndroidEntryPoint。
 */
class PetGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PetGlanceWidget()
}
