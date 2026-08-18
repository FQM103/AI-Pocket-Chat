package com.situ.aichat

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.notification.NotificationChannels
import com.situ.aichat.ui.gift.GiftImageStore
import com.situ.aichat.ui.pet.PetSpriteLoader
import com.situ.aichat.util.AvatarStore
import com.situ.aichat.util.ContentImageStore
import com.situ.aichat.util.LocaleManager
import com.situ.aichat.util.WallpaperStore
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AIChatApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    /** 上下文日志记录器（批 D）：仅在启动时跑一次失败率审计；写入接线在各 LLM 调用站。 */
    @Inject lateinit var contextLogService: ContextLogService

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // 通知渠道(P6.1a)：发任何通知前必须先建好渠道(Android 8+)。幂等，可重复调用。
        NotificationChannels.ensureCreated(this)
        // 系统级单独语言(13.10d)：33+ 一次性把旧语言选择迁移进框架 LocaleManager（保持首启简体中文默认 + 不丢老用户选择）。
        LocaleManager.ensureDefaultLocale(this)
        // 上下文日志(批 D)：启动时跑一次近 24h 失败率审计（移植 iOS auditRecentFailureRates）；自有 scope、不阻塞启动。
        contextLogService.auditRecentFailureRates()
    }

    /**
     * 内存压力时收缩五处图片 [android.util.LruCache]（P15.2 #21，对齐 iOS `AIChatApp.didReceiveMemoryWarning`）。
     * evict 仅清缓存不 recycle bitmap（Compose / 通知可能仍持有显示中）。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AvatarStore.onTrimMemory(level)
        GiftImageStore.onTrimMemory(level)
        ContentImageStore.onTrimMemory(level)
        WallpaperStore.onTrimMemory(level)
        PetSpriteLoader.onTrimMemory(level)
    }

    /**
     * 自定义 WorkManager 初始化(P5.0)：注入 Hilt 的 [HiltWorkerFactory]，让后台任务(@HiltWorker)
     * 能拿到现有的仓库 / LLM 客户端。默认初始化器已在 AndroidManifest 中关闭，改用按需(on-demand)初始化。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
