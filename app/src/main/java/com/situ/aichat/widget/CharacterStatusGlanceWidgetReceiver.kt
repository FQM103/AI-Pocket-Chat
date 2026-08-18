package com.situ.aichat.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 角色「此刻」状态小组件的 AppWidget 接收器（13.9a）。系统通过它创建/更新组件；
 * 依赖注入走 [CharacterStatusGlanceWidget] 内的 Hilt EntryPoint，这里无需 @AndroidEntryPoint。
 */
class CharacterStatusGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CharacterStatusGlanceWidget()
}
