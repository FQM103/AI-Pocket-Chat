package com.situ.aichat.util

import android.content.ComponentCallbacks2
import android.util.LruCache

/**
 * 按系统内存压力等级收缩图片 [LruCache]（P15.2 #21，对齐 iOS `AIChatApp.didReceiveMemoryWarning` 清缓存）。
 *
 * evict 仅清 map 不 recycle 位图——Compose / 通知可能仍持有显示中，recycle 会 use-after-free（同
 * [AvatarStore]/[com.situ.aichat.ui.gift.GiftImageStore]/[ContentImageStore]「不在淘汰时 recycle」既有约束）。
 * 由 [com.situ.aichat.AIChatApplication.onTrimMemory] 统一分发给各图片缓存（头像/礼物/内容图/壁纸/宠物精灵帧）。
 * receiver 泛化为 `LruCache<*, *>`（K4·2026-07-12）：收缩逻辑与键值类型无关，宠物帧缓存值为 ImageBitmap。
 */
// API 34 起 MODERATE/RUNNING_* 等级被废弃（不再下发），但常量值不变、旧系统仍下发；34+ 仍会下发
// UI_HIDDEN(20)/BACKGROUND(40)，本 when 对它们照样触发 evict/减半，故跨版本均有效。沿用项目既有 @Suppress 风格。
@Suppress("DEPRECATION")
internal fun LruCache<*, *>.trimForMemoryLevel(level: Int) {
    when {
        level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> evictAll()                  // 60/80：后台 + 系统吃紧，全清
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> trimToSize(maxSize() / 2) // 40：刚进后台，减半
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> evictAll()          // 15/20：前台危急 / UI 隐藏，全清
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> trimToSize(maxSize() / 2) // 10：前台偏紧，减半
    }
}
