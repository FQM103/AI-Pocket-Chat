package com.situ.aichat.world

import com.situ.aichat.ui.world.WorldScene
import com.situ.aichat.world.atlas.WorldAtlas

/**
 * debug 直达世界场景的意图暂存（W10 效率契约·**仅 `BuildConfig.DEBUG` 写入/消费**）：MainActivity 读 adb intent
 * extra `debug_world` 存于此，[com.situ.aichat.ui.world.WorldViewModel] bootstrap 完成后 [take] 消费一次（取即清·
 * 单值·@Volatile 跨线程可见）。release 构建两处守卫全短路 → 行为零变化（图纸 §3.3）。
 *
 * adb 用法：`adb shell am start -n com.situ.aichat/.MainActivity --es debug_world "starmap"`。
 */
object WorldDebugEntry {

    @Volatile
    var pending: String? = null

    /** 取并清（消费一次·再取为 null）。 */
    fun take(): String? = pending.also { pending = null }
}

/**
 * debug 直达串 → 场景（§3.3·纯函数·T2-8 直测）：`starmap` / `continent:<regionId>` / `town:<cityId>` /
 * `interior:<cityId>:<placeId>`；格式错 / 区/城查无 → null（调用方忽略留 Planet）。placeId 不在此校验（无效则室内装载兜底）。
 */
internal fun parseDebugScene(spec: String, atlas: WorldAtlas.Atlas): WorldScene? {
    val parts = spec.split(":")
    return when (parts.firstOrNull()) {
        "starmap" -> if (parts.size == 1) WorldScene.StarMap else null
        "continent" -> parts.getOrNull(1)?.takeIf { atlas.regionById(it) != null }?.let { WorldScene.Continent(it) }
        "town" -> parts.getOrNull(1)?.takeIf { atlas.cityById(it) != null }?.let { WorldScene.Town(it) }
        "interior" ->
            if (parts.size == 3 && parts[1].isNotEmpty() && parts[2].isNotEmpty() && atlas.cityById(parts[1]) != null) {
                WorldScene.Interior(parts[1], parts[2])
            } else {
                null
            }
        else -> null
    }
}
