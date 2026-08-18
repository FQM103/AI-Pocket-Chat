package com.situ.aichat.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 最新动态（朋友圈）小组件的 AppWidget 接收器（13.9b）。系统通过它创建/更新组件；
 * 依赖注入走 [MomentFeedGlanceWidget] 内的 Hilt EntryPoint，这里无需 @AndroidEntryPoint。
 */
class MomentFeedGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MomentFeedGlanceWidget()
}
