package com.situ.aichat.diagnostics.perf

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 界面层够到采集件的唯一桥（性能专项卷 0）。
 *
 * 为什么要这层桥：被观测的屏（世界 / 通话 / 星空 / 阅读器 / 上下文日志设置）散落各处，都不该为「采集」这件
 * 与它们业务无关的事去改自己的 ViewModel 签名，更不该自己去摸单例。这里用 Hilt 官方的 `EntryPointAccessors`
 * 从 Application 组件取同一个 `@Singleton`，屏幕侧只写一行。
 *
 * 取不到（Compose 预览、非 Hilt 的 Robolectric 宿主）→ 一律降级成 **no-op**，绝不让采集件把界面搞崩。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PerfComposeEntryPoint {
    fun perfCollector(): PerfCollector
    fun frameMetricsProbe(): FrameMetricsProbe
}

@Composable
private fun rememberPerfEntryPoint(): PerfComposeEntryPoint? {
    val context: Context = LocalContext.current
    return remember(context) {
        runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, PerfComposeEntryPoint::class.java)
        }.getOrNull()
    }
}

/**
 * 被观测屏挂这一行即可：进屏报到、离屏报离。[scene] 传 null = 本次不观测
 * （如世界屏进了室内 / 星图这两个不在锁定名单里的场景）。
 * 结构恒定（判空在 effect 内部），不因 null 与否改变可组合调用结构。
 */
@Composable
fun FrameSceneObserver(scene: String?) {
    val probe = rememberPerfEntryPoint()?.frameMetricsProbe()
    DisposableEffect(probe, scene) {
        if (probe != null && scene != null) probe.onEnter(scene)
        onDispose { if (probe != null && scene != null) probe.onExit() }
    }
}

/**
 * 设置滑杆的「一趟手势」写盘计数器（尺 4）。用法：`onValueChange` 里 [onTick]，
 * `onValueChangeFinished` 里 [onGestureEnd]。
 */
class SettingsWriteRecorder internal constructor(
    private val collector: PerfCollector?,
    private val screen: String,
    private val key: String,
) {
    private var ticks = 0
    private var startMillis = 0L

    /** 滑杆值变了一次。 */
    fun onTick() {
        if (collector?.isEnabled != true) return
        if (ticks == 0) startMillis = System.currentTimeMillis()
        ticks++
    }

    /** 手势结束：落一条 `settings_write` 样本。一次都没动过 → 不落。 */
    fun onGestureEnd() {
        val c = collector ?: return
        val count = ticks
        ticks = 0
        if (count == 0) return
        c.recordSettingsWrite(screen, key, count, System.currentTimeMillis() - startMillis)
    }
}

/** 记住一个 [SettingsWriteRecorder]；采集件取不到时返回的实例是 no-op。 */
@Composable
fun rememberSettingsWriteRecorder(screen: String, key: String): SettingsWriteRecorder {
    val collector = rememberPerfEntryPoint()?.perfCollector()
    return remember(collector, screen, key) { SettingsWriteRecorder(collector, screen, key) }
}
